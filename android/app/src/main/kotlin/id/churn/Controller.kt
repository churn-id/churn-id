package id.churn

import android.content.Context
import android.content.Intent
import android.net.Network
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/** What the screen shows. [MainActivity] draws it. */
sealed interface Screen {
    data object Intro : Screen
    data class Connect(val error: String? = null) : Screen
    data class Working(val title: String) : Screen
    data class TrustKey(val key: String, val fingerprint: String, val changed: Boolean) : Screen
    data class SetUp(val factory: ImeiPair, val warnings: List<String>, val note: String?) : Screen
    data class Home(
        val status: RouterStatus,
        val notes: List<String>,
        val places: List<PlaceCard>,
        /** The router checks' results, once the checks have run. */
        val checks: CheckReport? = null,
    ) : Screen
    data class Swapping(
        val step: Step,
        val log: List<String> = emptyList(),
        /** The step whose lines [log] holds: [step], or the step that ended before it. */
        val logStep: Step = step,
        val running: Boolean = false,
        val error: String? = null,
        /** Step 3's question, which the running step waits for the user to answer. */
        val question: Question? = null,
    ) : Screen

    /** Tier 2's dry run, which only debug builds have, with the lines it reported. */
    data class DryRun(
        val lines: List<String> = emptyList(),
        val running: Boolean = false,
        val error: String? = null,
        /** Whether the last dry run stopped before it put the router back as it was. */
        val unfinished: Boolean = false,
    ) : Screen

    /** Tier 2's removal test, which only debug builds have, with the lines it reported. */
    data class RemovalTest(
        val lines: List<String> = emptyList(),
        val running: Boolean = false,
        val error: String? = null,
        /** Whether the last removal test stopped before it set the router up again. */
        val unfinished: Boolean = false,
    ) : Screen

    /** Tier 3's power cycle test, which only debug builds have, with the lines it reported. */
    data class PowerCycles(
        val lines: List<String> = emptyList(),
        val running: Boolean = false,
        val error: String? = null,
        /** Whether the test waits for the user to switch the router on again. */
        val waiting: Boolean = false,
        /** Whether the last test stopped before it put the router back as it was. */
        val unfinished: Boolean = false,
    ) : Screen

    /** Tier 4's leak watch, which only debug builds have, with the lines it reported. */
    data class LeakWatch(
        val lines: List<String> = emptyList(),
        val running: Boolean = false,
        val error: String? = null,
    ) : Screen
}

/** The router's state as the router screen shows it; null where the modem didn't answer. */
class RouterStatus(
    val imeis: ImeiPair,
    val factory: ImeiPair,
    val airplaneMode: Boolean,
    val radio: Int?,
    val activeSlot: String?,
    val slot2: Slot2?,
    /** The card answering in slot 2, by its ICCID's digits. */
    val card: String?,
    /** Whether a card without an enabled profile answers in slot 2 instead. */
    val noProfile: Boolean,
    /** The card the record names, by its ICCID's digits. */
    val recorded: String?,
    val scheduledReboot: Boolean,
    val vpnSetUp: Boolean,
    val enhancedKillSwitch: Boolean,
    val firewall: Boolean,
    /** Whether GL's connection check is off for the SIM, as Churn's firewall rules need it. */
    val connectionCheckOff: Boolean,
    val flashLogOff: Boolean,
)

/**
 * The router checks' results as the router screen shows them: a line for each check that has run, out of [total], and
 * the lines of those that failed, [failures].
 */
class CheckReport(val lines: List<String>, val failures: List<String>, val total: Int) {
    val failed get() = failures.isNotEmpty()
    val done get() = lines.size == total
}

private class NoRouterNetwork : Exception()

private class NoNetworkPermission : Exception()

private class MissingPassword : Exception()

/**
 * The app's state, and everything that talks to the router. Router work runs on one background thread, one
 * job at a time; screens change on the main thread.
 */
class Controller(private val context: Context) {

    private val saved = Saved(context)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Used on the worker thread only.
    private var connection: RouterConnection? = null

    /** The password typed for the connection attempt under way, until the attempt succeeds or fails. */
    private var typedPassword: String? = null

    /** Holds the user's answer to step 3's question until the step, on the worker thread, takes it. */
    private val answers = LinkedBlockingQueue<Answer>(1)

    // Used on the main thread only.
    private var cableWatch: CableWatch? = null

    /** The router's network on the cable, while the app watches for it; null if it isn't there. */
    private val cable get() = cableWatch?.network

    /** The router's network when the last job or step started. The app starts one by itself only once on each. */
    private var started: Network? = null

    var screen: Screen = start()
        private set

    /** Called on the main thread with every new screen. */
    var onScreen: ((Screen) -> Unit)? = null

    /**
     * Whether a swap step, or another job that takes minutes, is running, with [SwapService] keeping the app alive.
     * Main thread only.
     */
    var stepRunning = false
        private set

    /** Called on the main thread when that step or job ends. */
    var onStepEnd: (() -> Unit)? = null

    /** Whether the router is on the cable, as far as the app watches for it. Main thread only. */
    val routerOnCable get() = cable != null

    /** Called on the main thread when the router shows up on the cable or leaves it. */
    var onCable: ((Boolean) -> Unit)? = null

    val address get() = saved.address
    val hasPassword get() = saved.sealedPassword != null

    /** Whether step 1 says that a first swap can run at home or at work. */
    val firstSwap get() = saved.firstSwap

    /** Whether the user confirmed in step 1 that "Power On with Charger" is off, for this swap. Main thread only. */
    var chargerChecked = false

    /** What step 4 says: whether the new card is a place card, and the last digits of the card to destroy. */
    val placeCardIn get() = saved.placeCardIn
    val cardToDestroy get() = saved.cardToDestroy

    /** The debug log, which the app keeps while [debugLogOn]. */
    val debugLog = DebugLog(File(context.filesDir, DebugLog.FILE_NAME), on = { saved.debugLog })

