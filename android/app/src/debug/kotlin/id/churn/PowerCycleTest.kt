package id.churn

/**
 * Tier 3's power cycle test (docs/testing.md, "Power cycles"): the router switched off with poweroff and on again by
 * the user, twice, with both trays empty and slot 2 on the empty SIM 2 tray, where it emits nothing at power-on, and
 * what each power-on keeps and starts checked. The router is then put back as it was. Only debug builds have it.
 */
class PowerCycleTest(
    router: Router,
    report: (Progress) -> Unit,
    /** Reports a [Progress] whose line names a number of seconds. */
    private val measured: (Progress, Int) -> Unit,
    check: () -> List<String>,
    /** Waits up to the milliseconds it gets for the phone to lose the router's network, and says whether it did. */
    private val awaitNetworkLost: (Long) -> Boolean,
    sleep: (Long) -> Unit = Thread::sleep,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : Swap(router, report, ask = { error("The power cycle test asks nothing") }, check, sleep, now) {

    /** The IDs of the claims that the part of the test under way confirms, which a failure names. */
    var testing = emptyList<String>()
        private set

    /**
     * Starts the test, up to the first power-off. [save] gets what the test puts back and keeps across the power-offs,
     * before the first change and again before each power-off, and null once the router is as it was again. A test
     * that stops puts the router back if the radio is still off, and otherwise leaves that to [putBack].
     */
    fun run(save: (PowerCycleStart?) -> Unit) {
        val start = PowerCycleStart(router.slot2(), activeSlot(), router.imeis())
        save(start)
        stopping(start, save) {
            // Step 3 up to the card, without the dry run's reloads.
            part { startNewImeis(reload = false) }
            // With both trays empty and slot 2 on the empty SIM 2 tray, the router emits nothing at power-on.
            part { emptyTray() }
            powerOff(start, powerOn = 1, save)
        }
    }

    /**
     * Goes on with the test that [start] keeps, once the router is on again for the power-on that [start] waits for:
     * the checks after it, then the next power-off, or after the second power-on, the router put back.
     */
    fun resume(start: PowerCycleStart, save: (PowerCycleStart?) -> Unit) {
        stopping(start, save) {
            // A router that didn't shut down, such as one whose network went without it, keeps its boot ID.
            part("B4", "B6") {
                if (router.bootId() == start.bootId) {
                    throw RouterException(Problem.UNEXPECTED_ANSWER, "The boot ID is the one from before poweroff")
                }
                report(Progress.NEW_BOOT_ID)
            }
            part {
                waitForStart()
                waitForRadioOff()
            }
            if (start.powerOn == 1) {
                powerOff(start, powerOn = 2, save)
            } else {
                part { restore(start) }
                save(null)
                report(Progress.PUT_BACK)
                report(Progress.POWER_CYCLES_PASSED)
            }
        }
    }

    /**
     * Puts the router back as it was before a test that stopped, [start], once the router has started and its radio is
     * off, as at step 3: it may have been switched off and on since. [save] then gets null.
     */
    fun putBack(start: PowerCycleStart, save: (PowerCycleStart?) -> Unit) {
        turnAirplaneModeOn()
        waitForStart()
        waitForRadioOff()
        if (!asItWas(start)) restore(start)
        save(null)
        report(Progress.PUT_BACK)
    }

    /**
     * Shuts the router down for the [powerOn] after it, 1 or 2, once [save] has [start] with it and with the ID of the
     * router's current run, which a power-on changes. The phone has to lose the router's network within 120 s.
     */
    private fun powerOff(start: PowerCycleStart, powerOn: Int, save: (PowerCycleStart?) -> Unit) {
        // Saved before the router goes down, which may end the connection before it answers.
        save(start.copy(bootId = router.bootId(), powerOn = powerOn))
        part("B4", "B5") {
            val sent = now()
            router.powerOff()
            report(Progress.SHUTTING_DOWN)
            if (!awaitNetworkLost(NETWORK_LOSS_MILLIS)) {
                throw RouterException(Problem.UNEXPECTED_ANSWER, "The router's network was still there 120 s later")
            }
            measured(Progress.NETWORK_LOST, ((now() - sent) / 1000).toInt())
        }
    }

    /**
     * Runs [body] of the test that [start] keeps. If it stops, puts the router back as far as the router lets it. One
     * that doesn't answer, or whose radio isn't off, waits for [putBack], and [save] gets [start] waiting for no
     * power-on.
     */
    private fun stopping(start: PowerCycleStart, save: (PowerCycleStart?) -> Unit, body: () -> Unit) {
        try {
            body()
        } catch (e: Exception) {
            val putBack = runCatching {
                if (!asItWas(start)) {
                    checkRadioOff()
                    restore(start)
                }
            }.isSuccess
            if (putBack) {
                save(null)
                report(Progress.PUT_BACK)
            } else {
                save(start.copy(powerOn = 0))
            }
            throw e
        }
    }

    /** Runs [part] of the test, which confirms the claims with the IDs [ids]. */
    private fun part(vararg ids: String, part: () -> Unit) {
        testing = ids.toList()
        part()
    }

    companion object {
        /** Debug builds have the power cycle test. */
        const val AVAILABLE = true

        /** How long the phone may keep the router's network after poweroff, in milliseconds, as B5 says: 120 s. */
        private const val NETWORK_LOSS_MILLIS = 120_000L
    }
}
