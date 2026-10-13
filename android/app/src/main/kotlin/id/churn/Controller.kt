package id.churn

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.Executors

/** What the screen shows. [MainActivity] draws it. */
sealed interface Screen {
    data object Intro : Screen
    data class Connect(val error: String? = null) : Screen
    data class Working(val title: String) : Screen
    data class TrustKey(val key: String, val fingerprint: String, val changed: Boolean) : Screen
    data class SetUp(val factory: ImeiPair, val warnings: List<String>) : Screen
    data class Home(val status: RouterStatus, val notes: List<String>) : Screen
    data class Swapping(
        val step: Step,
        val log: List<String> = emptyList(),
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
    private val network = RouterNetwork(context)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Used on the worker thread only.
    private var connection: RouterConnection? = null
    private var typedPassword: String? = null

    var screen: Screen = start()
        private set

    /** Called on the main thread with every new screen. */
    var onScreen: ((Screen) -> Unit)? = null

    /** Whether a swap step is running, with [SwapService] keeping the app alive. Main thread only. */
    var stepRunning = false
        private set

    /** Called on the main thread when a swap step ends. */
    var onStepEnd: (() -> Unit)? = null

    val address get() = saved.address
    val hasPassword get() = saved.sealedPassword != null

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
        router.writeChurnFile("README.txt", asset("README.txt"))
        router.writeChurnFile("restore.sh", asset("restore.sh"), executable = true)
        router.keepChurnFolder()
        if (folder.factory == null) {
            router.writeChurnFile("factory", "${factory.imei1}\n${factory.imei2}\n".toByteArray())
        }
        // Written last: a folder with the secret and the factory IMEIs counts as set up.
        if (folder.secret == null) router.writeChurnFile("secret", ByteArray(32).also { SecureRandom().nextBytes(it) })
        showRouter()
    }

    fun refresh() = job(R.string.reading) { showRouter() }

    fun restore() = job(R.string.restoring) {
        val router = router()
        router.restoreFactoryImeis()
        saved.written = router.readChurnFolder().factory
        showRouter(context.getString(R.string.note_restored))
    }