    var debugLogOn: Boolean
        get() = saved.debugLog
        set(value) {
            saved.debugLog = value
        }

    /**
     * Watches for the router on the cable until [stopWatching], while the app is on screen. When the router shows up,
     * the Connect screen connects to a router it knows, and step 2 goes on to step 3. Neither can happen while the
     * phone is locked: GrapheneOS lets no new USB connection through then, and Android lets an app in the background
     * start no foreground service, which a step needs.
     */
    fun startWatching() {
        if (cableWatch == null) cableWatch = CableWatch.start(context, ::cableChanged)
    }

    fun stopWatching() {
        cableWatch?.close()
        cableWatch = null
    }

    private fun cableChanged(network: Network?) {
        onCable?.invoke(network != null)
        if (network == null || network == started) return
        val current = screen
        when {
            // Only to a router the app knows: the first connection takes the password and the user's trust in the key.
            current is Screen.Connect && hasPassword && saved.hostKey != null ->
                job(R.string.connecting) {
                    disconnect()
                    showRouter(waitSeconds = STARTING_WAIT_SECONDS)
                }
            current is Screen.Swapping && current.step == Step.SWAP_CARD && !current.running ->
                advance(Step.SWAP_CARD)
            current is Screen.PowerCycles && current.waiting && !current.running -> resumePowerCycles()
        }
    }

    fun acceptIntro() {
        saved.introAccepted = true
        show(start())
    }

    fun connect(address: String, password: String) {
        when {
            !address.matches(IPV4) -> show(Screen.Connect(context.getString(R.string.error_address)))
            password.isEmpty() && !hasPassword -> show(Screen.Connect(context.getString(R.string.error_no_password)))
            else -> {
                saved.address = address
                job(R.string.connecting) {
                    disconnect()
                    if (password.isNotEmpty()) typedPassword = password
                    showRouter()
                }
            }
        }
    }

    fun trust(key: String) {
        saved.hostKey = key
        job(R.string.connecting) { showRouter() }
    }

    /** Cancel on the key or setup screen: ends the attempt at the key question, or the connection behind setup. */
    fun cancel() {
        worker.execute {
            typedPassword = null
            disconnect()
        }
        show(Screen.Connect())
    }

    fun setUp(factory: ImeiPair) = job(R.string.setting_up) {
        val router = router()
        setUpFolder(router, factory, keepIccid = router.currentCardIccid())
        showRouter()
    }

    /**
     * Sets [router] up for Churn with the [factory] IMEIs: Churn's files, kept across firmware upgrades, and the SIM
     * history cleared but for the card with [keepIccid], as [Router.clearHistory] takes it.
     */
    private fun setUpFolder(router: Router, factory: ImeiPair, keepIccid: String?) {
        val folder = router.readChurnFolder()
        updateChurnFiles(router)
        router.keepChurnFolder()
        // Written last: a folder with the factory IMEIs counts as set up.
        if (folder.factory == null) {
            router.writeChurnFile("factory", "${factory.imei1}\n${factory.imei2}\n".toByteArray())
        }
        // Turns the flash log off and clears the SIM history that built up before Churn. The first overwrite of the
        // free space takes minutes, so it waits for the first swap's step 3, and setup takes seconds.
        clearingEsimLog()
        router.clearHistory(keepIccid, overwrite = false)
    }

    fun refresh() = job(R.string.reading) { showRouter() }

    fun restore() = job(R.string.restoring) {
        val router = router()
        // Read first: the script deletes Churn's folder, so the setup screen comes next.
        val factory = router.readChurnFolder().factory
        router.restoreFactoryImeis()
        factory?.let { saved.wrote(it, factory = it) }
        showRouter(context.getString(R.string.note_restored))
    }

    fun turnKillSwitchOn() = job(R.string.turning_kill_switch_on) {
        router().turnEnhancedKillSwitchOn()
        showRouter()
    }

    fun installFirewall() = job(R.string.installing_firewall) {
        router().installFirewall()
        showRouter()
    }

    fun turnConnectionCheckOff() = job(R.string.turning_connection_check_off) {
        router().turnSimConnectionCheckOff()
        showRouter()
    }

    fun clearHistory() = job(R.string.clearing_history, keepAlive = true) {
        val router = router()
        clearingEsimLog()
        router.clearHistory(router.currentCardIccid())
        showRouter()
    }

    fun forget() {
        saved.hostKey = null
        saved.sealedPassword = null
        worker.execute { disconnect() }
        show(Screen.Connect())
    }

    fun namePlaceCard(iccid: String, name: String) = changePlaceCards { saved.placeCards.name(iccid, name) }

    fun forgetPlaceCard(iccid: String) = changePlaceCards { saved.placeCards.forget(iccid) }

    /** Changes the place list, which stays on the phone, and shows the router screen with it. */
    private fun changePlaceCards(change: () -> Unit) {
        change()
        showChanged { (it as? Screen.Home)?.copy(places = saved.placeCards.all) }
    }

    /** Starts a swap from the router screen, whose [status] tells whether the router still has its factory IMEIs. */
    fun beginSwap(status: RouterStatus) {
        saved.firstSwap = status.imeis == status.factory
        chargerChecked = false
        debugLog.beginSwap()
        show(Screen.Swapping(Step.PREPARE))
    }

    fun cancelSwap() {
        saved.endSwap()
        refresh()
    }

