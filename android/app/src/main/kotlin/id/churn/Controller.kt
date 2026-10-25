package id.churn

import android.content.Context
import android.content.Intent
import android.net.Network
import android.os.Handler
import android.os.Looper
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
    data class SetUp(val factory: ImeiPair, val warnings: List<String>) : Screen
    data class Home(val status: RouterStatus, val notes: List<String>, val places: List<PlaceCard>) : Screen
    data class Swapping(
        val step: Step,
        val log: List<String> = emptyList(),
        val running: Boolean = false,
        val error: String? = null,
        /** Step 3's question, which the running step waits for the user to answer. */
        val question: Question? = null,
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
    val flashLogOff: Boolean,
)

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
    private var typedPassword: String? = null

    /** Holds the user's answer to step 3's question until the step, on the worker thread, takes it. */
    private val answers = LinkedBlockingQueue<Answer>(1)

    // Used on the main thread only.
    private val cableWatch = CableWatch(context, ::cableChanged)

    /** The router's network on the cable, while the app watches for it; null if it isn't there. */
    private var cable: Network? = null

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

    /** What step 4 says: whether the new card is a place card, and the last digits of the card to destroy. */
    val placeCardIn get() = saved.placeCardIn
    val cardToDestroy get() = saved.cardToDestroy

    /**
     * Watches for the router on the cable until [stopWatching], while the app is on screen. When the router shows up,
     * the Connect screen connects to a router it knows, and step 2 goes on to step 3. Neither can happen while the
     * phone is locked: GrapheneOS lets no new USB connection through then, and Android lets an app in the background
     * start no foreground service, which a step needs.
     */
    fun startWatching() = cableWatch.start()

    fun stopWatching() {
        cableWatch.stop()
        cable = null
    }

    private fun cableChanged(network: Network?) {
        cable = network
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

    fun cancel() = show(Screen.Connect())

    fun setUp(factory: ImeiPair) = job(R.string.setting_up) {
        val router = router()
        val folder = router.readChurnFolder()
        updateChurnFiles(router)
        router.keepChurnFolder()
        // Written last: a folder with the factory IMEIs counts as set up.
        if (folder.factory == null) {
            router.writeChurnFile("factory", "${factory.imei1}\n${factory.imei2}\n".toByteArray())
        }
        // Turns the flash log off and clears the SIM history that built up before Churn. The first overwrite of the
        // free space takes minutes, so it waits for the first swap's step 3, and setup takes seconds.
        router.clearHistory(router.currentCardIccid(), overwrite = false)
        showRouter()
    }

    fun refresh() = job(R.string.reading) { showRouter() }

    fun restore() = job(R.string.restoring) {
        val router = router()
        router.restoreFactoryImeis()
        router.readChurnFolder().factory?.let { saved.wrote(it, factory = it) }
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

    fun clearHistory() = job(R.string.clearing_history, keepAlive = true) {
        val router = router()
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
        (screen as? Screen.Home)?.let { show(it.copy(places = saved.placeCards.all)) }
    }

    fun beginSwap() = show(Screen.Swapping(Step.PREPARE))

    fun cancelSwap() {
        saved.step = null
        refresh()
    }

    /** Runs the app's part of [step]: step 1, step 3 once the router is on again, or the end of step 4. */
    fun advance(step: Step) {
        when (step) {
            Step.PREPARE -> runStep(Step.PREPARE, CONNECT_WAIT_SECONDS) { swap, folder ->
                // Step 3 replaces the record, and may run more than once.
                saved.cardBeforeSwap = folder.card?.iccid
                val factory = folder.factory!!
                saved.wrote(swap.prepare(factory, SecureRandom()), factory = factory)
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
                saved.step = null
                saved.cardToDestroy = null
                show(Screen.Connect())
            }
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
            try {
                body()
            } catch (e: UntrustedHostKey) {
                show(Screen.TrustKey(e.key, e.fingerprint, e.changed))
            } catch (e: Exception) {
                show(Screen.Connect(describe(e)))
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
        update()
        stepRunning = true
        context.startForegroundService(Intent(context, SwapService::class.java))
        worker.execute {
            val next = try {
                if (openConnection() == null) {
                    log += context.getString(R.string.waiting_for_router)
                    update()
                }
                val router = router(waitSeconds)
                val folder = router.readChurnFolder()
                if (!folder.complete) throw RouterException(Problem.NOT_SET_UP)
                val swap = Swap(
                    router,
                    report = {
                        log += text(it)
                        update()
                    },
                    ask = { question ->
                        // Drops an answer left from an earlier question, such as a second tap.
                        answers.clear()
                        update(question)
                        answers.take().also { update() }
                    },
                )
                body(swap, folder)
            } catch (e: UntrustedHostKey) {
                Screen.TrustKey(e.key, e.fingerprint, e.changed)
            } catch (e: Exception) {
                Screen.Swapping(step, log.toList(), error = describe(e))
            }
            // Before the next screen, which may start the next step.
            main.post {
                stepRunning = false
                onStepEnd?.invoke()
            }
            show(next)
        }
    }

    /** The router, connecting first if needed, and waiting up to [waitSeconds] for it to come up. */
    private fun router(waitSeconds: Int = CONNECT_WAIT_SECONDS): Router {
        openConnection()?.let { return Router(it) }
        if (!RouterNetwork.socketsAllowed()) throw NoNetworkPermission()
        val password = typedPassword ?: saved.sealedPassword?.let { PasswordVault.open(it) } ?: throw MissingPassword()
        val deadline = System.nanoTime() + waitSeconds * 1_000_000_000L
        while (true) {
            val left = ((deadline - System.nanoTime()) / 1_000_000).toInt()
            val usb = RouterNetwork.connect(context, maxOf(left, 1_000)) ?: throw NoRouterNetwork()
            try {
                val opened = RouterConnection.open(usb, saved.address, password, saved.hostKey)
                connection = opened
                if (typedPassword != null) {
                    saved.sealedPassword = PasswordVault.seal(password)
                    typedPassword = null
                }
                return Router(opened)
            } catch (e: WrongPassword) {
                typedPassword = null
                throw e
            } catch (e: IOException) {
                // While the router starts, its network can show up before it accepts connections.
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
        show(
            when {
                !folder.complete -> setUpScreen(router)
                step != null -> Screen.Swapping(step)
                else -> home(router, folder, note)
            },
        )
    }

    /** The setup screen, for a router without Churn's folder. */
    private fun setUpScreen(router: Router): Screen.SetUp {
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
        return Screen.SetUp(factory, warnings.map { context.getString(it) })
    }

    private fun home(router: Router, folder: ChurnFolder, note: String?): Screen.Home {
        val factory = folder.factory!!
        val imeis = router.imeis()
        val notes = listOfNotNull(note).toMutableList()
        val slot2 = runCatching { router.slot2() }.getOrNull()
        // The card in slot 2 registers with the IMEIs at every power-on, so they must be the pair step 3 recorded for
        // it. A card in the tray while slot 2 is on the built-in eSIM stays off, and reading it would take a switch of
        // the slot. A profile enabled on the eSIM gets the SIM line's warning instead (rule 4), and a card without an
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

    private fun text(progress: Progress) = context.getString(
        when (progress) {
            Progress.AIRPLANE_MODE_ON -> R.string.progress_airplane_mode_on
            Progress.RADIO_OFF -> R.string.progress_radio_off
            Progress.THROWAWAY_IMEIS -> R.string.progress_throwaway_imeis
            Progress.SLOT2_ON_ESIM -> R.string.progress_slot2_on_esim
            Progress.NO_PROFILE -> R.string.progress_no_profile
            Progress.PROFILE_DISABLED -> R.string.progress_profile_disabled
            Progress.WAITING_FOR_START -> R.string.progress_waiting_for_start
            Progress.KILL_SWITCH_ON -> R.string.progress_kill_switch_on
            Progress.FIREWALL -> R.string.progress_firewall
            Progress.SLOT2_ON_SIM2 -> R.string.progress_slot2_on_sim2
            Progress.SLOT2_ACTIVE -> R.string.progress_slot2_active
            Progress.CARD_FOUND -> R.string.progress_card_found
            Progress.NEW_IMEIS -> R.string.progress_new_imeis
            Progress.CLEARING_HISTORY -> R.string.progress_clearing_history
            Progress.SHUTTING_DOWN -> R.string.progress_shutting_down
        },
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
            Problem.CARD_CHANGED -> R.string.problem_card_changed
            Problem.NOT_SET_UP -> R.string.problem_not_set_up
            Problem.NOT_STARTED -> R.string.problem_not_started
            Problem.KILL_SWITCH_OFF -> R.string.problem_kill_switch_off
            Problem.NO_VPN -> R.string.problem_no_vpn
            Problem.FIREWALL_NOT_LOADED -> R.string.problem_firewall_not_loaded
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