    fun forget() {
        saved.hostKey = null
        saved.sealedPassword = null
        worker.execute { disconnect() }
        show(Screen.Connect())
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
                saved.written = swap.prepare(folder.factory!!, SecureRandom())
                // Saved before the router goes down, which may end the connection before it answers.
                saved.step = Step.SWAP_CARD
                swap.shutDown()
                disconnect()
                Step.SWAP_CARD
            }
            Step.SWAP_CARD, Step.NEW_IMEIS -> runStep(Step.NEW_IMEIS, BOOT_WAIT_SECONDS) { swap, folder ->
                saved.written = swap.giveNewImeis(folder.factory!!, folder.secret!!)
                saved.step = Step.GO_ONLINE
                Step.GO_ONLINE
            }
            Step.GO_ONLINE -> {
                saved.step = null
                refresh()
            }
        }
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

    /** Runs [body] on the worker thread while the screen shows [title]. */
    private fun job(title: Int, body: () -> Unit) {
        show(Screen.Working(context.getString(title)))
        worker.execute {
            try {
                body()
            } catch (e: UntrustedHostKey) {
                show(Screen.TrustKey(e.key, e.fingerprint, e.changed))
            } catch (e: Exception) {
                show(Screen.Connect(describe(e)))
            }
        }
    }

    /** Runs a swap step on the worker thread, with the foreground service keeping the app alive meanwhile. */
    private fun runStep(step: Step, waitSeconds: Int, body: (Swap, ChurnFolder) -> Step) {
        saved.step = step
        val log = mutableListOf<String>()
        fun update() = show(Screen.Swapping(step, log.toList(), running = true))
        update()
        stepRunning = true
        context.startForegroundService(Intent(context, SwapService::class.java))
        worker.execute {
            val next = try {
                if (connection == null) {
                    log += context.getString(R.string.waiting_for_router)
                    update()
                }
                val router = router(waitSeconds)
                val folder = router.readChurnFolder()
                if (!folder.complete) throw RouterException(Problem.NOT_SET_UP)
                val swap = Swap(router, report = {
                    log += text(it)
                    update()
                })
                Screen.Swapping(body(swap, folder))
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
        connection?.let { return Router(it) }
        if (!network.socketsAllowed()) throw NoNetworkPermission()
        val password = typedPassword ?: saved.sealedPassword?.let { PasswordVault.open(it) } ?: throw MissingPassword()
        val deadline = System.nanoTime() + waitSeconds * 1_000_000_000L
        while (true) {
            val left = ((deadline - System.nanoTime()) / 1_000_000).toInt()
            val usb = network.await(maxOf(left, 1_000)) ?: throw NoRouterNetwork()
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
                network.release()
                throw e
            } catch (e: IOException) {
                // While the router starts, its network can show up before it accepts connections.
                network.release()
                if (System.nanoTime() >= deadline) throw e
                Thread.sleep(RETRY_MILLISECONDS)
            }
        }
    }

    private fun disconnect() {
        connection?.close()
        connection = null
        network.release()
    }

    /** Shows the router screen, or the setup screen if Churn's folder isn't complete. */
    private fun showRouter(note: String? = null) {
        val router = router()
        val folder = router.readChurnFolder()
        val step = saved.step
        show(
            when {
                !folder.complete -> setUpScreen(router, folder)
                step != null -> Screen.Swapping(step)
                else -> home(router, folder.factory!!, note)
            },
        )
    }

    private fun setUpScreen(router: Router, folder: ChurnFolder): Screen.SetUp {
        val factory = folder.factory ?: router.imeis()
        val warnings = mutableListOf<Int>()
        if (folder.factory == null) {
            if (factory == saved.written) warnings += R.string.setup_warning_written
            if (listOf(factory.imei1, factory.imei2).any { Imei.tac(it) != Imei.MUDI7_TAC }) {
                warnings += R.string.setup_warning_tac
            }
            if (!Imei.isValid(factory.imei1) || !Imei.isValid(factory.imei2)) {
                warnings += R.string.setup_warning_check_digit
            }
        } else if (folder.secret == null) {
            warnings += R.string.setup_warning_secret
        }
        return Screen.SetUp(factory, warnings.map { context.getString(it) })
    }

    private fun home(router: Router, factory: ImeiPair, note: String?): Screen.Home {
        val imeis = router.imeis()
        val notes = listOfNotNull(note).toMutableList()
        val written = saved.written
        if (written != null && written != imeis) notes += context.getString(R.string.note_imeis_changed)
        if (router.scheduledReboot()) notes += context.getString(R.string.note_scheduled_reboot)
        if (!router.enhancedKillSwitch()) notes += context.getString(R.string.note_kill_switch)
        val status = RouterStatus(
            imeis = imeis,
            factory = factory,
            airplaneMode = router.airplaneMode(),
            radio = runCatching { router.radio() }.getOrNull(),
            activeSlot = router.activeSlot(),
            slot2 = runCatching { router.slot2() }.getOrNull(),
        )
        return Screen.Home(status, notes)
    }

    private fun asset(name: String) = context.assets.open(name).use { it.readBytes() }

    private fun describe(e: Exception): String = when (e) {
        is RouterException -> text(e.problem) + (e.detail?.let { "\n\n$it" } ?: "")
        is NoRouterNetwork -> context.getString(R.string.error_no_network)
        is NoNetworkPermission -> context.getString(R.string.error_network_permission)
        is MissingPassword -> context.getString(R.string.error_no_password)
        is WrongPassword -> context.getString(R.string.error_password)
        is ConnectException, is NoRouteToHostException, is SocketTimeoutException ->
            context.getString(R.string.error_unreachable, saved.address)
        is IOException -> context.getString(R.string.error_connection, e.message ?: e.javaClass.simpleName)
        else -> context.getString(R.string.error_other, e.toString())
    }.also {
        if (e is IOException) disconnect()
    }

    private fun text(progress: Progress) = context.getString(
        when (progress) {
            Progress.AIRPLANE_MODE_ON -> R.string.progress_airplane_mode_on
            Progress.RADIO_OFF -> R.string.progress_radio_off
            Progress.THROWAWAY_IMEIS -> R.string.progress_throwaway_imeis
            Progress.SLOT2_ON_ESIM -> R.string.progress_slot2_on_esim
            Progress.NO_PROFILE -> R.string.progress_no_profile
            Progress.PROFILE_DISABLED -> R.string.progress_profile_disabled
            Progress.WAITING_FOR_RADIO -> R.string.progress_waiting_for_radio
            Progress.SLOT2_ON_SIM2 -> R.string.progress_slot2_on_sim2
            Progress.SLOT2_ACTIVE -> R.string.progress_slot2_active
            Progress.CARD_FOUND -> R.string.progress_card_found
            Progress.NEW_IMEIS -> R.string.progress_new_imeis
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
            Problem.NOT_SET_UP -> R.string.problem_not_set_up
        },
    )

    private companion object {
        val IPV4 = Regex("""((25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(25[0-5]|2[0-4]\d|1?\d?\d)""")
        const val CONNECT_WAIT_SECONDS = 15

        /** Long enough for the router to start after the user switched it on. */
        const val BOOT_WAIT_SECONDS = 300
        const val RETRY_MILLISECONDS = 3_000L
    }
}