    /** Runs the app's part of [step]: step 1, step 3 once the router is on again, or the end of step 4. */
    fun advance(step: Step) {
        when (step) {
            Step.PREPARE -> runStep(Step.PREPARE, CONNECT_WAIT_SECONDS) { swap, folder ->
                // Step 3 replaces the record, and may run more than once.
                saved.cardBeforeSwap = folder.card?.iccid
                swap.prepare()
                shutDown(swap, next = Step.SWAP_CARD)
            }
            Step.SWAP_CARD, Step.NEW_IMEIS -> runStep(Step.NEW_IMEIS, BOOT_WAIT_SECONDS) { swap, folder ->
                // A router still on since the last shutdown, such as one still shutting down after step 1, has had no
                // card swapped.
                if (!swap.restartedSince(saved.bootId)) {
                    saved.step = Step.SWAP_CARD
                    val error = context.getString(R.string.error_not_restarted)
                    return@runStep Screen.Swapping(Step.SWAP_CARD, error = error)
                }
                val factory = folder.factory!!
                val card = swap.giveNewImeis(factory, folder.card, saved.placeCards, SecureRandom())
                if (card == null) {
                    // Canceled at the question, with slot 2 back on the eSIM: the user puts in another card.
                    shutDown(swap, next = Step.SWAP_CARD)
                } else {
                    saved.wrote(card.imeis, factory = factory)
                    saved.placeCardIn = saved.placeCards.find(card.iccid) != null
                    val destroy = saved.placeCards.toDestroy(before = saved.cardBeforeSwap, after = card.iccid)
                    saved.cardToDestroy = destroy?.takeLast(4)
                    saved.cardBeforeSwap = null
                    shutDown(swap, next = Step.GO_ONLINE)
                }
            }
            Step.GO_ONLINE -> {
                saved.endSwap()
                show(Screen.Connect())
            }
        }
    }

    /** Shows the dry run's screen, from the router screen. */
    fun openDryRun() = show(Screen.DryRun())

    /** Runs tier 2's dry run (router-tests.md, "Dry run"), which only debug builds have. */
    fun startDryRun() = runDryRun { dryRun, folder ->
        dryRun.run(folder.factory!!, SecureRandom()) { saved.dryRunStart = it }
    }

    /** Puts the router back as it was before the dry run that stopped. */
    fun putBack() = runDryRun { dryRun, _ ->
        saved.dryRunStart?.let { start -> dryRun.putBack(start) { saved.dryRunStart = it } }
    }

    /** Forgets the dry run that stopped, for a router the user has put back as it was. */
    fun forgetDryRun() {
        saved.dryRunStart = null
        refresh()
    }

    /** Shows the removal test's screen, from the router screen. */
    fun openRemovalTest() = show(Screen.RemovalTest())

    /** Runs tier 2's removal test (router-tests.md, "Removal and clearing"), which only debug builds have. */
    fun startRemovalTest() = runRemovalTest { test -> test.run { saved.removalStart = it } }

    /** Sets the router up again as it was before the removal test that stopped. */
    fun setUpAgain() = runRemovalTest { test ->
        saved.removalStart?.let { start -> test.setUpAgain(start) { saved.removalStart = it } }
    }

    /** Forgets the removal test that stopped, for a router the user has set up again. */
    fun forgetRemovalTest() {
        saved.removalStart = null
        refresh()
    }

    /** Shows the power cycle test's screen, from the router screen. */
    fun openPowerCycles() = show(Screen.PowerCycles())

    /**
     * Runs tier 3's power cycle test (router-tests.md, "Power cycles"), which only debug builds have, up to its first
     * power-off.
     */
    fun startPowerCycles() = runPowerCycles(CONNECT_WAIT_SECONDS) { test, folder ->
        test.run(folder.factory!!, SecureRandom()) { saved.powerCycleStart = it }
    }

    /** Goes on with the power cycle test once the user has switched the router on again, as at step 3. */
    fun resumePowerCycles() = runPowerCycles(BOOT_WAIT_SECONDS, keepLines = true) { test, _ ->
        saved.powerCycleStart?.let { start -> test.resume(start) { saved.powerCycleStart = it } }
    }

    /** Puts the router back as it was before the power cycle test that stopped, or instead of going on with it. */
    fun putBackPowerCycles() = runPowerCycles(BOOT_WAIT_SECONDS) { test, _ ->
        saved.powerCycleStart?.let { start -> test.putBack(start) { saved.powerCycleStart = it } }
    }

    /** Forgets the power cycle test that stopped, for a router the user has put back as it was. */
    fun forgetPowerCycles() {
        saved.powerCycleStart = null
        refresh()
    }

    /** Shows the leak watch's screen, from the router screen. */
    fun openLeakWatch() = show(Screen.LeakWatch())

    /**
     * Runs tier 4's leak watch (router-tests.md, "Leak watch"), which only debug builds have, with the card that step 3
     * last gave its IMEIs.
     */
    fun startLeakWatch() {
        started = cable
        val lines = mutableListOf<String>()
        fun update() = show(Screen.LeakWatch(lines.toList(), running = true))
        fun add(line: String) {
            lines += line
            debugLog.add("✓ $line")
            update()
        }
        update()
        stepRunning = true
        context.startForegroundService(Intent(context, SwapService::class.java))
        worker.execute {
            debugLog.start(context.getString(R.string.leak_watch))
            var test: LeakWatch? = null
            val next = try {
                val router = router()
                val folder = router.readChurnFolder()
                if (!folder.complete) throw RouterException(Problem.NOT_SET_UP)
                test = LeakWatch(
                    router,
                    report = { add(text(it)) },
                    measured = { progress, numbers -> add(text(progress, *numbers.toTypedArray())) },
                    check = { runChecks(router, folder, CHECKS.filter { it.blocks }).failures },
                )
                test.run(folder.card)
                Screen.LeakWatch(lines.toList())
            } catch (e: UntrustedHostKey) {
                trustKey(e)
            } catch (e: Exception) {
                val error = stopped(e)
                // The claims of the part that stopped, if it confirms any.
                val ids = test?.testing.orEmpty().joinToString(", ")
                Screen.LeakWatch(
                    lines.toList(),
                    error = if (ids.isEmpty()) error else context.getString(R.string.leak_watch_stopped, ids, error),
                )
            }
            main.post {
                stepRunning = false
                onStepEnd?.invoke()
            }
            show(next)
        }
    }

    /** The user's answer to step 3's question on the screen. */
    fun answer(answer: Answer) {
        answers.offer(answer)
    }

