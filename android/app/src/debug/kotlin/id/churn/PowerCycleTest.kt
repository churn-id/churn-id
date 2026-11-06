package id.churn

import java.security.SecureRandom

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
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : Swap(router, report, ask = { error("The power cycle test asks nothing") }, check, sleep, now) {

    /** The IDs of the claims that the part of the test under way confirms, which a failure names. */
    var testing = emptyList<String>()
        private set

    /**
     * Starts the test, up to the first power-off, with a test pair of IMEIs from [random] with the TACs and the spacing
     * of the [factory] IMEIs. [save] gets what the test puts back and keeps across the power-offs, before the first
     * change and again before each power-off, and null once the router is as it was again. A test that stops puts the
     * router back if the radio is still off, and otherwise leaves that to [putBack].
     */
    fun run(factory: ImeiPair, random: SecureRandom, save: (PowerCycleStart?) -> Unit) {
        val start = PowerCycleStart(router.slot2(), activeSlot(), router.imeis())
        save(start)
        stopping(start, save) {
            // Step 3 up to the card, without the dry run's reloads.
            part { startNewImeis(reload = false) }
            // With both trays empty and slot 2 on the empty SIM 2 tray, the router emits nothing at power-on.
            part { emptyTray() }
            val test = generateSequence { Imei.random(random, factory) }.first { it != start.imeis }
            // Right before the power-off: without the save, a reboot 5 s after a write brought the earlier IMEIs back.
            part("M7") {
                router.writeImeis(test)
                checkRadioOff()
                report(Progress.TEST_PAIR_SAVED)
            }
            powerOff(start.copy(testImeis = test), powerOn = 1, save)
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
            // Before the test reads or sets them again: what the last uci commit wrote.
            part("U3") {
                val lost = listOfNotNull(
                    "airplane mode".takeIf { !router.airplaneMode() },
                    "the Enhanced Kill Switch".takeIf { !router.killSwitchSettings() },
                )
                if (lost.isNotEmpty()) {
                    throw RouterException(Problem.UNEXPECTED_ANSWER, "Not kept: ${lost.joinToString(" and ")}")
                }
                report(Progress.SETTINGS_KEPT)
            }
            // From the moment the test reaches the router, while the modem may not answer yet.
            checkStart(part("K10", "D14", "B1", "B2", "B3") { watchStart() })
            part("M7") {
                // The test pair after the first power-on, and the earlier pair after the second.
                val saved = if (start.powerOn == 1) start.testImeis else start.imeis
                if (router.imeis() != saved) {
                    throw RouterException(Problem.UNEXPECTED_ANSWER, "AT+EGMR=0: not the pair saved before poweroff")
                }
                report(Progress.IMEIS_KEPT)
            }
            if (start.powerOn == 1) {
                part("M7") {
                    router.writeImeis(start.imeis)
                    checkRadioOff()
                    report(Progress.EARLIER_IMEIS_SAVED)
                }
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

    /** The modem's answer to `AT+CFUN?` [uptime] seconds after power-on: the [radio] state, or null for none. */
    private class RadioReading(val uptime: Double, val radio: Int?) {
        /** [uptime] in whole seconds, as the lines name it. */
        val seconds get() = uptime.toInt()
    }

    /**
     * What the test saw after a power-on until the router had started: the modem's answers, [readings], and the seconds
     * after power-on at which the Enhanced Kill Switch's routing rules, [rulesAt], and Churn's firewall chains,
     * [chainsAt], were first in place, or null for never.
     */
    private class Start(val readings: List<RadioReading>, val rulesAt: Int?, val chainsAt: Int?)

    /**
     * Reads `/proc/uptime` and `AT+CFUN?` every 2 s until the router has started, 120 s after power-on, and the
     * Enhanced Kill Switch's routing rules and Churn's firewall chains until they are in place.
     */
    private fun watchStart(): Start {
        report(Progress.WAITING_FOR_START)
        val readings = mutableListOf<RadioReading>()
        var rulesAt: Int? = null
        var chainsAt: Int? = null
        // Only a restart sets the uptime back, and it ends the connection first.
        while (true) {
            val uptime = router.uptimeSeconds()
            // An answer that doesn't parse counts as none, as when the modem doesn't answer yet.
            val radio = try {
                router.radio()
            } catch (_: RouterException) {
                null
            }
            readings += RadioReading(uptime, radio)
            if (rulesAt == null && killSwitchRules(router) == CheckResult.Passed) rulesAt = uptime.toInt()
            if (chainsAt == null && router.firewallLoaded()) chainsAt = uptime.toInt()
            if (uptime >= SETTLED_UPTIME_SECONDS) return Start(readings, rulesAt, chainsAt)
            sleep(WATCH_MILLIS)
        }
    }

    /**
     * Checks what the test saw after a power-on, [start], against K10, D14, B1, B2 and B3: the kill switch's rules and
     * Churn's chains in place by the time the router had started, and the modem not answering at first, then reporting
     * its radio on until 58 s at the latest, and the radio off from then on, also 120 s after power-on.
     */
    private fun checkStart(start: Start) {
        part("K10") {
            val at = start.rulesAt ?: throw RouterException(Problem.UNEXPECTED_ANSWER, "ip rule show: $NOT_BY_START")
            measured(Progress.RULES_AT_START, at)
        }
        part("D14") {
            val at = start.chainsAt ?: throw RouterException(Problem.UNEXPECTED_ANSWER, "nft list chain: $NOT_BY_START")
            measured(Progress.CHAINS_AT_START, at)
        }
        val readings = start.readings
        val answered = readings.filter { it.radio != null }
        part("B1") {
            val first = answered.firstOrNull()
                ?: throw RouterException(Problem.UNEXPECTED_ANSWER, "AT+CFUN?: no answer until 120 s after power-on")
            // A phone that reaches the router late finds the modem answering already.
            val atOnce = readings.first().radio != null
            measured(if (atOnce) Progress.MODEM_AT_ONCE else Progress.MODEM_ANSWERED, first.seconds)
        }
        part("B2") {
            // The radio-on window: +CFUN: 1 before the first +CFUN: 4.
            val window = answered.takeWhile { it.radio != RADIO_OFF }.filter { it.radio == RADIO_ON }
            window.firstOrNull { it.uptime > WINDOW_END_SECONDS }?.let {
                throw RouterException(Problem.UNEXPECTED_ANSWER, "AT+CFUN?: +CFUN: 1 ${it.seconds} s after power-on")
            }
            if (window.isEmpty()) {
                measured(Progress.RADIO_OFF_AT_FIRST, answered.first().seconds)
            } else {
                measured(Progress.RADIO_ON_UNTIL, window.last().seconds)
            }
        }
        part("B3") {
            val off = answered.dropWhile { it.radio != RADIO_OFF }
            off.firstOrNull { it.radio != RADIO_OFF }?.let {
                val detail = "AT+CFUN?: +CFUN: ${it.radio} ${it.seconds} s after power-on, after +CFUN: 4"
                throw RouterException(Problem.UNEXPECTED_ANSWER, detail)
            }
            if (readings.last().radio != RADIO_OFF) {
                throw RouterException(Problem.UNEXPECTED_ANSWER, "AT+CFUN?: no +CFUN: 4 120 s after power-on")
            }
            measured(Progress.RADIO_OFF_FROM, off.first().seconds)
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

    /** Runs [part] of the test, which confirms the claims with the IDs [ids], and returns what it returns. */
    private fun <T> part(vararg ids: String, part: () -> T): T {
        testing = ids.toList()
        return part()
    }

    companion object {
        /** Debug builds have the power cycle test. */
        const val AVAILABLE = true

        /** How long the phone may keep the router's network after poweroff, in milliseconds, as B5 says: 120 s. */
        private const val NETWORK_LOSS_MILLIS = 120_000L

        /** How often the test reads the uptime and the radio after a power-on, as testing.md says. */
        private const val WATCH_MILLIS = 2_000L

        /** Why K10 or D14 failed. */
        private const val NOT_BY_START = "not in place 120 s after power-on"

        /** The latest end of the radio-on window after power-on, as B2 says. */
        private const val WINDOW_END_SECONDS = 58.0
        private const val RADIO_ON = 1
        private const val RADIO_OFF = 4
    }
}
