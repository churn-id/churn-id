package id.churn

import java.io.IOException
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DryRunTest {

    private val fake = FakeRouter().apply {
        // A router with no SIM: both trays empty, and no profile enabled on the built-in eSIM.
        trayCard = null
        esimProfile = null
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

    /** The claims that the dry run's parts named while they reported. */
    private val claims = mutableSetOf<String>()

    /** Runs where the dry run runs the router checks, and returns the lines of those that failed. */
    private var checks = { emptyList<String>() }
    private val dryRun = dryRun()

    /** What the dry run kept on the phone, after each time it saved. */
    private val saves = mutableListOf<DryRunStart?>()

    private fun dryRun(): DryRun {
        lateinit var made: DryRun
        made = DryRun(
            Router(shell),
            report = {
                progress += it
                claims += made.testing
            },
            check = { checks() },
            sleep = { fake.clock += it },
            now = { fake.clock },
        )
        return made
    }

    /** What the dry run puts back, as the router has it now. */
    private fun state() = DryRunStart(fake.slot2, fake.activeSlot, ImeiPair(fake.imei1, fake.imei2))

    /** The slots that the commands sent to [fake] made the active slot, in turn. */
    private fun activations() =
        fake.commands.mapNotNull { Regex(""""current_sim":(\d)""").find(it)?.groupValues?.get(1) }

    /** The factory IMEIs, which the router still has. */
    private val factory = ImeiPair(fake.imei1, fake.imei2)

    /** The test pair's serials: first the router's own pair, which tests no write, then another. */
    private val random = Serials(500_000, 123_456)

    private fun run() = dryRun.run(factory, random) { saves += it }

    /** The IMEIs that the commands sent to [fake] wrote as IMEI [item], 7 or 11, in turn. */
    private fun imeiWrites(item: Int) =
        fake.commands.mapNotNull { Regex("""AT\+EGMR=1,$item,"(\d{15})"""").find(it)?.groupValues?.get(1) }

    private fun assertStops(problem: Problem, run: () -> Unit) {
        try {
            run()
        } catch (e: RouterException) {
            assertEquals(problem, e.problem)
            return
        }
        throw AssertionError("The dry run didn't stop")
    }

    private fun assertSafe() {
        assertEquals(emptyList<String>(), fake.unsafe)
        assertEquals(emptyList<String>(), fake.unlocked)
    }

    @Test
    fun dryRunRunsStepsOneAndThreeAndPutsTheRouterBack() {
        val before = state()
        run()
        assertEquals(
            listOf(
                Progress.AIRPLANE_MODE_ON, Progress.RADIO_OFF, Progress.SLOT2_ON_ESIM, Progress.NO_PROFILE,
                Progress.AIRPLANE_MODE_ON, Progress.WAITING_FOR_START, Progress.RADIO_OFF, Progress.SIM1_EMPTY,
                Progress.NETWORK_RELOADED, Progress.FIREWALL_RELOADED, Progress.SLOT1_ACTIVE, Progress.SLOT2_ON_SIM2,
                Progress.SLOT2_ACTIVE, Progress.TRAY_EMPTY, Progress.ESIM_SILENT, Progress.TEST_IMEIS,
                Progress.EARLIER_IMEIS, Progress.ANSWERS_MATCHED, Progress.SLOT2_ON_ESIM, Progress.PUT_BACK,
                Progress.DRY_RUN_PASSED,
            ),
            progress,
        )
        // Slot 1 first, so that step 3 makes slot 2 the active slot.
        assertEquals(listOf("1", "2"), activations())
        assertEquals(before, state())
        // Kept before the first change, and forgotten once the router is as it was.
        assertEquals(listOf(before, null), saves)
        // As a swap leaves them.
        assertTrue(fake.airplaneMode)
        assertTrue(Router(fake).firewallInPlace())
        assertSafe()
    }

    @Test
    fun dryRunPutsSlot2BackOnTheEsimAndSlot1BackAsTheActiveSlot() {
        // As on a router new to Churn, parked by hand.
        fake.slot2 = Slot2.ESIM
        fake.activeSlot = 1
        val before = state()
        run()
        assertEquals(before, state())
        assertEquals(Progress.DRY_RUN_PASSED, progress.last())
        // Slot 1 is the active slot already, so only step 3 and the put-back switch it.
        assertEquals(listOf("2", "1"), activations())
        assertSafe()
    }

    @Test
    fun dryRunConfirmsTier2ClaimsOfTestingMd() {
        run()
        assertEquals(setOf("S2", "S3", "K8", "D19", "U6", "E4", "M6", "P5"), claims)
        for (id in claims) {
            val tiers = ChecksTest.TIERS[id] ?: throw AssertionError("$id isn't a claim in testing.md")
            assertTrue("$id isn't a tier 2 claim in testing.md", "2" in tiers)
        }
    }

    @Test
    fun dryRunStopsAtAnEnabledProfileWithoutDisablingIt() {
        fake.esimProfile = "8944000000000000123F"
        val before = state()
        assertStops(Problem.PROFILE_ENABLED) { run() }
        assertEquals("8944000000000000123F", fake.esimProfile)
        assertEquals(before, state())
        assertEquals(listOf(before, null), saves)
        assertEquals(listOf("S2", "S3"), dryRun.testing)
        assertSafe()
    }

    @Test
    fun dryRunStopsAtACardInTheTrayBeforeWritingAnything() {
        fake.trayCard = FakeRouter.Card("8941000000000000015F", "228010000000001")
        fake.slot2 = Slot2.ESIM
        val before = state()
        assertStops(Problem.CARD_IN_TRAY) { run() }
        assertEquals(before, state())
        assertEquals(listOf(before, null), saves)
        assertTrue(fake.commands.none { it.contains("AT+EGMR=1") })
        assertSafe()
    }

    @Test
    fun dryRunStopsAtAnAdapterCardWithoutAProfileToo() {
        // A 9eSIM card with no profile enabled, which step 3 counts as no card.
        fake.trayCard = FakeRouter.Card("89111111111111111111", "111111111111111")
        val before = state()
        assertStops(Problem.CARD_IN_TRAY) { run() }
        assertEquals(before, state())
    }

    @Test
    fun dryRunStopsWhenTheBuiltInEsimAnswersWithSlot2OnTheTray() {
        fake.esimAnswersOnTray = true
        val before = state()
        assertStops(Problem.UNEXPECTED_ANSWER) { run() }
        assertEquals(listOf("E4"), dryRun.testing)
        // Not taken for a card in the tray.
        assertEquals(Progress.TRAY_EMPTY, progress.dropLast(1).last())
        assertEquals(before, state())
    }

    @Test
    fun dryRunWritesATestPairAndThenTheEarlierPair() {
        val before = state()
        run()
        val test = Imei.random(Serials(123_456), factory)
        assertEquals(listOf(test.imei1, before.imeis.imei1), imeiWrites(7))
        assertEquals(listOf(test.imei2, before.imeis.imei2), imeiWrites(11))
        assertSafe()
    }

    @Test
    fun dryRunStopsWhenTheModemRefusesTheTestPair() {
        fake.imeiWritesRefused = true
        val before = state()
        assertStops(Problem.UNEXPECTED_ANSWER) { run() }
        assertEquals(listOf("M6"), dryRun.testing)
        assertEquals(before, state())
        assertEquals(listOf(before, null), saves)
    }

    @Test
    fun dryRunChangesNothingMoreOnceTheRadioIsOnAfterTheTestPair() {
        assertStopsWithTheRadioOnAfterImeiWrite(1)
        // Left for Put the router back.
        assertEquals(Imei.random(Serials(123_456), factory), state().imeis)
    }

    @Test
    fun dryRunChangesNothingMoreOnceTheRadioIsOnAfterTheEarlierPair() {
        assertStopsWithTheRadioOnAfterImeiWrite(2)
    }

    /** Turns the radio on after the dry run's [n]th IMEI write, and checks that it stops before its next change. */
    private fun assertStopsWithTheRadioOnAfterImeiWrite(n: Int) {
        var writes = 0
        shell.after = { if ("AT+QPRTPARA=1" in it && ++writes == n) fake.radioStuckOn = true }
        assertStops(Problem.RADIO_ON) { run() }
        assertEquals(listOf("M6"), dryRun.testing)
        assertSafe()
    }

    @Test
    fun dryRunReadsTheRadioAndBothImeis20TimesInARow() {
        val reads = mutableListOf<String>()
        shell.after = { if (dryRun.testing == listOf("P5")) reads += it }
        run()
        assertEquals(20, reads.count { "AT+CFUN?" in it })
        assertEquals(20, reads.count { "AT+EGMR=0,7" in it })
        assertEquals(20, reads.count { "AT+EGMR=0,11" in it })
    }

    @Test
    fun dryRunStopsAtAnotherPairInTheRepeatedReads() {
        // At the last read only, as if another command's answer were mixed into gl_modem's.
        var reads = 0
        shell.after = {
            if (dryRun.testing == listOf("P5") && "AT+EGMR=0,7" in it) {
                when (++reads) {
                    19 -> fake.atAnswers["AT+EGMR=0,7"] = "+EGMR: \"${fake.imei2}\""
                    20 -> fake.atAnswers.remove("AT+EGMR=0,7")
                }
            }
        }
        val before = state()
        assertStops(Problem.UNEXPECTED_ANSWER) { run() }
        assertEquals(listOf("P5"), dryRun.testing)
        assertEquals(before, state())
        assertEquals(listOf(before, null), saves)
    }

    @Test
    fun dryRunStopsWhenSlot2DoesNotSwitch() {
        // GL answers, but slot 2 stays on the eSIM.
        val toTray = """{"location_id":"${FakeRouter.LOCATION_ID}","bus":"cpu","slot":2,"data":{"failover":""" +
            """{"slot_type":[{"type":0,"slot":1},{"type":0,"slot":2}]}}}"""
        fake.answers["ubus -t 30 call cellular.modem set_feature_config '$toTray'"] = "{\"ok\": true}"
        val before = state()
        assertStops(Problem.SLOT_NOT_SWITCHED) { run() }
        assertEquals(listOf("S2", "S3", "U6"), dryRun.testing)
        assertTrue(Progress.TRAY_EMPTY !in progress)
        // Putting the router back stopped at the same switch, before the active slot, so it waits for putBack.
        assertEquals(before.copy(slot2 = Slot2.ESIM, activeSlot = 1), state())
        assertEquals(listOf<DryRunStart?>(before), saves)
    }

    @Test
    fun dryRunStopsWhenDdsIdDoesNotFollowTheActiveSlot() {
        fake.uciAnswers["network.modem_cpu.dds_id"] = "2"
        assertStops(Problem.SLOT_NOT_SWITCHED) { run() }
        assertEquals(listOf("U6", "S3"), dryRun.testing)
        assertTrue(Progress.SLOT1_ACTIVE !in progress)
    }

    @Test
    fun dryRunStopsBeforeItChangesAnythingAtAnActiveSlotItDoesNotKnow() {
        fake.activeSlot = 0
        assertStops(Problem.UNEXPECTED_ANSWER) { run() }
        assertEquals(emptyList<DryRunStart?>(), saves)
        assertEquals(emptyList<String>(), fake.changes)
    }

    @Test
    fun aDryRunThatChangedNothingStopsWithNothingToPutBack() {
        checks = { listOf("✗ A check (D7)\nFailed: one thing") }
        val before = state()
        assertStops(Problem.CHECK_FAILED) { run() }
        assertEquals(emptyList<String>(), fake.changes)
        assertEquals(listOf(before, null), saves)
    }

    @Test
    fun dryRunReloadsTheNetworkAndTheFirewallThoughBothAreInPlace() {
        fake.airplaneMode = true
        Router(fake).installFirewall()
        fake.commands.clear()
        run()
        assertTrue(fake.commands.any { it.endsWith("/etc/init.d/network reload") })
        assertTrue("fw4 reload" in fake.commands)
        assertEquals(Progress.DRY_RUN_PASSED, progress.last())
    }

    @Test
    fun aReloadThatDropsTheCableStopsTheDryRun() {
        val before = state()
        shell.breakAt = { it.endsWith("/etc/init.d/network reload") }
        try {
            run()
            throw AssertionError("The dry run didn't stop")
        } catch (_: IOException) {
        }
        assertEquals(listOf("K8", "D19"), dryRun.testing)
        assertEquals(listOf<DryRunStart?>(before), saves)
    }

    @Test
    fun aDryRunWhoseRadioCameOnLeavesTheRouterToPutBackLater() {
        fake.radioOnAtReload = true
        val before = state()
        assertStops(Problem.RADIO_ON) { run() }
        assertEquals(listOf("K8", "D19"), dryRun.testing)
        // Nothing changed since the radio came on, at the network's reload.
        assertEquals("Enhanced Kill Switch", fake.changes.last().substringBefore(" at "))
        assertEquals(Slot2.ESIM, fake.slot2)
        assertEquals(listOf<DryRunStart?>(before), saves)
        fake.radioOnAtReload = false
        fake.radioStuckOn = false
        dryRun().putBack(saves.last()!!) { saves += it }
        assertEquals(before, state())
        assertNull(saves.last())
    }

    @Test
    fun aDryRunCutOffIsPutBackOnceTheRouterHasStartedAgain() {
        fake.slot2 = Slot2.ESIM
        fake.activeSlot = 1
        val before = state()
        // At the switch back to the eSIM, after the tray.
        shell.breakAt = { fake.slot2 == Slot2.SIM2 && "{\"type\":1,\"slot\":2}" in it }
        try {
            run()
            throw AssertionError("The dry run didn't stop")
        } catch (_: IOException) {
        }
        assertEquals(listOf<DryRunStart?>(before), saves)
        // Unplugged and switched off and on, with airplane mode turned off meanwhile, then connected anew.
        shell.broken = false
        shell.breakAt = { false }
        fake.airplaneMode = false
        fake.boot()
        progress.clear()
        dryRun().putBack(saves.last()!!) { saves += it }
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
    fun putBackWritesTheEarlierImeisBack() {
        val before = state()
        fake.airplaneMode = true
        Router(fake).writeImeis(ImeiPair("356090211234563", "356090211235313"))
        dryRun().putBack(before) { saves += it }
        assertEquals(before, state())
        assertEquals(listOf<DryRunStart?>(null), saves)
        // Saved to the modem's flash.
        fake.boot()
        assertEquals(before, state())
        assertSafe()
    }

    @Test
    fun putBackLeavesARouterAsItWasAlone() {
        val before = state()
        dryRun().putBack(before) { saves += it }
        assertEquals(emptyList<String>(), fake.changes)
        assertEquals(listOf<DryRunStart?>(null), saves)
        assertEquals(Progress.PUT_BACK, progress.last())
    }

    @Test
    fun putBackSwitchesNothingOnceTheRadioIsOnAfterTheImeis() {
        val before = state()
        fake.airplaneMode = true
        Router(fake).writeImeis(ImeiPair("356090211234563", "356090211235313"))
        fake.slot2 = Slot2.ESIM
        shell.after = { if ("AT+QPRTPARA=1" in it) fake.radioStuckOn = true }
        assertStops(Problem.RADIO_ON) { dryRun().putBack(before) { saves += it } }
        assertEquals(Slot2.ESIM, fake.slot2)
        assertEquals(emptyList<DryRunStart?>(), saves)
    }

    @Test
    fun aDryRunStoppedAtTheTrayPutsSlot1BackAsTheActiveSlot() {
        // As on a router new to Churn, with slot 2 on the tray, here with a card in it.
        fake.activeSlot = 1
        fake.trayCard = FakeRouter.Card("8941000000000000015F", "228010000000001")
        val before = state()
        assertStops(Problem.CARD_IN_TRAY) { run() }
        // The tray made slot 2 the active slot, the one change left to put back.
        assertEquals(before, state())
        assertEquals(listOf(before, null), saves)
    }

    @Test
    fun dryRunSwitchesSlot2BackToTheEsimBeforeItPutsTheRouterBack() {
        val settings = mutableListOf(fake.slot2)
        shell.after = { if (fake.slot2 != settings.last()) settings += fake.slot2 }
        run()
        // Step 1, the tray, back to the eSIM, and put back.
        assertEquals(listOf(Slot2.SIM2, Slot2.ESIM, Slot2.SIM2, Slot2.ESIM, Slot2.SIM2), settings)
    }

    /** Gives [values] in turn, as the serials of test pairs. */
    private class Serials(private vararg val values: Int) : SecureRandom() {
        private var next = 0

        override fun nextInt(bound: Int) = values[next++]
    }
}