    /**
     * Ends step 1 or 3: saves [next] as the step reached, shuts the router down and waits for it to leave the cable.
     * Returns [next]'s screen, which warns if the router is still on the cable.
     */
    private fun shutDown(swap: Swap, next: Step): Screen.Swapping {
        saved.bootId = swap.bootId()
        // Saved before the router goes down, which may end the connection before it answers.
        saved.step = next
        debugLog.stopWatchingEsim()
        swap.shutDown()
        val left = connection?.awaitCableLost(SHUTDOWN_WAIT_SECONDS * 1_000L) != false
        disconnect()
        return Screen.Swapping(next, error = if (left) null else context.getString(R.string.warning_still_on_cable))
    }

    private fun start(): Screen {
        val step = saved.step
        return when {
            !saved.introAccepted -> Screen.Intro
            step != null -> Screen.Swapping(step)
            else -> Screen.Connect()
        }
    }

    private fun show(next: Screen) {
        main.post {
            screen = next
            onScreen?.invoke(next)
        }
    }

    /** Shows what [change] makes of the screen as it is by then, unless it makes nothing of it. */
    private fun showChanged(change: (Screen) -> Screen?) {
        main.post {
            change(screen)?.let {
                screen = it
                onScreen?.invoke(it)
            }
        }
    }

    /**
     * Runs [body] on the worker thread while the screen shows [title]. With [keepAlive], for a job that takes minutes,
     * the foreground service keeps the app alive meanwhile.
     */
    private fun job(title: Int, keepAlive: Boolean = false, body: () -> Unit) {
        started = cable
        show(Screen.Working(context.getString(title)))
        if (keepAlive) {
            stepRunning = true
            context.startForegroundService(Intent(context, SwapService::class.java))
        }
        worker.execute {
            debugLog.start(context.getString(title))
            try {
                body()
            } catch (e: UntrustedHostKey) {
                // The connection attempt goes on once the user trusts the key.
                show(trustKey(e))
            } catch (e: Exception) {
                typedPassword = null
                show(Screen.Connect(stopped(e)))
                // Every way on from the Connect screen connects anew.
                disconnect()
            }
            if (keepAlive) {
                main.post {
                    stepRunning = false
                    onStepEnd?.invoke()
                }
            }
        }
    }

    /** Runs a swap step on the worker thread, with the foreground service keeping the app alive meanwhile. */
    private fun runStep(step: Step, waitSeconds: Int, body: (Swap, ChurnFolder) -> Screen.Swapping) {
        started = cable
        saved.step = step
        val log = mutableListOf<String>()
        fun update(question: Question? = null) =
            show(Screen.Swapping(step, log.toList(), running = true, question = question))
        fun report(line: String) {
            log += line
            debugLog.add("✓ $line")
            update()
        }
        update()
        stepRunning = true
        context.startForegroundService(Intent(context, SwapService::class.java))
        worker.execute {
            debugLog.start(context.getString(R.string.step_number, step.ordinal + 1))
            val next = try {
                if (openConnection() == null) report(context.getString(R.string.waiting_for_router))
                val router = router(waitSeconds)
                debugLog.watchEsim(router)
                val folder = router.readChurnFolder()
                if (!folder.complete) throw RouterException(Problem.NOT_SET_UP)
                val swap = Swap(
                    router,
                    report = {
                        // The clearing empties GL's eSIM manager's log.
                        if (it == Progress.CLEARING_HISTORY) {
                            debugLog.copyEsim()
                            clearingEsimLog()
                        }
                        report(text(it))
                    },
                    ask = { question ->
                        debugLog.add("? ${question.javaClass.simpleName}")
                        // Drops an answer left from an earlier question, such as a second tap.
                        answers.clear()
                        update(question)
                        answers.take().also {
                            debugLog.add("  $it")
                            update()
                        }
                    },
                    check = { runChecks(router, folder, CHECKS.filter { it.blocks }).failures },
                )
                // The next step's screen keeps the step's lines, so that the user can still read them.
                body(swap, folder).copy(log = log.toList(), logStep = step)
            } catch (e: UntrustedHostKey) {
                trustKey(e)
            } catch (e: Exception) {
                Screen.Swapping(step, log.toList(), error = stopped(e))
            } finally {
                // The last copy, unless the step made it before shutting the router down.
                debugLog.stopWatchingEsim()
            }
            // Before the next screen, which may start the next step.
            main.post {
                stepRunning = false
                onStepEnd?.invoke()
            }
            show(next)
        }
    }

    /**
     * Runs [body], the dry run or its putting back, on the worker thread, with the foreground service keeping the app
     * alive meanwhile.
     */
    private fun runDryRun(body: (DryRun, ChurnFolder) -> Unit) {
        started = cable
        val lines = mutableListOf<String>()
        fun update() = show(Screen.DryRun(lines.toList(), running = true))
        update()
        stepRunning = true
        context.startForegroundService(Intent(context, SwapService::class.java))
        worker.execute {
            debugLog.start(context.getString(R.string.dry_run))
            var dryRun: DryRun? = null
            val next = try {
                val router = router()
                val folder = router.readChurnFolder()
                if (!folder.complete) throw RouterException(Problem.NOT_SET_UP)
                dryRun = DryRun(
                    router,
                    report = {
                        val line = text(it)
                        lines += line
                        debugLog.add("✓ $line")
                        update()
                    },
                    check = { runChecks(router, folder, CHECKS.filter { it.blocks }).failures },
                )
                body(dryRun, folder)
                Screen.DryRun(lines.toList(), unfinished = saved.dryRunStart != null)
            } catch (e: UntrustedHostKey) {
                trustKey(e)
            } catch (e: Exception) {
                val error = stopped(e)
                // The claims of the part that stopped, if it confirms any.
                val ids = dryRun?.testing.orEmpty().joinToString(", ")
                Screen.DryRun(
                    lines.toList(),
                    error = if (ids.isEmpty()) error else context.getString(R.string.dry_run_stopped, ids, error),
                    unfinished = saved.dryRunStart != null,
                )
            }
            main.post {
                stepRunning = false
                onStepEnd?.invoke()
            }
            show(next)
        }
    }

