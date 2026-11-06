package id.churn

import java.io.IOException
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerCycleTestTest {

    private val fake = FakeRouter().apply {
        // Both trays empty, as tier 3 needs.
        trayCard = null
        // As after step 1, so that the test has slot 2's setting and the active slot to put back.
        slot2 = Slot2.ESIM
        activeSlot = 1
    }

    /** The connection to [fake], which fails every command once [broken] is set, as when the cable is pulled. */
    private val shell = object : Shell {
        var broken = false
        var breakAt: (String) -> Boolean = { false }

        /** Runs after each command that [fake] answered, with the command. */
        var after: (String) -> Unit = {}

        override fun run(command: String, input: ByteArray?, timeoutSeconds: Long): Output {
            if (breakAt(command)) broken = true
            if (broken) throw IOException("Broken pipe")
            return fake.run(command, input, timeoutSeconds).also { after(command) }
        }
    }
    private val progress = mutableListOf<Progress>()

    /** The seconds that the lines naming a number of seconds named, in turn. */
    private val seconds = mutableListOf<Int>()

    /** The claims that the test's parts named while they reported. */
    private val claims = mutableSetOf<String>()

    /** How long after `poweroff` the phone loses the router's network, if the router shut down. */
    private var networkLostAfter = 9_000L

    /** Whether the phone loses the router's network after `poweroff` though the router stays on. */
    private var networkLostWhileOn = false

    /** What the test kept on the phone, after each time it saved. */
    private val saves = mutableListOf<PowerCycleStart?>()

    private fun test(): PowerCycleTest {
        lateinit var made: PowerCycleTest
        made = PowerCycleTest(
            Router(shell),
            report = {
                progress += it
                claims += made.testing
            },
            measured = { line, value ->
                progress += line
                seconds += value
                claims += made.testing
            },
            check = { emptyList() },
            awaitNetworkLost = { millis ->
                val lost = (fake.poweredOff || networkLostWhileOn) && networkLostAfter <= millis
                fake.clock += if (lost) networkLostAfter else millis
                lost
            },
            sleep = { fake.clock += it },
            now = { fake.clock },
        )
        return made
    }

    private val powerCycles = test()

    /** What the test puts back, as the router has it now. */
    private fun state() = PowerCycleStart(fake.slot2, fake.activeSlot, ImeiPair(fake.imei1, fake.imei2))

    /** The factory IMEIs, which the router still has. */
    private val factory = ImeiPair(fake.imei1, fake.imei2)

    /** The test pair's serials: first the router's own pair, which tests no write, then another. */
    private val random = Serials(500_000, 123_456)

    /** The test pair that [random] gives. */
    private val testPair = Imei.random(Serials(123_456), factory)

    private fun run() = powerCycles.run(factory, random) { saves += it }

    /** The IMEIs that the commands sent to [fake] wrote as IMEI [item], 7 or 11, in turn. */
    private fun imeiWrites(item: Int) =
        fake.commands.mapNotNull { Regex("""AT\+EGMR=1,$item,"(\d{15})"""").find(it)?.groupValues?.get(1) }

    /** Switches the router on again and goes on with the test, as once the router shows up on the cable. */
    private fun resume(test: PowerCycleTest = powerCycles) {
        if (fake.poweredOff) fake.boot()
        test.resume(saves.last()!!) { saves += it }
    }

    private fun assertStops(problem: Problem, run: () -> Unit) {
        try {
            run()
        } catch (e: RouterException) {
            assertEquals(problem, e.problem)
            return
        }
        throw AssertionError("The power cycle test didn't stop")
    }

    private fun assertSafe() {
        assertEquals(emptyList<String>(), fake.unsafe)
        assertEquals(emptyList<String>(), fake.unlocked)
    }

    @Test
    fun powerCycleTestShutsTheRouterDownTwiceAndPutsItBack() {
        val before = state()
        val bootIds = mutableListOf(fake.bootId)
        run()
        assertEquals(
            listOf(
                Progress.AIRPLANE_MODE_ON, Progress.WAITING_FOR_START, Progress.RADIO_OFF, Progress.SIM1_EMPTY,
                Progress.KILL_SWITCH_ON, Progress.FIREWALL, Progress.SLOT2_ON_SIM2, Progress.SLOT2_ACTIVE,
                Progress.TRAY_EMPTY, Progress.TEST_PAIR_SAVED, Progress.SHUTTING_DOWN, Progress.NETWORK_LOST,
            ),
            progress,
        )
        progress.clear()
        resume()
        bootIds += fake.bootId
        val start = listOf(
            Progress.NEW_BOOT_ID, Progress.SETTINGS_KEPT, Progress.WAITING_FOR_START, Progress.RULES_AT_START,
            Progress.CHAINS_AT_START, Progress.MODEM_ANSWERED, Progress.RADIO_ON_UNTIL, Progress.RADIO_OFF_FROM,
            Progress.IMEIS_KEPT,
        )
        assertEquals(
            start + listOf(Progress.EARLIER_IMEIS_SAVED, Progress.SHUTTING_DOWN, Progress.NETWORK_LOST),
            progress,
        )
        progress.clear()
        resume()
        assertEquals(start + listOf(Progress.PUT_BACK, Progress.POWER_CYCLES_PASSED), progress)
        // The network lost after poweroff, the rules at once, then the modem's first answer and the radio's window,
        // read every 2 s.
        assertEquals(listOf(9, 0, 0, 30, 56, 58, 9, 0, 0, 30, 56, 58), seconds)
        assertEquals(before, state())
        // Kept before the first change, again before each power-off, and forgotten once the router is as it was.
        val first = before.copy(bootId = bootIds[0], powerOn = 1, testImeis = testPair)
        assertEquals(listOf(before, first, first.copy(bootId = bootIds[1], powerOn = 2), null), saves)
        assertEquals(2, fake.commands.count { it == "poweroff" })
        assertTrue(fake.uptime >= 120)
        // As a swap leaves them.
        assertTrue(fake.airplaneMode)
        assertTrue(Router(fake).firewallInPlace())
        assertSafe()
    }

    @Test
    fun powerCycleTestConfirmsTier3ClaimsOfTestingMd() {
        run()
        resume()
        resume()
        assertEquals(setOf("B1", "B2", "B3", "B4", "B5", "B6", "D14", "K10", "M7", "U3"), claims)
        for (id in claims) {
            val tiers = ChecksTest.TIERS[id] ?: throw AssertionError("$id isn't a claim in testing.md")
            assertTrue("$id isn't a tier 3 claim in testing.md", "3" in tiers)
        }
    }

    @Test
    fun powerCycleTestGoesOnInANewRunOfTheApp() {
        val before = state()
        run()
        // The app lost the test with the connection, and has only what it saved.
        resume(test())
        resume(test())
        assertEquals(before, state())
        assertNull(saves.last())
        assertEquals(Progress.POWER_CYCLES_PASSED, progress.last())
    }

    @Test
    fun powerCycleTestSavesATestPairAndThenTheEarlierPairEachBeforeAPowerOff() {
        val before = state()
        run()
        assertEquals(listOf(testPair.imei1), imeiWrites(7))
        assertEquals(listOf(testPair.imei2), imeiWrites(11))
        resume()
        assertEquals(listOf(testPair.imei1, before.imeis.imei1), imeiWrites(7))
        assertEquals(listOf(testPair.imei2, before.imeis.imei2), imeiWrites(11))
        resume()
        assertEquals(before, state())
        assertSafe()
    }

    @Test
    fun aTestPairThatThePowerOnLostStopsTheTest() {
        fake.imeiSaveIgnored = true
        val before = state()
        run()
        assertStops(Problem.UNEXPECTED_ANSWER) { resume() }
        assertEquals(listOf("M7"), powerCycles.testing)
        assertTrue(Progress.IMEIS_KEPT !in progress)
        assertEquals(before, state())
        assertNull(saves.last())
    }

    @Test
    fun anEarlierPairThatTheSecondPowerOnLostStopsTheTest() {
        val before = state()
        run()
        // The earlier pair isn't saved after the first power-on, so the second brings the test pair back.
        fake.imeiSaveIgnored = true
        resume()
        assertStops(Problem.UNEXPECTED_ANSWER) { resume() }
        assertEquals(listOf("M7"), powerCycles.testing)
        assertEquals(1, progress.count { it == Progress.IMEIS_KEPT })
        // Put back with the earlier pair written again.
        assertEquals(before, state())
        assertNull(saves.last())
    }

    @Test
    fun anAirplaneModeThatThePowerOnLostStopsTheTest() {
        val before = state()
        run()
        fake.boot()
        fake.airplaneMode = false
        assertStops(Problem.UNEXPECTED_ANSWER) { resume() }
        assertEquals(listOf("U3"), powerCycles.testing)
        assertTrue(Progress.SETTINGS_KEPT !in progress)
        // The radio is on, with airplane mode off, so the test changes nothing more.
        assertEquals(Slot2.SIM2, fake.slot2)
        assertEquals(0, saves.last()!!.powerOn)
        powerCycles.putBack(saves.last()!!) { saves += it }
        assertEquals(before, state())
        assertNull(saves.last())
        assertSafe()
    }

    @Test
    fun aKillSwitchSettingThatThePowerOnLostStopsTheTest() {
        val before = state()
        run()
        fake.boot()
        // Only the switch's setting, with airplane mode still on.
        fake.killSwitchSet = false
        assertStops(Problem.UNEXPECTED_ANSWER) { resume() }
        assertEquals(listOf("U3"), powerCycles.testing)
        // Checked at once, while the radio is still on after the power-on, so the test waits for Put the router back.
        assertEquals(0, saves.last()!!.powerOn)
        powerCycles.putBack(saves.last()!!) { saves += it }
        assertEquals(before, state())
        assertNull(saves.last())
    }

    @Test
    fun powerCycleTestStopsAtACardInTheTrayBeforeThePowerOff() {
        fake.trayCard = FakeRouter.Card("8941000000000000015F", "228010000000001")
        val before = state()
        assertStops(Problem.CARD_IN_TRAY) { run() }
        assertTrue("poweroff" !in fake.commands)
        assertTrue(fake.commands.none { it.contains("AT+EGMR=1") })
        assertEquals(before, state())
        assertEquals(listOf(before, null), saves)
        assertSafe()
    }

    @Test
    fun powerCycleTestStopsAtACardInTheSim1TrayBeforeItChangesSlot2() {
        fake.tray1Card = FakeRouter.Card("8941000000000000023F", "228010000000002")
        val before = state()
        assertStops(Problem.SIM1_CARD) { run() }
        assertTrue("poweroff" !in fake.commands)
        assertEquals(before, state())
        assertEquals(listOf(before, null), saves)
    }

    @Test
    fun aRouterWhoseNetworkStaysAfterPoweroffStopsTheTest() {
        fake.poweroffIgnored = true
        val before = state()
        assertStops(Problem.UNEXPECTED_ANSWER) { run() }
        assertEquals(listOf("B4", "B5"), powerCycles.testing)
        assertTrue(Progress.NETWORK_LOST !in progress)
        // The router still answers with its radio off, so the test put it back at once.
        assertEquals(before, state())
        assertNull(saves.last())
        assertEquals(Progress.PUT_BACK, progress.last())
    }

    @Test
    fun aNetworkLostLaterThan120SecondsStopsTheTest() {
        networkLostAfter = 121_000L
        val before = state()
        assertStops(Problem.UNEXPECTED_ANSWER) { run() }
        assertEquals(listOf("B4", "B5"), powerCycles.testing)
        // The router is off, so the test waits for Put the router back, not for a power-on.
        assertEquals(0, saves.last()!!.powerOn)
        fake.boot()
        powerCycles.putBack(saves.last()!!) { saves += it }
        assertEquals(before, state())
        assertNull(saves.last())
    }

    @Test
    fun aNetworkLostWithin120SecondsIsTimed() {
        networkLostAfter = 100_000L
        run()
        assertEquals(Progress.NETWORK_LOST, progress.last())
        assertEquals(listOf(100), seconds)
        assertEquals(1, saves.last()!!.powerOn)
    }

    @Test
    fun aRouterThatKeptItsBootIdStopsTheTest() {
        // Its network went at poweroff, but it didn't shut down.
        fake.poweroffIgnored = true
        networkLostWhileOn = true
        val before = state()
        run()
        assertStops(Problem.UNEXPECTED_ANSWER) { resume() }
        assertEquals(listOf("B4", "B6"), powerCycles.testing)
        assertTrue(Progress.NEW_BOOT_ID !in progress)
        assertEquals(before, state())
        assertNull(saves.last())
        assertEquals(Progress.PUT_BACK, progress.last())
    }

    @Test
    fun aModemThatAnswersAtOnceLeavesB1Unconfirmed() {
        fake.modemAnswersFrom = 0.0
        run()
        resume()
        assertTrue(Progress.MODEM_AT_ONCE in progress)
        assertEquals(0, seconds[3])
        assertTrue(Progress.MODEM_ANSWERED !in progress)
        resume()
        assertEquals(Progress.POWER_CYCLES_PASSED, progress.last())
    }

    @Test
    fun aRouterReachedAfterTheWindowLeavesB1AndB2Unconfirmed() {
        run()
        fake.boot()
        // As when the user switched the router on long before the phone reached it.
        fake.clock += 70_000
        progress.clear()
        resume()
        val start = progress.dropWhile { it != Progress.WAITING_FOR_START }.drop(3).take(3)
        assertEquals(listOf(Progress.MODEM_AT_ONCE, Progress.RADIO_OFF_AT_FIRST, Progress.RADIO_OFF_FROM), start)
        assertEquals(listOf(70, 70, 70, 70, 70), seconds.subList(1, 6))
    }

    @Test
    fun killSwitchRulesThatThePowerOnDroppedStopTheTest() {
        fake.bootDropsKillSwitchRules = true
        val before = state()
        run()
        assertStops(Problem.UNEXPECTED_ANSWER) { resume() }
        assertEquals(listOf("K10"), powerCycles.testing)
        // Checked once the router has started, so the test put the router back at once.
        assertEquals(before, state())
        assertNull(saves.last())
    }

    @Test
    fun killSwitchRulesInPlaceOnlyLaterAreTimed() {
        fake.bootDropsKillSwitchRules = true
        run()
        fake.boot()
        // As if GL's route policy set them up a while after the network.
        shell.after = { if (fake.uptime >= 40) fake.killSwitchInUse = true }
        resume()
        // After the first power-off's line, the rules', then the chains' at once.
        assertEquals(listOf(40, 0), seconds.subList(1, 3))
    }

    @Test
    fun churnsRulesThatThePowerOnDidNotLoadStopTheTest() {
        fake.bootSkipsChurnRules = true
        val before = state()
        run()
        assertStops(Problem.UNEXPECTED_ANSWER) { resume() }
        assertEquals(listOf("D14"), powerCycles.testing)
        assertTrue(Progress.RULES_AT_START in progress)
        assertEquals(before, state())
        assertNull(saves.last())
    }

    @Test
    fun aModemThatNeverAnswersStopsTheTest() {
        run()
        fake.modemAnswersFrom = 1_000.0
        assertStops(Problem.UNEXPECTED_ANSWER) { resume() }
        assertEquals(listOf("B1"), powerCycles.testing)
        // The modem doesn't answer, so the test can't put the router back yet.
        assertEquals(0, saves.last()!!.powerOn)
    }

    @Test
    fun aRadioWindowPast58SecondsStopsTheTest() {
        fake.radioWindowEnd = 70.0
        val before = state()
        run()
        assertStops(Problem.UNEXPECTED_ANSWER) { resume() }
        assertEquals(listOf("B2"), powerCycles.testing)
        assertTrue(Progress.RADIO_ON_UNTIL !in progress)
        // Checked once the router has started, with its radio off, so the test put the router back at once.
        assertEquals(before, state())
        assertNull(saves.last())
    }

    @Test
    fun aRadioOnAgainAfterTheWindowStopsTheTest() {
        fake.radioBurst = 90.0..95.0
        val before = state()
        run()
        assertStops(Problem.UNEXPECTED_ANSWER) { resume() }
        assertEquals(listOf("B3"), powerCycles.testing)
        assertEquals(Progress.RADIO_ON_UNTIL, progress.last { it != Progress.PUT_BACK })
        assertEquals(before, state())
        assertNull(saves.last())
    }

    @Test
    fun aRadioOnAt120SecondsStopsTheTest() {
        fake.radioBurst = 120.0..130.0
        run()
        assertStops(Problem.UNEXPECTED_ANSWER) { resume() }
        assertEquals(listOf("B3"), powerCycles.testing)
        assertEquals(0, saves.last()!!.powerOn)
    }

    @Test
    fun aRadioUnansweredAt120SecondsStopsTheTest() {
        run()
        fake.boot()
        // The modem stops answering AT+CFUN? after its window.
        shell.after = { if (fake.uptime >= 100) fake.atAnswers["AT+CFUN?"] = "+CFUN: ?" }
        assertStops(Problem.UNEXPECTED_ANSWER) { resume() }
        assertEquals(listOf("B3"), powerCycles.testing)
        assertEquals(0, saves.last()!!.powerOn)
    }

    @Test
    fun aTestWhoseRadioIsOnAfterAPowerOnLeavesTheRouterToPutBackLater() {
        val before = state()
        run()
        fake.boot()
        fake.radioStuckOn = true
        assertStops(Problem.UNEXPECTED_ANSWER) { resume() }
        assertEquals(listOf("B2"), powerCycles.testing)
        assertEquals(0, saves.last()!!.powerOn)
        // Still on the empty tray, which the test changes nothing more on.
        assertEquals(Slot2.SIM2, fake.slot2)
        fake.radioStuckOn = false
        powerCycles.putBack(saves.last()!!) { saves += it }
        assertEquals(before, state())
        assertNull(saves.last())
        assertSafe()
    }

    @Test
    fun putBackInsteadOfGoingOnPutsTheRouterBackAfterThePowerOn() {
        val before = state()
        run()
        // The user taps Put the router back, then switches the router on, after turning airplane mode off meanwhile.
        fake.boot()
        fake.airplaneMode = false
        progress.clear()
        test().putBack(saves.last()!!) { saves += it }
        assertEquals(before, state())
        assertNull(saves.last())
        assertEquals(
            listOf(Progress.AIRPLANE_MODE_ON, Progress.WAITING_FOR_START, Progress.RADIO_OFF, Progress.PUT_BACK),
            progress,
        )
        assertTrue(fake.uptime >= 120)
        assertSafe()
    }

    @Test
    fun aTestCutOffBeforeThePowerOffIsPutBackOnceTheRouterHasStartedAgain() {
        val before = state()
        // At slot 2 made the active slot, once it is on the SIM 2 tray.
        shell.breakAt = { "\"current_sim\":2" in it }
        try {
            run()
            throw AssertionError("The power cycle test didn't stop")
        } catch (_: IOException) {
        }
        assertTrue("poweroff" !in fake.commands)
        assertEquals(Slot2.SIM2, fake.slot2)
        assertEquals(before, saves.last())
        shell.broken = false
        shell.breakAt = { false }
        fake.boot()
        test().putBack(saves.last()!!) { saves += it }
        assertEquals(before, state())
        assertNull(saves.last())
    }

    /** Gives [values] in turn, as the serials of test pairs. */
    private class Serials(private vararg val values: Int) : SecureRandom() {
        private var next = 0

        override fun nextInt(bound: Int) = values[next++]
    }
}
