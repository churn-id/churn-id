package id.churn

/**
 * Tier 2's removal test (docs/testing.md, "Removal and clearing"): restore.sh run as Remove Churn runs it, and the
 * router then set up again as it was, with the card's record, the IMEIs it had and Churn's firewall rules. restore.sh
 * deletes the zero files, so the next clearing fills all of the flash's free space: the test runs once for each
 * firmware. Only debug builds have it.
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
        save(start)
        try {
            part("F12") {
                if (!router.restoreFactoryImeis().endsWith(LAST_LINE_END)) {
                    throw RouterException(Problem.UNEXPECTED_ANSWER, "restore.sh: its last line is missing")
                }
                report(Progress.RESTORED)
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

    /** Runs [part] of the test, which confirms the claims with the IDs [ids]. */
    private fun part(vararg ids: String, part: () -> Unit) {
        testing = ids.toList()
        part()
    }

    /** Sets the router up as [start] says it was, with the radio off, as it must be already. */
    private fun setUp(start: RemovalStart) {
        // As setup keeps the current card's history: the recorded card's, since with both trays empty GL may name no
        // card as the current one, or else the card GL names.
        setUpFolder(start.factory, start.card?.iccid ?: router.currentCardIccid())
        start.card?.let(router::recordCard)
        if (router.imeis() != start.imeis) {
            router.writeImeis(start.imeis)
            checkRadioOff()
        }
        if (start.firewall && !router.firewallInPlace()) router.installFirewall()
    }

    companion object {
        /** Debug builds have the removal test. */
        const val AVAILABLE = true

        /** How restore.sh's last line ends, which only a script that ran to its end prints. */
        const val LAST_LINE_END = "Airplane mode is on."
    }
}