    /**
     * Runs [body], the removal test or its setting the router up again, as [runDryRun] runs the dry run. The test
     * reads Churn's folder itself, since setting the router up again needs none.
     */
    private fun runRemovalTest(body: (RemovalTest) -> Unit) {
        started = cable
        val lines = mutableListOf<String>()
        fun update() = show(Screen.RemovalTest(lines.toList(), running = true))
        update()
        stepRunning = true
        context.startForegroundService(Intent(context, SwapService::class.java))
        worker.execute {
            debugLog.start(context.getString(R.string.removal_test))
            var test: RemovalTest? = null
            val next = try {
                val router = router()
                val folder = router.readChurnFolder()
                test = RemovalTest(
                    router,
                    report = {
                        // The clearing empties GL's eSIM manager's log.
                        if (it == Progress.CLEARING_HISTORY) clearingEsimLog()
                        val line = text(it)
                        lines += line
                        debugLog.add("✓ $line")
                        update()
                    },
                    check = { runChecks(router, folder, CHECKS.filter { it.blocks }).failures },
                    setUpFolder = { factory, keepIccid -> setUpFolder(router, factory, keepIccid) },
                )
                body(test)
                Screen.RemovalTest(lines.toList(), unfinished = saved.removalStart != null)
            } catch (e: UntrustedHostKey) {
                trustKey(e)
            } catch (e: Exception) {
                val error = stopped(e)
                // The claims of the part that stopped, if it confirms any.
                val ids = test?.testing.orEmpty().joinToString(", ")
                Screen.RemovalTest(
                    lines.toList(),
                    error = if (ids.isEmpty()) error else context.getString(R.string.removal_test_stopped, ids, error),
                    unfinished = saved.removalStart != null,
                )
            }
            main.post {
                stepRunning = false
                onStepEnd?.invoke()
            }
            show(next)
        }
    }

    /**
     * Runs [body], a part of the power cycle test or its putting back, as [runDryRun] runs the dry run, waiting up to
     * [waitSeconds] for the router. With [keepLines], the screen keeps the lines from before the last power-off.
     */
    private fun runPowerCycles(
        waitSeconds: Int,
        keepLines: Boolean = false,
        body: (PowerCycleTest, ChurnFolder) -> Unit,
    ) {
        started = cable
        val lines = if (keepLines) (screen as? Screen.PowerCycles)?.lines.orEmpty().toMutableList() else mutableListOf()
        fun update() = show(Screen.PowerCycles(lines.toList(), running = true))
        fun add(line: String) {
            lines += line
            debugLog.add("✓ $line")
            update()
        }
        update()
        stepRunning = true
        context.startForegroundService(Intent(context, SwapService::class.java))
        worker.execute {
            debugLog.start(context.getString(R.string.power_cycles))
            var test: PowerCycleTest? = null
            val next = try {
                // As at step 3, while the router starts after a power-on.
                if (openConnection() == null) add(context.getString(R.string.waiting_for_router))
                val router = router(waitSeconds)
                val folder = router.readChurnFolder()
                if (!folder.complete) throw RouterException(Problem.NOT_SET_UP)
                test = PowerCycleTest(
                    router,
                    report = { add(text(it)) },
                    measured = { progress, seconds -> add(text(progress, seconds)) },
                    check = { runChecks(router, folder, CHECKS.filter { it.blocks }).failures },
                    awaitNetworkLost = { millis -> connection?.awaitCableLost(millis) != false },
                )
                body(test, folder)
                powerCycleScreen(lines)
            } catch (e: UntrustedHostKey) {
                trustKey(e)
            } catch (e: Exception) {
                val error = stopped(e)
                // The claims of the part that stopped, if it confirms any.
                val ids = test?.testing.orEmpty().joinToString(", ")
                powerCycleScreen(
                    lines,
                    error = if (ids.isEmpty()) error else context.getString(R.string.power_cycles_stopped, ids, error),
                )
            }
            // The router is off for the next power-on, after which the test connects anew.
            if ((saved.powerCycleStart?.powerOn ?: 0) > 0) disconnect()
            main.post {
                stepRunning = false
                onStepEnd?.invoke()
            }
            show(next)
        }
    }

    /** The power cycle test's screen with [lines], as the test left the router, and with [error] if it stopped. */
    private fun powerCycleScreen(lines: List<String> = emptyList(), error: String? = null): Screen.PowerCycles {
        val start = saved.powerCycleStart
        return Screen.PowerCycles(
            lines.toList(),
            error = error,
            waiting = start != null && start.powerOn > 0,
            unfinished = start?.powerOn == 0,
        )
    }

    /** The router, connecting first if needed, and waiting up to [waitSeconds] for it to come up. */
    private fun router(waitSeconds: Int = CONNECT_WAIT_SECONDS): Router {
        openConnection()?.let { return Router(debugLog.record(it)) }
        if (!RouterNetwork.socketsAllowed()) throw NoNetworkPermission()
        val password = typedPassword ?: saved.sealedPassword?.let { PasswordVault.open(it) } ?: throw MissingPassword()
        val deadline = System.nanoTime() + waitSeconds * 1_000_000_000L
        while (true) {
            val left = ((deadline - System.nanoTime()) / 1_000_000).toInt()
            val usb = RouterNetwork.connect(context, maxOf(left, 1_000)) ?: throw NoRouterNetwork()
            debugLog.add("The router's network is there.")
            try {
                val opened = RouterConnection.open(usb, saved.address, password, saved.hostKey)
                connection = opened
                debugLog.add("Connected to the router.")
                if (typedPassword != null) {
                    saved.sealedPassword = PasswordVault.seal(password)
                    typedPassword = null
                }
                return Router(debugLog.record(opened))
            } catch (e: IOException) {
                // While the router starts, its network can show up before it accepts connections.
                debugLog.add("  $e")
                if (System.nanoTime() >= deadline) throw e
                Thread.sleep(RETRY_MILLISECONDS)
            }
        }
    }

