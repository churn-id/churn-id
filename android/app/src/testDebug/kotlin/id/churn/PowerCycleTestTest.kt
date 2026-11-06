package id.churn

import java.io.IOException
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

        override fun run(command: String, input: ByteArray?, timeoutSeconds: Long): Output {
            if (breakAt(command)) broken = true
            if (broken) throw IOException("Broken pipe")
            return fake.run(command, input, timeoutSeconds)
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

    private fun run() = powerCycles.run { saves += it }

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
                Progress.TRAY_EMPTY, Progress.SHUTTING_DOWN, Progress.NETWORK_LOST,
            ),
            progress,
        )
        progress.clear()
        resume()
        bootIds += fake.bootId
        assertEquals(
            listOf(
                Progress.NEW_BOOT_ID, Progress.WAITING_FOR_START, Progress.RADIO_OFF, Progress.SHUTTING_DOWN,
                Progress.NETWORK_LOST,
            ),
            progress,
        )
        progress.clear()
        resume()
        assertEquals(
            listOf(
                Progress.NEW_BOOT_ID, Progress.WAITING_FOR_START, Progress.RADIO_OFF, Progress.PUT_BACK,
                Progress.POWER_CYCLES_PASSED,
            ),
            progress,
        )
        assertEquals(listOf(9, 9), seconds)
        assertEquals(before, state())
        // Kept before the first change, again before each power-off, and forgotten once the router is as it was.
        assertEquals(
            listOf(
                before, before.copy(bootId = bootIds[0], powerOn = 1), before.copy(bootId = bootIds[1], powerOn = 2),
                null,
            ),
            saves,
        )
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
        assertEquals(setOf("B4", "B5", "B6"), claims)
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
    fun powerCycleTestStopsAtACardInTheTrayBeforeThePowerOff() {
        fake.trayCard = FakeRouter.Card("8941000000000000015F", "228010000000001")
        val before = state()
        assertStops(Problem.CARD_IN_TRAY) { run() }
        assertTrue("poweroff" !in fake.commands)
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
    fun aTestWhoseRadioIsOnAfterAPowerOnLeavesTheRouterToPutBackLater() {
        val before = state()
        run()
        fake.boot()
        fake.radioStuckOn = true
        assertStops(Problem.RADIO_ON) { resume() }
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
}
