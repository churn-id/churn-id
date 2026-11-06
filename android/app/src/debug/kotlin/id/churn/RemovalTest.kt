package id.churn

/**
 * Tier 2's removal test (docs/testing.md, "Removal and clearing"): restore.sh run as Remove Churn runs it, the router
 * then set up again as it was, with the card's record, the IMEIs it had and Churn's firewall rules, and the SIM history
 * cleared as step 3 clears it. restore.sh deletes the zero files, so the clearing fills all of the flash's free space:
 * the test runs once for each firmware. Only debug builds have it.
 */
class RemovalTest(
    router: Router,
    report: (Progress) -> Unit,
    check: () -> List<String>,
    /** Sets the router up as setup does, with the factory IMEIs, keeping the SIM history of the card with the ICCID. */
    private val setUpFolder: (ImeiPair, String?) -> Unit,
    sleep: (Long) -> Unit = Thread::sleep,
    now: () -> Long = { System.nanoTime() / 1_000_000 },
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
        clear(start)
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
     * the zero files, so the overwrite fills all of the free space, about 2.9 GB, and takes minutes.
     */
    private fun clear(start: RemovalStart) {
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
        testing = emptyList()
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

        /** How restore.sh's last line ends, which only a script that ran to its end prints. */
        const val LAST_LINE_END = "Airplane mode is on."
    }
}