    /**
     * The connection from an earlier job, unless it ended since. Android ends the connections of an app it freezes in
     * the background, as when the phone locks while a screen waits for the user.
     */
    private fun openConnection(): RouterConnection? {
        if (connection?.isOpen == false) disconnect()
        return connection
    }

    private fun disconnect() {
        connection?.close()
        connection = null
    }

    /**
     * Shows the router screen, or the setup screen if Churn's folder isn't complete, connecting first if needed and
     * waiting up to [waitSeconds] for the router.
     */
    private fun showRouter(note: String? = null, waitSeconds: Int = CONNECT_WAIT_SECONDS) {
        val router = router(waitSeconds)
        val folder = router.readChurnFolder()
        // A router set up by an earlier version of the app keeps that version's restore script, which may not undo
        // everything this version changes.
        if (folder.complete) updateChurnFiles(router)
        val step = saved.step
        val next = when {
            // Until the router is set up again after the removal test, which only debug builds have and which deletes
            // Churn's folder.
            saved.removalStart != null -> Screen.RemovalTest(unfinished = true)
            !folder.complete -> setUpScreen(router, note)
            step != null -> Screen.Swapping(step)
            // Until the router is as it was before the dry run, which only debug builds have.
            saved.dryRunStart != null -> Screen.DryRun(unfinished = true)
            // Until the power cycle test, which only debug builds have, has put the router back.
            saved.powerCycleStart != null -> powerCycleScreen()
            else -> home(router, folder, note)
        }
        show(next)
        // Only once the router screen shows, so that connecting takes no longer.
        if (next is Screen.Home) {
            debugLog.start(context.getString(R.string.checking_router))
            runChecks(router, folder, CHECKS) { report ->
                // Unless the user has left the router screen, or it shows a newer read of the router.
                showChanged { screen ->
                    (screen as? Screen.Home)?.takeIf { it.status === next.status }?.copy(checks = report)
                }
            }
        }
    }

    /**
     * Runs [checks] on [router], whose Churn folder is [folder], and returns their results. [progress] gets the
     * results so far after each check. The debug log gets each result after the commands of its check.
     */
    private fun runChecks(
        router: Router,
        folder: ChurnFolder,
        checks: List<Check>,
        progress: (CheckReport) -> Unit = {},
    ): CheckReport {
        var report = CheckReport(emptyList(), emptyList(), checks.size)
        for (check in checks) {
            val result = check.run(router, CheckContext(folder, saved.checkNotes))
            val line = line(check, result)
            debugLog.add(line)
            val failures = if (result is CheckResult.Failed) report.failures + line else report.failures
            report = CheckReport(report.lines + line, failures, checks.size)
            progress(report)
        }
        return report
    }

    /** Forgets the note of GL's eSIM manager's log, as a clearing of the SIM history empties the log. */
    private fun clearingEsimLog() {
        saved.checkNotes[ESIM_LOG_NOTE] = null
    }

    /** [check]'s [result], as the router screen and the debug log show it. */
    private fun line(check: Check, result: CheckResult): String {
        val title = context.getString(check.title)
        val ids = check.ids.joinToString(", ")
        return when (result) {
            CheckResult.Passed -> context.getString(R.string.check_passed, title, ids)
            is CheckResult.Failed -> context.getString(R.string.check_failed, title, ids, result.detail)
            is CheckResult.NotApplicable ->
                context.getString(R.string.check_not_applicable, title, ids, context.getString(result.reason))
        }
    }

    /** The setup screen, for a router without Churn's folder, such as a new or a restored one. */
    private fun setUpScreen(router: Router, note: String?): Screen.SetUp {
        val modem = router.imeis()
        val written = saved.written
        val factory = ChurnFolder.factoryAtSetup(modem, written = written, copy = saved.factory)
        val warnings = mutableListOf<Int>()
        if (factory != modem) {
            warnings += R.string.setup_warning_copy
        } else if (modem == written && saved.factory == null) {
            // Written by an earlier version of the app, which kept no copy.
            warnings += R.string.setup_warning_written
        }
        if (listOf(factory.imei1, factory.imei2).any { Imei.tac(it) != Imei.MUDI7_TAC }) {
            warnings += R.string.setup_warning_tac
        }
        if (!Imei.isValid(factory.imei1) || !Imei.isValid(factory.imei2)) {
            warnings += R.string.setup_warning_check_digit
        }
        return Screen.SetUp(factory, warnings.map { context.getString(it) }, note)
    }

    private fun home(router: Router, folder: ChurnFolder, note: String?): Screen.Home {
        val factory = folder.factory!!
        val imeis = router.imeis()
        val notes = listOfNotNull(note).toMutableList()
        val slot2 = runCatching { router.slot2() }.getOrNull()
        // The card in slot 2 registers with the IMEIs at every power-on, so they must be the pair step 3 recorded for
        // it. A card in the tray while slot 2 is on the built-in eSIM stays off, and reading it would take a switch of
        // the slot. A profile enabled on the eSIM gets the SIM line's warning instead (rule 6), and a card without an
        // enabled profile has no pair to check.
        val slot2Card = router.slot2Card()
        val card = (slot2Card as? Slot2Card.Subscription)?.iccid?.let(Imei::iccidDigits)
        if (card != null && slot2 != Slot2.ESIM && !folder.isRecorded(card, imeis)) {
            notes += context.getString(R.string.note_not_cards_imeis)
        }
        val scheduledReboot = router.scheduledReboot()
        if (scheduledReboot) notes += context.getString(R.string.note_scheduled_reboot)
        val vpnSetUp = router.vpnSetUp()
        if (!vpnSetUp) notes += context.getString(R.string.note_no_vpn)
        val status = RouterStatus(
            imeis = imeis,
            factory = factory,
            airplaneMode = router.airplaneMode(),
            radio = runCatching { router.radio() }.getOrNull(),
            activeSlot = router.activeSlot(),
            slot2 = slot2,
            card = card,
            noProfile = slot2Card == Slot2Card.NoProfile,
            recorded = folder.card?.iccid,
            scheduledReboot = scheduledReboot,
            vpnSetUp = vpnSetUp,
            enhancedKillSwitch = router.enhancedKillSwitch(),
            // A router whose firewall the app can't read shows the warning, and adding the rules says why.
            firewall = runCatching { router.firewallInPlace() }.getOrDefault(false),
            // The same for GL's connection check.
            connectionCheckOff = runCatching { router.simConnectionCheckOff() }.getOrDefault(false),
            flashLogOff = router.flashLogOff(),
        )
        return Screen.Home(status, notes, saved.placeCards.all)
    }

