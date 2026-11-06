package id.churn

/**
 * Tier 2's removal test (docs/testing.md, "Removal and clearing"): restore.sh run as Remove Churn runs it, the router
 * then set up again as it was, with the card's record, the IMEIs it had and Churn's firewall rules, and the SIM history
 * cleared twice as step 3 clears it. restore.sh deletes the zero files, so the first clearing fills all of the flash's
 * free space: the test runs once for each firmware. Only debug builds have it.
 */
class RemovalTest(
    router: Router,
    report: (Progress) -> Unit,
    check: () -> List<String>,
    /** Sets the router up as setup does, with the factory IMEIs, keeping the SIM history of the card with the ICCID. */
    private val setUpFolder: (ImeiPair, String?) -> Unit,
    sleep: (Long) -> Unit = Thread::sleep,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : Swap(router, report, ask = { error("The removal test asks nothing") }, check, sleep, now) {

    /** The IDs of the claims that the part of the test under way confirms, which a failure names. */
    var testing = emptyList<String>()
        private set

    /**
     * Runs the test. [save] gets what setting the router up again takes, before restore.sh runs, to keep on the phone,
     * and null once the router is set up again. A test that stops sets the router up again if the radio is still off,
     * and otherwise leaves that to [setUpAgain].
     */
    fun run(save: (RemovalStart?) -> Unit) {
        val folder = router.readChurnFolder()
        val factory = folder.factory ?: throw RouterException(Problem.NOT_SET_UP)
        // As in the dry run: airplane mode on, the radio off, and slot 2 on the built-in eSIM with no profile enabled,
        // so that a power-on before the router is set up again registers nowhere.
        prepare(disableProfile = false)
        if (router.cardInSim1Tray()) throw RouterException(Problem.SIM1_CARD)
        report(Progress.SIM1_EMPTY)
        val start = RemovalStart(factory, folder.card, router.imeis(), router.firewallInPlace())
        // GL's setting for its log's file as restore.sh leaves it: Churn's copy, or without one, as it is.
        val logFile = router.logFileCopy() ?: router.setting(Router.LOG_FILE)
        val zeros = router.zeroFiles()
        val free = router.overlayFreeKb()
        save(start)
        var numbers = emptyList<String>()
        try {
            part("F12") {
                if (!router.restoreFactoryImeis().endsWith(LAST_LINE_END)) {
                    throw RouterException(Problem.UNEXPECTED_ANSWER, "restore.sh: its last line is missing")
                }
                report(Progress.RESTORED)
            }
            // Saved too, which only a reboot shows (tier 3).
            part("F7") {
                if (router.imeis() != start.factory) {
                    throw RouterException(Problem.UNEXPECTED_ANSWER, "AT+EGMR=0: other IMEIs after restore.sh")
                }
                checkRadioOff()
                report(Progress.FACTORY_IMEIS)
            }
            // With the folder, Churn's record of the card is gone too.
            part("F8", "F12") {
                if (router.churnFolderListing() != null) {
                    throw RouterException(Problem.UNEXPECTED_ANSWER, "${Router.CHURN} is still there after restore.sh")
                }
                if (router.upgradeKeeps("${Router.CHURN}/")) {
                    throw RouterException(Problem.UNEXPECTED_ANSWER, "/etc/sysupgrade.conf still lists Churn's folder")
                }
                report(Progress.FOLDER_GONE)
            }
            part("F9") {
                if (router.setting(Router.LOG_FILE) != logFile) {
                    throw RouterException(Problem.UNEXPECTED_ANSWER, "${Router.LOG_FILE} isn't as before Churn")
                }
                report(Progress.LOG_SETTING_BACK)
            }
            // Where restore.sh leaves GL's log on. logread opens the file as the log service starts it, and the app
            // deleted it at the last clearing.
            if (logFile != null) {
                part("H3") {
                    if (flashLogWritten(router) != CheckResult.Passed || !router.exists(logFile)) {
                        throw RouterException(Problem.UNEXPECTED_ANSWER, "logread doesn't write $logFile")
                    }
                    report(Progress.LOG_WRITTEN)
                }
            }
            // With the folder, the link to the zero files is gone too.
            part("F10") {
                if (router.zeroFiles().count != 0) {
                    throw RouterException(Problem.UNEXPECTED_ANSWER, "/overlay still has zero files after restore.sh")
                }
                // Less what GL and its log wrote since.
                if (router.overlayFreeKb() < free + zeros.kb - FREE_SLACK_KB) {
                    throw RouterException(Problem.UNEXPECTED_ANSWER, "The zero files' space isn't free again")
                }
                report(Progress.ZERO_FILES_GONE)
            }
            part("F11") {
                val left = listOfNotNull(
                    router.churnChains().takeIf { it.isNotEmpty() }?.joinToString(" "),
                    Router.FIREWALL.takeIf { router.firewallFile() != null },
                    "its line in /etc/sysupgrade.conf".takeIf { router.upgradeKeeps(Router.FIREWALL) },
                )
                if (left.isNotEmpty()) {
                    throw RouterException(Problem.UNEXPECTED_ANSWER, "Churn's rules left: ${left.joinToString(", ")}")
                }
                report(Progress.FIREWALL_GONE)
            }
            // Before setting the router up again deletes the log's file, with what logread wrote since restore.sh. Kept
            // only in memory.
            part("H10", "H11") { numbers = cardNumbers(start, logFile) }
            part { setUp(start) }
        } catch (e: Exception) {
            // As far as the router lets it: one that doesn't answer, or whose radio isn't off, waits for setUpAgain.
            runCatching {
                checkRadioOff()
                setUp(start)
                save(null)
                report(Progress.SET_UP_AGAIN)
            }
            throw e
        }
        testing = emptyList()
        save(null)
        report(Progress.SET_UP_AGAIN)
        clear(start, numbers)
        report(Progress.REMOVAL_PASSED)
    }

    /**
     * Sets the router up again as it was before a test that stopped, [start], once the router has started and its radio
     * is off: it may have been switched off and on since. [save] then gets null.
     */
    fun setUpAgain(start: RemovalStart, save: (RemovalStart?) -> Unit) {
        turnAirplaneModeOn()
        waitForStart()
        waitForRadioOff()
        setUp(start)
        save(null)
        report(Progress.SET_UP_AGAIN)
    }

    /**
     * Clears the SIM history as step 3 does, with the router set up again as [start] says it was. restore.sh deleted
     * the zero files, so the overwrite fills all of the free space, about 2.9 GB, and takes minutes. Afterwards, those
     * of [numbers] that no file holds any more must be nowhere on the partition.
     */
    private fun clear(start: RemovalStart, numbers: List<String>) {
        val reserve = router.overlay()?.reserve
        // The overwrite prints nothing until it ends, and a connection that closes meanwhile fails the command.
        part("H17") {
            report(Progress.CLEARING_HISTORY)
            router.clearHistory(keep(start))
            report(Progress.CONNECTION_KEPT)
        }
        // ext4's reserve, which the overwrite sets to 0 while it fills the partition.
        part("H13") {
            val after = router.overlay()?.reserve
            if (reserve == null || after != reserve) {
                throw RouterException(
                    Problem.UNEXPECTED_ANSWER,
                    "reserved_clusters is $after after the clearing, $reserve before it",
                )
            }
            report(Progress.RESERVE_BACK)
        }
        // GL may save them while the overwrite fills the partition, and a save that finds it full could cut one short.
        part("H14") {
            val broken = router.cardFiles().values.count { it == null }
            if (broken > 0) {
                throw RouterException(Problem.UNEXPECTED_ANSWER, "$broken of GL's card files don't parse any more")
            }
            report(Progress.CARD_FILES_PARSE)
        }
        // The overwrite deletes zero files, 64 MB each, until 512 MB are free, of which GL may have taken some since.
        part("H15") {
            val free = router.overlayFreeKb()
            if (free !in KEPT_FREE_KB - FREE_SLACK_KB until KEPT_FREE_KB + ZERO_FILE_KB) {
                throw RouterException(Problem.UNEXPECTED_ANSWER, "${free / 1024} MB are free after the clearing")
            }
            // Their folder, with no fill files left: what isn't free any more went into the zero files.
            if (router.zeroFiles().count != 1) {
                throw RouterException(Problem.UNEXPECTED_ANSWER, "No folder of zero files, or fill files are left")
            }
            report(Progress.ZEROS_KEEP_SPACE)
        }
        // The numbers that the clearing deleted, with their counts; null if the read of the partition can't be checked.
        var deleted: List<Pair<String, NumberCount>>? = emptyList()
        part("H10", "H11") {
            if (numbers.isNotEmpty()) {
                report(Progress.READING_PARTITION)
                val counts = router.countOnPartition(numbers)
                // The numbers that files hold are on the raw partition too, so a read that finds none of them failed.
                // If files hold none, as when no card is recorded and GL names none, a failed read can't be told.
                val held = counts.filter { it.files > 0 }
                if (held.isEmpty()) {
                    deleted = null
                    report(Progress.PARTITION_UNCHECKED)
                } else if (held.none { it.raw > 0 }) {
                    throw RouterException(
                        Problem.UNEXPECTED_ANSWER,
                        "The raw partition holds none of the ${held.size} numbers that its files hold",
                    )
                } else {
                    // Only the numbers that the clearing deleted count: GL rewrites the files that name the card in
                    // use, so old copies of those can be anywhere in the free space.
                    deleted = numbers.zip(counts).filter { (_, count) -> count.files == 0 }
                }
            }
        }
        deleted?.let { checked ->
            val (imsis, iccids) = checked.partition { (number, _) -> number.length == IMSI_LENGTH }
            part("H10") { checkGone(imsis, "IMSIs", Progress.IMSIS_GONE, Progress.NO_IMSIS) }
            part("H11") { checkGone(iccids, "ICCIDs", Progress.ICCIDS_GONE, Progress.NO_ICCIDS) }
        }
        clearAgain(start)
        testing = emptyList()
    }

    /**
     * Clears the SIM history again, now with the zero files in place, as at every swap after the first: the overwrite
     * fills only the free space, about 512 MB, and so takes under a minute.
     */
    private fun clearAgain(start: RemovalStart) {
        var free = 0L
        var written = 0L
        var millis = 0L
        part("H15", "H16") {
            free = router.overlayFreeKb()
            val before = router.overlayWrittenKb()
            report(Progress.CLEARING_HISTORY)
            val started = now()
            router.clearHistory(keep(start))
            millis = now() - started
            written = router.overlayWrittenKb() - before
        }
        // All of the free space and what ext4 writes to keep track of it, but not the zero files' space again.
        part("H15") {
            if (written !in free - FREE_SLACK_KB..free + WRITE_SLACK_KB) {
                throw RouterException(
                    Problem.UNEXPECTED_ANSWER,
                    "Clearing again wrote ${written / 1024} MB, with ${free / 1024} MB free",
                )
            }
            report(Progress.FREE_SPACE_FILLED)
        }
        part("H16") {
            if (millis >= MAX_CLEARING_MILLIS) {
                throw RouterException(Problem.UNEXPECTED_ANSWER, "Clearing again took ${millis / 1000} s")
            }
            report(Progress.CLEARED_QUICKLY)
        }
    }

    /**
     * Checks that the raw partition holds no copy of the [kind] that the clearing [deleted], with how often it holds
     * each, and reports [gone], or [none] if there were none to look for.
     */
    private fun checkGone(deleted: List<Pair<String, NumberCount>>, kind: String, gone: Progress, none: Progress) {
        if (deleted.isEmpty()) return report(none)
        val left = deleted.map { (_, count) -> count.raw }.filter { it > 0 }
        if (left.isNotEmpty()) {
            throw RouterException(
                Problem.UNEXPECTED_ANSWER,
                "The raw partition still holds ${left.size} of the ${deleted.size} $kind deleted, ${left.sum()} copies",
            )
        }
        report(gone)
    }

    /**
     * The ICCIDs and IMSIs that GL's card files and logs name, with [logFile] the system log's file: IMEIs, which have
     * the TAC of the factory IMEIs or of those in [start], are left out.
     */
    private fun cardNumbers(start: RemovalStart, logFile: String?): List<String> {
        val tacs = listOf(start.factory, start.imeis).flatMap { listOf(it.imei1, it.imei2) }.map { it.take(8) }.toSet()
        return router.numbersInCardFilesAndLogs(logFile).filter { number ->
            // ICCIDs begin with 89, for telecommunications. MCCs, which IMSIs begin with, start at 001.
            if (number.length == IMSI_LENGTH) number.take(8) !in tacs && !number.startsWith("000")
            else number.startsWith("89")
        }.sorted()
    }

    /** Runs [part] of the test, which confirms the claims with the IDs [ids]. */
    private fun part(vararg ids: String, part: () -> Unit) {
        testing = ids.toList()
        part()
    }

    /** Sets the router up as [start] says it was, with the radio off, as it must be already. */
    private fun setUp(start: RemovalStart) {
        setUpFolder(start.factory, keep(start))
        start.card?.let(router::recordCard)
        if (router.imeis() != start.imeis) {
            router.writeImeis(start.imeis)
            checkRadioOff()
        }
        if (start.firewall && !router.firewallInPlace()) router.installFirewall()
    }

    /**
     * The ICCID of the card whose SIM history setting the router up again and the clearing keep, as setup keeps the
     * current card's: the recorded card's, since with both trays empty GL may name no card as the current one, or else
     * the card GL names.
     */
    private fun keep(start: RemovalStart) = start.card?.iccid ?: router.currentCardIccid()

    companion object {
        /** Debug builds have the removal test. */
        const val AVAILABLE = true

        /** How much less free space than the zero files took the overlay may have after restore.sh: 16 MB. */
        private const val FREE_SLACK_KB = 16_384

        /** The free space that the overwrite leaves, in kB: 512 MB. */
        private const val KEPT_FREE_KB = 524_288

        /** The size of each zero file, in kB: 64 MB. */
        private const val ZERO_FILE_KB = 65_536

        /** What ext4 writes besides the data at most, for its journal and the fill files' metadata, in kB: 64 MB. */
        private const val WRITE_SLACK_KB = 65_536

        /** How long a clearing with the zero files in place may take, in milliseconds: a minute. */
        private const val MAX_CLEARING_MILLIS = 60_000

        /** How many digits an IMSI has, as an IMEI does; an ICCID has 18 to 20. */
        private const val IMSI_LENGTH = 15

        /** How restore.sh's last line ends, which only a script that ran to its end prints. */
        const val LAST_LINE_END = "Airplane mode is on."
    }
}
