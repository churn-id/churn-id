package id.churn

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovalTestTest {

    private val fake = FakeRouter().apply {
        // No profile enabled on the built-in eSIM, as the test needs it.
        esimProfile = null
    }

    /** The connection to [fake], which fails every command once [broken] is set, as when the cable is pulled. */
    private val shell = object : Shell {
        var broken = false

        /** Runs before each command, with the command. */
        var before: (String) -> Unit = {}

        /** Runs after each command that [fake] answered, with the command. */
        var after: (String) -> Unit = {}

        override fun run(command: String, input: ByteArray?, timeoutSeconds: Long): Output {
            before(command)
            if (broken) throw IOException("Broken pipe")
            return fake.run(command, input, timeoutSeconds).also { after(command) }
        }
    }
    private val progress = mutableListOf<Progress>()

    /** The claims that the test's parts named while they reported. */
    private val claims = mutableSetOf<String>()

    /** What the test kept on the phone, after each time it saved. */
    private val saves = mutableListOf<RemovalStart?>()

    private val factory = ImeiPair(fake.imei1, fake.imei2)

    /** The card that a swap gave a pair, recorded in Churn's folder. */
    private val card = Card("8941000000000000015", ImeiPair("356090211234563", "356090211235313"))

    /** The version code of the app that runs the test, whose step 3 recorded the card. */
    private val version = 3

    /** What the test keeps on the phone to set the router up again, as the swap left it. */
    private val kept = RemovalStart(factory, card, card.imeis, firewall = true, connectionCheckOff = true, version)

    private val test = removalTest()

    init {
        // A router set up and swapped as the app does it, with the card taken out again. GL recorded it and an
        // earlier card since setup, as it stores their ICCIDs.
        val router = Router(fake)
        setUpFolder(router, factory, keepIccid = null)
        fake.recordCards("${card.iccid}F", "8941000000000000023F")
        fake.airplaneMode = true
        router.recordCard(card)
        router.writeImeis(card.imeis)
        router.installFirewall()
        router.turnSimConnectionCheckOff()
        router.clearHistory(card.iccid)
        router.recordVersion(version)
        fake.airplaneMode = false
        fake.trayCard = null
        // With both trays empty, GL names no card as the current one.
        fake.modemCpuIccid = null
        fake.changes.clear()
        fake.commands.clear()
    }

    private fun removalTest(): RemovalTest {
        lateinit var made: RemovalTest
        made = RemovalTest(
            Router(shell),
            report = {
                progress += it
                claims += made.testing
            },
            check = { emptyList() },
            setUpFolder = { factory, keepIccid -> setUpFolder(Router(shell), factory, keepIccid) },
            sleep = { fake.clock += it },
            now = { fake.clock },
        )
        return made
    }

    /** As the Controller sets a router up, with stand-ins for the files from the app's assets. */
    private fun setUpFolder(router: Router, factory: ImeiPair, keepIccid: String?) {
        val folder = router.readChurnFolder()
        router.updateChurnFile("README.txt", "Churn's folder\n".toByteArray())
        router.updateChurnFile("restore.sh", "#!/bin/sh\n".toByteArray(), executable = true)
        router.keepChurnFolder()
        if (folder.factory == null) {
            router.writeChurnFile("factory", "${factory.imei1}\n${factory.imei2}\n".toByteArray())
        }
        router.clearHistory(keepIccid, overwrite = false)
    }

    private fun run() = test.run(version) { saves += it }

    /** What the test sets up again, as the router has it now. */
    private fun state(): List<Any?> {
        val router = Router(fake)
        val folder = router.readChurnFolder()
        return listOf(
            folder.factory, folder.card, folder.version, ImeiPair(fake.imei1, fake.imei2), router.firewallInPlace(),
            router.upgradeKeeps(Router.FIREWALL), router.upgradeKeeps("/etc/churn/"), fake.logFile,
            fake.kmwanSimDisabled, fake.cardsInSimMap, fake.cardsInApnHistory, fake.cardsInEsimRecords,
        )
    }

    private fun assertStops(problem: Problem, run: () -> Unit) {
        try {
            run()
        } catch (e: RouterException) {
            assertEquals(problem, e.problem)
            return
        }
        throw AssertionError("The test didn't stop")
    }

    private fun assertSafe() {
        assertEquals(emptyList<String>(), fake.unsafe)
        assertEquals(emptyList<String>(), fake.unlocked)
    }

    @Test
    fun removalTestRunsRestoreShAndSetsTheRouterUpAgain() {
        val before = state()
        run()
        assertTrue("sh /etc/churn/restore.sh" in fake.commands)
        assertEquals(
            listOf(
                Progress.AIRPLANE_MODE_ON, Progress.RADIO_OFF, Progress.SLOT2_ON_ESIM, Progress.NO_PROFILE,
                Progress.SIM1_EMPTY, Progress.RESTORED, Progress.FACTORY_IMEIS, Progress.FOLDER_GONE,
                Progress.LOG_SETTING_BACK, Progress.LOG_WRITTEN, Progress.ZERO_FILES_GONE,
                Progress.CONNECTION_CHECK_BACK, Progress.FIREWALL_GONE, Progress.SET_UP_AGAIN,
                Progress.CLEARING_HISTORY, Progress.CONNECTION_KEPT, Progress.RESERVE_BACK, Progress.CARD_FILES_PARSE,
                Progress.ZEROS_KEEP_SPACE, Progress.READING_PARTITION, Progress.IMSIS_GONE, Progress.ICCIDS_GONE,
                Progress.CLEARING_HISTORY, Progress.FREE_SPACE_FILLED, Progress.CLEARED_QUICKLY,
                Progress.REMOVAL_PASSED,
            ),
            progress,
        )
        assertEquals(before, state())
        // Churn's copy of GL's setting for its connection check too, which restore.sh deleted with the folder.
        assertEquals("0\n", fake.files["/etc/churn/kmwan"]?.toString(Charsets.UTF_8))
        // Kept before restore.sh, and forgotten once the router is set up again.
        assertEquals(listOf(kept, null), saves)
        // The clearing made the zero files again, which restore.sh deleted.
        assertEquals("/overlay/churn-zeros", fake.zerosLink)
        assertTrue(fake.zeroFilesKb > 0)
        // As restore.sh leaves it.
        assertTrue(fake.airplaneMode)
        assertSafe()
    }

    @Test
    fun removalTestPutsThisVersionsRulesInPlaceOfAnEarlierVersions() {
        // As on a router whose last swap an earlier version of the app did, with rules that counted no packets.
        Router(fake).recordVersion(version - 1)
        fake.files[Router.FIREWALL] = Router.firewallRules(453).replace(" counter", "").toByteArray()
        run()
        // As the next swap's step 3 would.
        assertTrue(Router(fake).firewallInPlace())
        assertEquals(version - 1, Router(fake).readChurnFolder().version)
        assertEquals(kept.copy(version = version - 1), saves.first())
    }

    @Test
    fun removalTestSetsARouterWithoutARecordedCardUpAgain() {
        fake.files.remove("/etc/churn/card")
        fake.files.remove("/etc/churn/version")
        // Whose history setup keeps.
        fake.modemCpuIccid = "${card.iccid}F"
        fake.airplaneMode = true
        Router(fake).writeImeis(factory)
        fake.files.remove(Router.FIREWALL)
        fake.loadedChains.clear()
        fake.sysupgrade = fake.sysupgrade.lines().filter { it != Router.FIREWALL }.joinToString("\n")
        // GL's connection check as GL had it too.
        fake.files.remove("/etc/churn/kmwan")
        fake.kmwanSimDisabled = "0"
        val before = state()
        run()
        assertEquals(before, state())
        assertEquals(
            listOf(
                RemovalStart(
                    factory, card = null, factory, firewall = false, connectionCheckOff = false, version = null,
                ),
                null,
            ),
            saves,
        )
        assertEquals(Progress.REMOVAL_PASSED, progress.last())
    }

    @Test
    fun theFakeRouterPrintsRestoreShsLastLine() {
        // The script in the app's assets, from android/app, where Gradle runs the tests. Its last line names the
        // factory IMEIs it read.
        val script = File("src/main/assets/restore.sh").readText().trimEnd().lines().last()
        val output = fake.run("sh /etc/churn/restore.sh", input = null, timeoutSeconds = 120).text.trim()
        val expected = script.removePrefix("echo \"").removeSuffix("\"")
            .replace("\$imei1", factory.imei1).replace("\$imei2", factory.imei2)
        assertEquals(expected, output)
        assertTrue(output.endsWith(RemovalTest.LAST_LINE_END))
    }

    @Test
    fun removalTestStopsWhenRestoreShEndsBeforeItsLastLine() {
        fake.restoreEndsEarly = true
        assertStopsAfterRestoreSh("F12")
    }

    @Test
    fun removalTestReadsTheFactoryImeisAfterRestoreSh() {
        val reads = mutableListOf<String>()
        shell.after = { if (test.testing == listOf("F7")) reads += it }
        run()
        assertTrue(reads.any { "AT+EGMR=0,7" in it } && reads.any { "AT+EGMR=0,11" in it })
        assertTrue(reads.any { "AT+CFUN?" in it })
    }

    @Test
    fun removalTestStopsWhenRestoreShLeavesOtherImeis() {
        fake.restoreKeepsImeis = true
        assertStopsAfterRestoreSh("F7")
    }

    @Test
    fun removalTestStopsWhenTheRadioIsOnAfterRestoreSh() {
        shell.after = { if (it == "sh /etc/churn/restore.sh") fake.radioStuckOn = true }
        assertStops(Problem.RADIO_ON) { run() }
        assertEquals(listOf("F7"), test.testing)
        // Left for Set the router up again.
        assertEquals(listOf(kept), saves)
        assertSafe()
    }

    @Test
    fun removalTestStopsWhenRestoreShLeavesChurnsFolder() {
        fake.restoreKeepsFolder = true
        assertStopsAfterRestoreSh("F8", "F12")
    }

    @Test
    fun removalTestStopsWhenRestoreShLeavesTheFolderInTheUpgrade() {
        fake.restoreKeepsFolderLine = true
        assertStopsAfterRestoreSh("F8", "F12")
    }

    @Test
    fun removalTestStopsWhenRestoreShLeavesGlsLogOff() {
        fake.restoreKeepsLogOff = true
        assertStopsAfterRestoreSh("F9")
    }

    @Test
    fun removalTestStopsWhenNoLogreadWritesGlsLogAfterRestoreSh() {
        fake.logreadMissing = true
        // A file that no logread writes.
        fake.files["/etc/sys_log"] = "old\n".toByteArray()
        assertStopsAfterRestoreSh("H3")
    }

    @Test
    fun removalTestStopsWhenLogreadOpensNoFileAfterRestoreSh() {
        fake.logreadOpensNoFile = true
        assertStopsAfterRestoreSh("H3")
    }

    @Test
    fun removalTestSkipsH3WithGlsLogOffBeforeChurn() {
        fake.files.remove("/etc/churn/log_file")
        run()
        assertTrue(Progress.LOG_WRITTEN !in progress)
        assertEquals(Progress.REMOVAL_PASSED, progress.last())
    }

    @Test
    fun removalTestStopsWhenRestoreShLeavesTheZeroFiles() {
        fake.restoreKeepsZeroFiles = true
        assertStopsAfterRestoreSh("F10")
    }

    @Test
    fun removalTestStopsWhenRestoreShLeavesFillFiles() {
        // The small files of a clearing that stopped, which take less than the 16 MB that the check allows for.
        fake.fillFilesKb = 1_200
        fake.restoreKeepsFillFiles = true
        assertStopsAfterRestoreSh("F10")
    }

    @Test
    fun removalTestStopsWhenTheZeroFilesKeepTheirSpace() {
        fake.zeroFilesHeldOpen = true
        assertStopsAfterRestoreSh("F10")
    }

    @Test
    fun removalTestStopsWhenRestoreShLeavesGlsConnectionCheckOff() {
        fake.restoreKeepsConnectionCheckOff = true
        assertStopsAfterRestoreSh("F15")
    }

    @Test
    fun removalTestExpectsNoConnectionCheckSettingWhereGlHadNone() {
        // As if GL had no setting for it before Churn's step 3, whose copy is then an empty line.
        val router = Router(fake)
        fake.files.remove("/etc/churn/kmwan")
        fake.kmwanSimDisabled = null
        fake.airplaneMode = true
        router.turnSimConnectionCheckOff()
        fake.airplaneMode = false
        assertEquals("", router.kmwanCopy())
        val reads = mutableListOf<String?>()
        shell.after = { if (it == "sh /etc/churn/restore.sh") reads += fake.kmwanSimDisabled }
        run()
        assertEquals(listOf<String?>(null), reads)
        assertEquals(Progress.REMOVAL_PASSED, progress.last())
        assertEquals("1", fake.kmwanSimDisabled)
        assertEquals("", router.kmwanCopy())
    }

    @Test
    fun removalTestStopsWhenRestoreShLeavesChurnsChains() {
        fake.restoreKeepsChains = true
        assertStopsAfterRestoreSh("F11")
    }

    @Test
    fun removalTestStopsWhenRestoreShLeavesTheFirewallFile() {
        fake.restoreKeepsFirewallFile = true
        assertStopsAfterRestoreSh("F11")
    }

    @Test
    fun removalTestStopsWhenRestoreShLeavesTheFirewallInTheUpgrade() {
        fake.restoreKeepsFirewallLine = true
        assertStopsAfterRestoreSh("F11")
    }

    @Test
    fun removalTestPassesWithoutZeroFiles() {
        // As before the first clearing with an overwrite.
        fake.zeroFilesKb = 0
        fake.zerosLink = null
        run()
        assertEquals(Progress.REMOVAL_PASSED, progress.last())
    }

    @Test
    fun removalTestLeavesGlsLogAsItIsWithoutChurnsCopy() {
        // As if GL's log had been off before Churn and turned on since.
        fake.files.remove("/etc/churn/log_file")
        fake.logFile = "/etc/sys_log"
        run()
        assertEquals(Progress.REMOVAL_PASSED, progress.last())
        // Set up again as setup does it, which turns the log off and keeps a copy.
        assertNull(fake.logFile)
        assertEquals("/etc/sys_log", Router(fake).logFileCopy())
    }

    /**
     * Runs the test with a restore.sh or a firmware broken to fail the check of [ids], and checks that it stops there
     * and sets the router up again at once, since the radio is still off.
     */
    private fun assertStopsAfterRestoreSh(vararg ids: String) {
        val before = state()
        assertStops(Problem.UNEXPECTED_ANSWER) { run() }
        assertEquals(ids.toList(), test.testing)
        assertEquals(before, state())
        assertEquals(listOf(kept, null), saves)
        assertEquals(Progress.SET_UP_AGAIN, progress.last())
        assertSafe()
    }

    @Test
    fun removalTestStopsWhenTheConnectionClosesDuringTheClearing() {
        val before = state()
        // The overwrite, which writes the zeros.
        shell.before = { if ("dd if=/dev/zero" in it) shell.broken = true }
        try {
            run()
            throw AssertionError("The test didn't stop")
        } catch (_: IOException) {
        }
        assertEquals(listOf("H17"), test.testing)
        // Set up again before the clearing, so nothing is left to do.
        assertEquals(listOf(kept, null), saves)
        assertEquals(before, state())
        assertSafe()
    }

    @Test
    fun removalTestStopsWithoutExt4sReserveToCompare() {
        // An overlay without ext4's reserved_clusters file, which H12's check stops swaps for.
        fake.overlayType = "f2fs"
        assertStopsAfterTheClearing("H13")
    }

    @Test
    fun removalTestStopsWhenTheClearingLeavesTheReserveOff() {
        fake.overwriteLeavesReserveOff = true
        assertStopsAfterTheClearing("H13")
    }

    @Test
    fun removalTestStopsWhenACardFileNoLongerParsesAfterTheClearing() {
        fake.overwriteCutsSimMap = true
        assertStops(Problem.UNEXPECTED_ANSWER) { run() }
        assertEquals(listOf("H14"), test.testing)
        assertEquals(listOf(kept, null), saves)
        assertSafe()
    }

    @Test
    fun removalTestStopsWhenTheClearingLeavesMoreThan512MbFree() {
        // As if it had deleted 8 zero files too many.
        fake.overwriteKeepsFreeKb = 1_048_576
        assertStopsAfterTheClearing("H15")
    }

    @Test
    fun removalTestStopsWhenTheClearingLeavesLessThan512MbFree() {
        fake.overwriteKeepsFreeKb = 500_000
        assertStopsAfterTheClearing("H15")
    }

    @Test
    fun removalTestStopsWhenTheClearingLeavesFillFiles() {
        fake.overwriteLeavesFillFiles = true
        assertStopsAfterTheClearing("H15")
    }

    @Test
    fun removalTestCountsTheIccidsAndImsisOfGlsFilesAndLogs() {
        run()
        // The card's and the earlier card's from GL's log, which logread wrote to the flash after restore.sh, and the
        // card's ICCID from GL's card files, but not the IMEIs in the log.
        assertEquals(
            listOf("228010000000001", "228010000000002", "8941000000000000015", "8941000000000000023"),
            fake.numbersCounted,
        )
        // Only as input, which the debug log leaves out.
        for (number in fake.numbersCounted) assertTrue(fake.commands.none { number in it })
    }

    @Test
    fun removalTestCountsTheNumbersOfTheLogsOlderFileButNoOtherRuns() {
        // logread moves the file to .old once it reaches the log's size.
        fake.files["/etc/sys_log.old"] =
            "user.info Diag_Lib[1]: iccid[8941000000000000031F] imsi[228010000000003]\n".toByteArray()
        // Neither a run of 15 zeros, which no IMSI is, nor one of 19 digits that doesn't begin with an ICCID's 89.
        fake.logBuffer += "user.info test[1]: 000000000000000 1762412345123456789\n"
        run()
        assertEquals(
            listOf(
                "228010000000001", "228010000000002", "228010000000003", "8941000000000000015", "8941000000000000023",
                "8941000000000000031",
            ),
            fake.numbersCounted,
        )
        assertEquals(Progress.REMOVAL_PASSED, progress.last())
    }

    @Test
    fun removalTestStopsWhenTheConnectionClosesWhileReadingTheCardNumbers() {
        // Right after the checks of what restore.sh did.
        shell.before = { if (Progress.FIREWALL_GONE in progress) shell.broken = true }
        try {
            run()
            throw AssertionError("The test didn't stop")
        } catch (_: IOException) {
        }
        assertEquals(listOf("H10", "H11"), test.testing)
        // Left to Set the router up again.
        assertEquals(listOf(kept), saves)
        assertSafe()
    }

    @Test
    fun removalTestStopsWhenAnImsiIsLeftOnTheRawPartition() {
        // As if the overwrite had missed the blocks of a deleted file.
        shell.after = { if ("dd if=/dev/zero" in it) fake.freeSpace += "imsi[228010000000002]".toByteArray() }
        assertStopsAfterTheClearing("H10")
    }

    @Test
    fun removalTestStopsWhenAnIccidIsLeftOnTheRawPartition() {
        shell.after = { if ("dd if=/dev/zero" in it) fake.freeSpace += "iccid[8941000000000000023F]".toByteArray() }
        assertStopsAfterTheClearing("H11")
    }

    @Test
    fun removalTestStopsWhenTheRawPartitionHoldsNoneOfTheNumbersInItsFiles() {
        // Which would leave every number that the clearing deleted uncounted.
        fake.rawReadFindsNothing = true
        assertStopsAfterTheClearing("H10", "H11")
    }

    @Test
    fun removalTestCountsOnlyTheNumbersThatNoFileHoldsAnyMore() {
        // The card's ICCID, which GL's card files and Churn's record hold, is on the raw partition in old copies too.
        shell.after = { if ("dd if=/dev/zero" in it) fake.freeSpace += "iccid[8941000000000000015F]".toByteArray() }
        run()
        assertTrue(Progress.ICCIDS_GONE in progress)
    }

    @Test
    fun removalTestLeavesH10AndH11UnconfirmedWithoutDeletedNumbers() {
        // GL's log named no card, so only the card's ICCID, which the card files hold, is there to count.
        fake.logBuffer = ""
        run()
        assertEquals(listOf("8941000000000000015"), fake.numbersCounted)
        assertTrue(Progress.NO_IMSIS in progress && Progress.NO_ICCIDS in progress)
        assertTrue(Progress.IMSIS_GONE !in progress && Progress.ICCIDS_GONE !in progress)
        assertEquals(Progress.REMOVAL_PASSED, progress.last())
    }

    @Test
    fun removalTestReadsThePartitionOnlyWithNumbersToCount() {
        fake.logBuffer = ""
        fake.files.keys.filter { it.startsWith("/etc/config/cellular/") || it.startsWith("/root/esim/") }
            .forEach { fake.files.remove(it) }
        run()
        assertTrue(fake.commands.none { it.startsWith("t=/tmp/churn-numbers") })
        assertTrue(Progress.READING_PARTITION !in progress)
        assertTrue(Progress.NO_IMSIS in progress && Progress.NO_ICCIDS in progress)
    }

    @Test
    fun removalTestLeavesH10AndH11UnconfirmedWhenNoFileHoldsANumberToCheckTheReadWith() {
        // A router never swapped since setup, so no card is recorded, and with both trays empty GL names none: the
        // clearing keeps no card's numbers.
        fake.files.remove("/etc/churn/card")
        run()
        assertTrue(fake.numbersCounted.isNotEmpty())
        assertTrue(Progress.PARTITION_UNCHECKED in progress)
        val results = listOf(Progress.IMSIS_GONE, Progress.NO_IMSIS, Progress.ICCIDS_GONE, Progress.NO_ICCIDS)
        assertTrue(progress.none { it in results })
        assertEquals(Progress.REMOVAL_PASSED, progress.last())
    }

    @Test
    fun removalTestStopsWhenClearingAgainWritesMoreThanTheFreeSpace() {
        // As if each overwrite wrote the zero files anew.
        shell.after = { if ("dd if=/dev/zero" in it) fake.lifetimeWrittenKb += 2_000_000 }
        assertStopsAfterTheClearing("H15")
        assertTrue(Progress.ICCIDS_GONE in progress)
    }

    @Test
    fun removalTestStopsWhenClearingAgainWritesLessThanTheFreeSpace() {
        // As if each overwrite left 100 MB of the free space as it was.
        shell.after = { if ("dd if=/dev/zero" in it) fake.lifetimeWrittenKb -= 102_400 }
        assertStopsAfterTheClearing("H15")
        assertTrue(Progress.ICCIDS_GONE in progress)
    }

    @Test
    fun removalTestStopsWhenTheConnectionClosesDuringTheSecondClearing() {
        shell.before = { if ("dd if=/dev/zero" in it && Progress.ICCIDS_GONE in progress) shell.broken = true }
        try {
            run()
            throw AssertionError("The test didn't stop")
        } catch (_: IOException) {
        }
        assertEquals(listOf("H15", "H16"), test.testing)
        assertEquals(listOf(kept, null), saves)
        assertSafe()
    }

    @Test
    fun removalTestStopsWhenClearingAgainTakesAMinute() {
        // 512 MB at 8 MB/s take 64 s.
        fake.flashWriteKbPerSecond = 8_192
        assertStopsAfterTheClearing("H16")
    }

    /**
     * Runs the test with a clearing or a firmware broken to fail the check of [ids], and checks that it stops there,
     * with the router set up again before the clearing.
     */
    private fun assertStopsAfterTheClearing(vararg ids: String) {
        val before = state()
        assertStops(Problem.UNEXPECTED_ANSWER) { run() }
        assertEquals(ids.toList(), test.testing)
        assertEquals(before, state())
        assertEquals(listOf(kept, null), saves)
        assertTrue(Progress.REMOVAL_PASSED !in progress)
        assertSafe()
    }

    @Test
    fun removalTestConfirmsTier2ClaimsOfTestingMd() {
        run()
        assertEquals(
            setOf(
                "F7", "F8", "F9", "F10", "F11", "F12", "F15", "H3", "H10", "H11", "H13", "H14", "H15", "H16", "H17",
            ),
            claims,
        )
        for (id in claims) {
            val tiers = ChecksTest.TIERS[id] ?: throw AssertionError("$id isn't a claim in router-tests.md")
            assertTrue("$id isn't a tier 2 claim in router-tests.md", "2" in tiers)
        }
    }

    @Test
    fun removalTestNeedsARouterSetUp() {
        fake.files.keys.removeAll { it.startsWith("/etc/churn/") }
        assertStops(Problem.NOT_SET_UP) { run() }
        assertEquals(emptyList<String>(), fake.changes)
    }

    @Test
    fun removalTestStopsAtAnEnabledProfileBeforeRestoreSh() {
        fake.esimProfile = "8944000000000000123F"
        val before = state()
        assertStops(Problem.PROFILE_ENABLED) { run() }
        assertEquals(before, state())
        assertEquals(emptyList<RemovalStart?>(), saves)
        assertTrue("sh /etc/churn/restore.sh" !in fake.commands)
    }

    @Test
    fun removalTestStopsAtACardInTheSim1TrayBeforeRestoreSh() {
        fake.tray1Card = FakeRouter.Card("8941000000000000023F", "228010000000002")
        assertStops(Problem.SIM1_CARD) { run() }
        assertEquals(emptyList<RemovalStart?>(), saves)
        assertTrue("sh /etc/churn/restore.sh" !in fake.commands)
    }

    @Test
    fun aRemovalTestThatStoppedSetsTheRouterUpAgainAtOnce() {
        fake.failing += "sh /etc/churn/restore.sh"
        val before = state()
        assertStops(Problem.UNEXPECTED_ANSWER) { run() }
        assertEquals(listOf("F12"), test.testing)
        assertEquals(before, state())
        assertEquals(listOf(kept, null), saves)
        assertEquals(Progress.SET_UP_AGAIN, progress.last())
        assertSafe()
    }

    @Test
    fun aRemovalTestWhoseRadioCameOnLeavesTheRouterToSetUpAgainLater() {
        val before = state()
        // Once the test kept what setting the router up again takes, so that restore.sh finds the radio on.
        assertStops(Problem.UNEXPECTED_ANSWER) {
            test.run(version) {
                saves += it
                fake.radioStuckOn = true
            }
        }
        assertTrue("sh /etc/churn/restore.sh" in fake.commands)
        assertEquals(listOf(kept), saves)
        fake.radioStuckOn = false
        removalTest().setUpAgain(saves.last()!!) { saves += it }
        assertEquals(before, state())
        assertNull(saves.last())
        assertSafe()
    }

    @Test
    fun aRemovalTestChangesNothingMoreOnceTheRadioIsOnAfterTheImeis() {
        shell.after = { if ("AT+QPRTPARA=1" in it) fake.radioStuckOn = true }
        assertStops(Problem.RADIO_ON) { run() }
        // Written back, but the firewall rules wait for Set the router up again.
        assertEquals(card.imeis, ImeiPair(fake.imei1, fake.imei2))
        assertTrue(!Router(fake).firewallInPlace())
        assertEquals(listOf(kept), saves)
        assertSafe()
    }

    @Test
    fun aRemovalTestCutOffIsSetUpAgainOnceTheRouterHasStarted() {
        val before = state()
        shell.after = { if (it == "sh /etc/churn/restore.sh") shell.broken = true }
        try {
            run()
            throw AssertionError("The test didn't stop")
        } catch (_: IOException) {
        }
        // restore.sh ran to its end: the factory IMEIs are back, and Churn's folder is gone.
        assertEquals(factory, ImeiPair(fake.imei1, fake.imei2))
        assertEquals(listOf(kept), saves)
        // Unplugged and switched off and on, then connected anew.
        shell.broken = false
        shell.after = {}
        fake.boot()
        progress.clear()
        removalTest().setUpAgain(saves.last()!!) { saves += it }
        assertEquals(before, state())
        assertNull(saves.last())
        assertEquals(
            listOf(Progress.AIRPLANE_MODE_ON, Progress.WAITING_FOR_START, Progress.RADIO_OFF, Progress.SET_UP_AGAIN),
            progress,
        )
        assertTrue(fake.uptime >= 120)
        assertSafe()
    }
}