    /** The README and restore script in Churn's folder, as this version of the app ships them. */
    private fun updateChurnFiles(router: Router) {
        router.updateChurnFile("README.txt", asset("README.txt"))
        router.updateChurnFile("restore.sh", asset("restore.sh"), executable = true)
    }

    private fun asset(name: String) = context.assets.open(name).use { it.readBytes() }

    /** The screen that asks the user whether to trust the router's key in [e]. */
    private fun trustKey(e: UntrustedHostKey): Screen.TrustKey {
        debugLog.error(context.getString(if (e.changed) R.string.key_changed_title else R.string.key_new_title), e)
        return Screen.TrustKey(e.key, e.fingerprint, e.changed)
    }

    /** What the screen says about [e], which the debug log keeps too. */
    private fun stopped(e: Exception) = describe(e).also { debugLog.error(it, e) }

    private fun describe(e: Exception): String {
        // Whatever failed, it failed because the router left the cable.
        if (connection?.cableLost == true) {
            disconnect()
            return context.getString(R.string.error_cable_lost)
        }
        if (e is IOException) disconnect()
        return when (e) {
            is RouterException -> text(e.problem) + (e.detail?.let { "\n\n$it" } ?: "")
            is NoRouterNetwork -> context.getString(R.string.error_no_network)
            is NoNetworkPermission -> context.getString(R.string.error_network_permission)
            is MissingPassword -> context.getString(R.string.error_no_password)
            is WrongPassword -> context.getString(R.string.error_password)
            is ConnectException, is NoRouteToHostException, is SocketTimeoutException ->
                context.getString(R.string.error_unreachable, saved.address)
            is IOException -> context.getString(R.string.error_connection, e.message ?: e.javaClass.simpleName)
            else -> context.getString(R.string.error_other, e.toString())
        }
    }

    /** [progress]'s line, with the [args] it names, such as a number of seconds. */
    private fun text(progress: Progress, vararg args: Any) = context.getString(
        when (progress) {
            Progress.UNTESTED_PLATFORM -> R.string.progress_untested_platform
            Progress.AIRPLANE_MODE_ON -> R.string.progress_airplane_mode_on
            Progress.RADIO_OFF -> R.string.progress_radio_off
            Progress.SLOT2_ON_ESIM -> R.string.progress_slot2_on_esim
            Progress.NO_PROFILE -> R.string.progress_no_profile
            Progress.DISABLING_AGAIN -> R.string.progress_disabling_again
            Progress.PROFILE_DISABLED -> R.string.progress_profile_disabled
            Progress.WAITING_FOR_START -> R.string.progress_waiting_for_start
            Progress.SIM1_EMPTY -> R.string.progress_sim1_empty
            Progress.KILL_SWITCH_ON -> R.string.progress_kill_switch_on
            Progress.FIREWALL -> R.string.progress_firewall
            Progress.SLOT2_ON_SIM2 -> R.string.progress_slot2_on_sim2
            Progress.SLOT2_ACTIVE -> R.string.progress_slot2_active
            Progress.CARD_FOUND -> R.string.progress_card_found
            Progress.SLOT2_BACK_ON_SIM2 -> R.string.progress_slot2_back_on_sim2
            Progress.SAME_CARD -> R.string.progress_same_card
            Progress.NEW_IMEIS -> R.string.progress_new_imeis
            Progress.CLEARING_HISTORY -> R.string.progress_clearing_history
            Progress.SHUTTING_DOWN -> R.string.progress_shutting_down
            Progress.NETWORK_RELOADED -> R.string.progress_network_reloaded
            Progress.FIREWALL_RELOADED -> R.string.progress_firewall_reloaded
            Progress.SLOT1_ACTIVE -> R.string.progress_slot1_active
            Progress.TRAY_EMPTY -> R.string.progress_tray_empty
            Progress.ESIM_SILENT -> R.string.progress_esim_silent
            Progress.TEST_IMEIS -> R.string.progress_test_imeis
            Progress.EARLIER_IMEIS -> R.string.progress_earlier_imeis
            Progress.ANSWERS_MATCHED -> R.string.progress_answers_matched
            Progress.RESTORED -> R.string.progress_restored
            Progress.FACTORY_IMEIS -> R.string.progress_factory_imeis
            Progress.FOLDER_GONE -> R.string.progress_folder_gone
            Progress.LOG_SETTING_BACK -> R.string.progress_log_setting_back
            Progress.LOG_WRITTEN -> R.string.progress_log_written
            Progress.ZERO_FILES_GONE -> R.string.progress_zero_files_gone
            Progress.FIREWALL_GONE -> R.string.progress_firewall_gone
            Progress.SET_UP_AGAIN -> R.string.progress_set_up_again
            Progress.CONNECTION_KEPT -> R.string.progress_connection_kept
            Progress.RESERVE_BACK -> R.string.progress_reserve_back
            Progress.CARD_FILES_PARSE -> R.string.progress_card_files_parse
            Progress.ZEROS_KEEP_SPACE -> R.string.progress_zeros_keep_space
            Progress.READING_PARTITION -> R.string.progress_reading_partition
            Progress.IMSIS_GONE -> R.string.progress_imsis_gone
            Progress.NO_IMSIS -> R.string.progress_no_imsis
            Progress.ICCIDS_GONE -> R.string.progress_iccids_gone
            Progress.NO_ICCIDS -> R.string.progress_no_iccids
            Progress.PARTITION_UNCHECKED -> R.string.progress_partition_unchecked
            Progress.FREE_SPACE_FILLED -> R.string.progress_free_space_filled
            Progress.CLEARED_QUICKLY -> R.string.progress_cleared_quickly
            Progress.REMOVAL_PASSED -> R.string.progress_removal_passed
            Progress.PUT_BACK -> R.string.progress_put_back
            Progress.DRY_RUN_PASSED -> R.string.progress_dry_run_passed
            Progress.NETWORK_LOST -> R.string.progress_network_lost
            Progress.NEW_BOOT_ID -> R.string.progress_new_boot_id
            Progress.TEST_PAIR_SAVED -> R.string.progress_test_pair_saved
            Progress.SETTINGS_KEPT -> R.string.progress_settings_kept
            Progress.RULES_AT_START -> R.string.progress_rules_at_start
            Progress.CHAINS_AT_START -> R.string.progress_chains_at_start
            Progress.MODEM_ANSWERED -> R.string.progress_modem_answered
            Progress.MODEM_AT_ONCE -> R.string.progress_modem_at_once
            Progress.RADIO_ON_UNTIL -> R.string.progress_radio_on_until
            Progress.RADIO_OFF_AT_FIRST -> R.string.progress_radio_off_at_first
            Progress.RADIO_OFF_FROM -> R.string.progress_radio_off_from
            Progress.IMEIS_KEPT -> R.string.progress_imeis_kept
            Progress.EARLIER_IMEIS_SAVED -> R.string.progress_earlier_imeis_saved
            Progress.TRAYS_EMPTY -> R.string.progress_trays_empty
            Progress.RADIO_ON_UNCOMMITTED -> R.string.progress_radio_on_uncommitted
            Progress.RADIO_OFF_UNCOMMITTED -> R.string.progress_radio_off_uncommitted
            Progress.POWER_CYCLES_PASSED -> R.string.progress_power_cycles_passed
            Progress.CARD_READY -> R.string.progress_card_ready
            Progress.GOING_ONLINE -> R.string.progress_going_online
            Progress.SIM_PACKETS -> R.string.progress_sim_packets
            Progress.FRAGMENTS_UNCLEAR -> R.string.progress_fragments_unclear
            Progress.INTERFACES_CHANGED -> R.string.progress_interfaces_changed
            Progress.LEAK_WATCH_PASSED -> R.string.progress_leak_watch_passed
            Progress.SIM_ONLY -> R.string.progress_sim_only
            Progress.TUNNEL_MADE_ANEW -> R.string.progress_tunnel_made_anew
            Progress.WIREGUARD_MATCHED -> R.string.progress_wireguard_matched
            Progress.WIREGUARD_UNCLEAR -> R.string.progress_wireguard_unclear
            Progress.HANDSHAKE -> R.string.progress_handshake
            Progress.CLOCK_SYNCED -> R.string.progress_clock_synced
            Progress.TURN_AIRPLANE_MODE_OFF -> R.string.progress_turn_airplane_mode_off
            Progress.CONNECTION_CHECK_OFF -> R.string.progress_connection_check_off
            Progress.CONNECTION_CHECK_RESTARTED -> R.string.progress_connection_check_restarted
            Progress.CONNECTION_CHECK_BACK -> R.string.progress_connection_check_back
        },
        *args,
    )

