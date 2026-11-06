package id.churn

/**
 * Tier 2's dry run (docs/testing.md, "Dry run"): steps 1 and 3 of a swap on a router with no SIM, as far as they go
 * without one, run by the swap's own code, and the router put back as it was. Only debug builds have it.
 */
class DryRun(
    router: Router,
    report: (Progress) -> Unit,
    check: () -> List<String>,
    sleep: (Long) -> Unit = Thread::sleep,
    now: () -> Long = { System.nanoTime() / 1_000_000 },
) : Swap(router, report, ask = { error("A dry run asks nothing") }, check, sleep, now) {

    /** The IDs of the claims that the part of the dry run under way confirms, which a failure names. */
    var testing = emptyList<String>()
        private set

    /**
     * Runs the dry run. [save] gets what it puts back before the first change, to keep on the phone, and null once the
     * router is as it was again. A dry run that stops puts the router back if the radio is still off, and otherwise
     * leaves that to [putBack].
     */
    fun run(save: (DryRunStart?) -> Unit) {
        val start = DryRunStart(router.slot2(), activeSlot(), router.imeis())
        save(start)
        try {
            part("S2", "S3") { prepare(disableProfile = false) }
            // Whether either reload drops the cable or turns the radio on is open in development.md.
            part("K8", "D19") { startNewImeis(reload = true) }
            part("S2", "S3") { emptyTray() }
            part("S2", "S3") {
                setSlot2(Slot2.ESIM)
                report(Progress.SLOT2_ON_ESIM)
            }
            part { restore(start) }
        } catch (e: Exception) {
            // As far as the router lets it: one that doesn't answer, or whose radio isn't off, waits for putBack.
            runCatching {
                if (!asItWas(start)) {
                    checkRadioOff()
                    restore(start)
                }
                save(null)
                report(Progress.PUT_BACK)
            }
            throw e
        }
        testing = emptyList()
        save(null)
        report(Progress.PUT_BACK)
        report(Progress.DRY_RUN_PASSED)
    }

    /**
     * Puts the router back as it was before a dry run that stopped, [start], once the router has started and its radio
     * is off, as at step 3: it may have been switched off and on since. [save] then gets null.
     */
    fun putBack(start: DryRunStart, save: (DryRunStart?) -> Unit) {
        turnAirplaneModeOn()
        waitForStart()
        waitForRadioOff()
        if (!asItWas(start)) restore(start)
        save(null)
        report(Progress.PUT_BACK)
    }

    /** Runs [part] of the dry run, which confirms the claims with the IDs [ids]. */
    private fun part(vararg ids: String, part: () -> Unit) {
        testing = ids.toList()
        part()
    }

    /**
     * Step 3 with an empty SIM 2 tray: slot 2 on the tray and made the active slot, where no card may answer. A card
     * there stops the dry run.
     */
    private fun emptyTray() {
        val iccid = try {
            cardInTray()
        } catch (e: RouterException) {
            if (e.problem != Problem.NO_CARD) throw e
            // Or an eSIM adapter card without an enabled profile, which step 3 counts as no card.
            router.slot2Iccid()
        }
        if (iccid != null) throw RouterException(Problem.CARD_IN_TRAY)
        report(Progress.TRAY_EMPTY)
    }

    /** Whether the router's IMEIs, slot 2's setting and the active slot are [start]'s. */
    private fun asItWas(start: DryRunStart) =
        router.imeis() == start.imeis && router.slot2() == start.slot2 && activeSlot() == start.activeSlot

    /** Puts [start]'s IMEIs, slot 2's setting and active slot back, with the radio off, as it must be already. */
    private fun restore(start: DryRunStart) {
        if (router.imeis() != start.imeis) {
            router.writeImeis(start.imeis)
            checkRadioOff()
        }
        setSlot2(start.slot2)
        if (activeSlot() != start.activeSlot) activate(start.activeSlot, start.slot2)
    }

    private fun activeSlot() = router.activeSlot()?.toIntOrNull()?.takeIf { it in 1..2 }
        ?: throw RouterException(Problem.UNEXPECTED_ANSWER, "network.modem_cpu.dds_id")

    companion object {
        /** Debug builds have the dry run. */
        const val AVAILABLE = true
    }
}