    private fun text(problem: Problem) = context.getString(
        when (problem) {
            Problem.UNEXPECTED_ANSWER -> R.string.problem_unexpected_answer
            Problem.RADIO_ON -> R.string.problem_radio_on
            Problem.SCHEDULED_REBOOT -> R.string.problem_scheduled_reboot
            Problem.IMEIS_NOT_KEPT -> R.string.problem_imeis_not_kept
            Problem.SLOT_NOT_SWITCHED -> R.string.problem_slot_not_switched
            Problem.PROFILE_STILL_ENABLED -> R.string.problem_profile_still_enabled
            Problem.NO_CARD -> R.string.problem_no_card
            Problem.SIM1_CARD -> R.string.problem_sim1_card
            Problem.CARD_CHANGED -> R.string.problem_card_changed
            Problem.NOT_SET_UP -> R.string.problem_not_set_up
            Problem.NOT_STARTED -> R.string.problem_not_started
            Problem.KILL_SWITCH_OFF -> R.string.problem_kill_switch_off
            Problem.NO_VPN -> R.string.problem_no_vpn
            Problem.VPN_GONE -> R.string.problem_vpn_gone
            Problem.FIREWALL_NOT_LOADED -> R.string.problem_firewall_not_loaded
            Problem.CHECK_FAILED -> R.string.problem_check_failed
            Problem.PROFILE_ENABLED -> R.string.problem_profile_enabled
            Problem.CARD_IN_TRAY -> R.string.problem_card_in_tray
            Problem.NOT_READY -> R.string.problem_not_ready
            Problem.LEAK -> R.string.problem_leak
            Problem.AIRPLANE_MODE_LEFT_ON -> R.string.problem_airplane_mode_left_on
            Problem.CONNECTION_CHECK_ON -> R.string.problem_connection_check_on
        },
    )

    private companion object {
        val IPV4 = Regex("""((25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(25[0-5]|2[0-4]\d|1?\d?\d)""")
        const val CONNECT_WAIT_SECONDS = 15

        /**
         * For a connection the app starts as soon as the router shows up on the cable, which may be before the router
         * accepts connections. Our guess, not a measurement.
         */
        const val STARTING_WAIT_SECONDS = 60

        /** Long enough for the router to start after the user switched it on. */
        const val BOOT_WAIT_SECONDS = 300

        /**
         * For the router to leave the cable after the shutdown command, as warning_still_on_cable says. Our guess, not a
         * measurement.
         */
        const val SHUTDOWN_WAIT_SECONDS = 120
        const val RETRY_MILLISECONDS = 3_000L
    }
}
