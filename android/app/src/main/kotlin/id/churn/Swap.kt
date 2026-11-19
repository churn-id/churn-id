package id.churn

import java.security.SecureRandom

/** The steps of a SIM swap, design.md section 7. The app saves the one reached. */
enum class Step {
    /** 1: the app parks slot 2 on the built-in eSIM and shuts the router down. */
    PREPARE,

    /** 2: the user swaps the card and switches the router on where step 1 ran. */
    SWAP_CARD,

    /** 3: the app gives the new card its IMEIs and shuts the router down. */
    NEW_IMEIS,

    /** 4: the user switches the router on later, at another place and time, and turns airplane mode off. */
    GO_ONLINE,
}

/** What a step, or a test of router-tests.md, reports as it goes. */
enum class Progress {
    UNTESTED_PLATFORM, AIRPLANE_MODE_ON, RADIO_OFF, SLOT2_ON_ESIM, NO_PROFILE, DISABLING_AGAIN, PROFILE_DISABLED,
    WAITING_FOR_START, SIM1_EMPTY, KILL_SWITCH_ON, FIREWALL, SLOT2_ON_SIM2, SLOT2_ACTIVE, CARD_FOUND, CARD_FROM_BEFORE,
    SLOT2_BACK_ON_SIM2, SAME_CARD, NEW_IMEIS, CLEARING_HISTORY, SHUTTING_DOWN, NETWORK_RELOADED, FIREWALL_RELOADED,
    SLOT1_ACTIVE, TRAY_EMPTY, ESIM_SILENT, TEST_IMEIS, EARLIER_IMEIS, ANSWERS_MATCHED, LONG_ANSWER_READ, PUT_BACK,
    DRY_RUN_PASSED, RESTORED, FACTORY_IMEIS, FOLDER_GONE, LOG_SETTING_BACK, LOG_WRITTEN, ZERO_FILES_GONE, FIREWALL_GONE,
    SET_UP_AGAIN, CARD_FILE_MODES_KEPT, NO_CARD_FILES, CONNECTION_KEPT, RESERVE_BACK, CARD_FILES_PARSE,
    ZEROS_KEEP_SPACE, READING_PARTITION, IMSIS_GONE, NO_IMSIS, ICCIDS_GONE, NO_ICCIDS, PARTITION_UNCHECKED,
    FREE_SPACE_FILLED, CLEARED_QUICKLY, REMOVAL_PASSED,
    NETWORK_LOST, NEW_BOOT_ID, TEST_PAIR_SAVED, SETTINGS_KEPT, RULES_AT_START, CHAINS_AT_START, MODEM_ANSWERED,
    MODEM_AT_ONCE, RADIO_ON_UNTIL, RADIO_OFF_AT_FIRST, RADIO_OFF_FROM, IMEIS_KEPT, EARLIER_IMEIS_SAVED,
    POWER_CYCLES_PASSED, CARD_READY, GOING_ONLINE, SIM_PACKETS,
    FRAGMENTS_UNCLEAR, INTERFACES_CHANGED, LEAK_WATCH_PASSED, SIM_ONLY, TUNNEL_MADE_ANEW, WIREGUARD_MATCHED,
    WIREGUARD_UNCLEAR, HANDSHAKE, CLOCK_SYNCED, TURN_AIRPLANE_MODE_OFF, CONNECTION_CHECK_OFF,
    CONNECTION_CHECK_RESTARTED, CONNECTION_CHECK_BACK, SWITCHING_AGAIN,
}

/**
 * The router's state before a test of router-tests.md that puts it back as it was: slot 2's setting, the active slot
 * and the IMEIs.
 */
interface TestStart {
    val slot2: Slot2
    val activeSlot: Int
    val imeis: ImeiPair
}

/**
 * What tier 2's dry run puts back (router-tests.md, "Dry run"): slot 2's setting, the active slot and the IMEIs as they
 * were before it.
 */
data class DryRunStart(override val slot2: Slot2, override val activeSlot: Int, override val imeis: ImeiPair) :
    TestStart

/**
 * What tier 2's removal test sets the router up again with (router-tests.md, "Removal and clearing"): the [factory]
 * IMEIs, the recorded [card], the [imeis] the router had, whether Churn's [firewall] rules were in place, whether
 * GL's connection check was off for the SIM, [connectionCheckOff], and the recorded [version].
 */
data class RemovalStart(
    val factory: ImeiPair,
    val card: Card?,
    val imeis: ImeiPair,
    val firewall: Boolean,
    val connectionCheckOff: Boolean,
    val version: Int?,
)

/**
 * What tier 3's power cycle test puts back and keeps across its power-offs (router-tests.md, "Power cycles"): slot 2's
 * setting, the active slot and the IMEIs as they were before it, the ID of the router's run that the last power-off
 * ended, [bootId], the power-on that the test waits for, [powerOn]: 1 or 2, or 0 for none, and the test pair of IMEIs
 * saved before the first power-off, [testImeis].
 */
data class PowerCycleStart(
    override val slot2: Slot2,
    override val activeSlot: Int,
    override val imeis: ImeiPair,
    val bootId: String? = null,
    val powerOn: Int = 0,
    val testImeis: ImeiPair? = null,
) : TestStart

/**
 * The router's part of steps 1 and 3. Each run starts from the beginning and checks every state it relies on,
 * so a step that stopped is simply run again. [ask] shows step 3's question and waits for the user's answer. [check]
 * runs the router checks that a swap relies on, those that block in router-tests.md, and returns the lines of those
 * that failed. Both steps run them before they change slot 2, step 3 once the router has started. The tests of tiers 2
 * and 3 in router-tests.md build on the steps' parts. [cardMayBeOn] starts as the last run of step 3 left it.
 */
open class Swap(
    protected val router: Router,
    protected val report: (Progress) -> Unit,
    private val ask: (Question) -> Answer,
    private val check: () -> List<String> = { emptyList() },
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    cardMayBeOn: Boolean = false,
) {

    /**
     * Whether slot 2 may be on the SIM 2 tray while the card there doesn't have its own pair yet: from the switch to
     * the tray until the pair is saved or slot 2 is back on the built-in eSIM. A power-on meanwhile would register the
     * card with the IMEIs that the modem has, at step 3 those of the card before it, so a step that stops then says
     * so.
     */
    var cardMayBeOn = cardMayBeOn
        private set

    /**
     * Step 1, up to the shutdown: radio off, slot 2 on the built-in eSIM with no profile enabled. The IMEIs stay the
     * old card's until step 3 (design.md, section 4). Without [disableProfile], for the dry run, an enabled profile
     * stops the step instead: the dry run puts back what it changes, and Churn can't enable a profile again.
     */
    fun prepare(disableProfile: Boolean = true) {
        // A reboot is a power-on, so with a SIM inside it registers wherever it happens.
        if (router.scheduledReboot()) throw RouterException(Problem.SCHEDULED_REBOOT)
        // Rule 5. Checked before anything changes, while the old card can still reach the internet to set one up.
        if (!router.vpnSetUp()) throw RouterException(Problem.NO_VPN)
        runChecks()
        turnAirplaneModeOn()
        waitForRadioOff()
        setSlot2(Slot2.ESIM)
        report(Progress.SLOT2_ON_ESIM)
        if (disableProfile) {
            disableEsimProfile()
        } else {
            if (enabledProfile() != null) throw RouterException(Problem.PROFILE_ENABLED)
            report(Progress.NO_PROFILE)
        }
        checkRadioOff()
    }

    /** Ends steps 1 and 3. */
    fun shutDown() {
        router.powerOff()
        report(Progress.SHUTTING_DOWN)
    }

    /** The ID of the router's current run, from power-on to shutdown, for [restartedSince]. */
    fun bootId() = router.bootId()

    /**
     * Whether the router has been switched off and on since the run with [bootId], which the last shutdown ended;
     * true without one. Until then the user can't have swapped the card.
     */
    fun restartedSince(bootId: String?) = bootId == null || router.bootId() != bootId

    /**
     * Step 3: slot 2 on the new card, which gets a pair of its own. The card in [record] keeps its pair. If it is also
     * [before], the card recorded when step 1 ran, the user is asked whether to keep it. Any other card brings a
     * question too: a card in [places] gets its kept pair, and a new card a random one. The pair is recorded before it
     * is written. Last, with everything in place, [version], the app's version code, is recorded. Returns the card
     * with its pair, or null if the user canceled, with slot 2 back on the built-in eSIM. A step that stops before the
     * pair is saved puts slot 2 back there too, as far as the connection allows, and [cardMayBeOn] says whether it may
     * still be on the tray.
     */
    fun giveNewImeis(
        factory: ImeiPair,
        record: Card?,
        before: String?,
        places: PlaceCards,
        random: SecureRandom,
        version: Int,
    ): Card? {
        // A run that stopped may have left slot 2 on the tray. Without an answer, as for a while after power-on, the
        // flag stays as that run left it.
        runCatching { router.slot2() }.getOrNull()?.let { cardMayBeOn = it != Slot2.ESIM }
        val card = try {
            startNewImeis(reload = false)
            writeCardsImeis(factory, record, before, places, random) ?: return null
        } catch (e: Exception) {
            // Back to the eSIM as far as the connection allows, also after a broken one, so that the card stays off
            // if the router is switched off and on before the next try. The step stopped for e, which it reports.
            runCatching { park() }
            throw e
        }
        report(Progress.NEW_IMEIS)
        // Only now that the new card is in use: it is the one to keep, and the old cards' records go.
        report(Progress.CLEARING_HISTORY)
        router.clearHistory(card.iccid)
        // The clearing reloads GL's log service and fills the flash for a moment.
        checkRadioOff()
        // So that a later version knows which of its settings this one put in place.
        router.recordVersion(version)
        return card
    }

    /**
     * Step 3 up to the card in the tray: airplane mode on, the router started, the checks passed, the radio off, slot 2
     * on the built-in eSIM, the SIM 1 tray empty, the Enhanced Kill Switch and Churn's firewall rules in place, and
     * GL's connection check off for the SIM. With [reload], for the dry run, the network and the firewall reload and
     * the connection check restarts even if the switch is on, the rules are in place and the check is off.
     */
    protected fun startNewImeis(reload: Boolean) {
        // Before the wait, in case the user turned it off, so that the radio goes off after its burst at power-on.
        turnAirplaneModeOn()
        // Rule 5, checked again in case the VPN was deleted since step 1. Slot 2 is on the eSIM, as step 1 left it, or
        // goes back there as the step stops, so the step can stop here and run again once one is set up, and the user
        // learns it before the wait.
        if (!router.vpnSetUp()) throw RouterException(Problem.VPN_GONE)
        waitForStart()
        runChecks()
        waitForRadioOff()
        // Before anything that might turn the radio on: an earlier run that lost its connection may have left slot 2
        // on the tray. Not before the radio is off, since a switch checks that it is.
        park()
        // A card in the SIM 1 tray registers at every power-on, whatever airplane mode says (design.md, section 6), so
        // at step 4 it would go out with the new card and link the two. Slot 2 is on the eSIM, so the step can stop
        // here and run again once the user has taken the card out.
        if (router.cardInSim1Tray()) throw RouterException(Problem.SIM1_CARD)
        report(Progress.SIM1_EMPTY)
        // Step 4 lets the router go online, so the Enhanced Kill Switch and Churn's firewall rules must be in place
        // by then (rule 5), and GL's connection check, which pings past the rules, off for the SIM. Turning the switch
        // on takes a network reload, adding the rules a firewall reload, and turning the check off a restart of it,
        // which reloads the network too. Each might turn the radio on, so all come while slot 2 is on the eSIM.
        turnKillSwitchOn(reload)
        putFirewallInPlace(reload)
        turnConnectionCheckOff(reload)
    }

    /** Waits until the router has started, when its radio's burst at power-on is over. */
    protected fun waitForStart() {
        report(Progress.WAITING_FOR_START)
        // At every power-on the radio is on for some 12 s, ending 51 to 58 s after power-on in our measurements,
        // whatever airplane mode says (design.md, section 6). Slot 2 stays on the eSIM until well after that.
        if (!poll(BOOT_TIMEOUT_SECONDS) { router.uptimeSeconds() >= SETTLED_UPTIME_SECONDS }) {
            throw RouterException(Problem.NOT_STARTED)
        }
    }

    /**
     * Stops the step if a router check that swaps rely on fails, since Churn's commands can't be trusted then. On a
     * firmware or modem we haven't tested, the step goes on once they all pass, with a note.
     */
    protected fun runChecks() {
        val failed = check()
        if (failed.isNotEmpty()) throw RouterException(Problem.CHECK_FAILED, failed.joinToString("\n\n"))
        // As in the check of P1, a version the app can't read counts as untested, and doesn't stop the step.
        val tested = try {
            router.platform() in TESTED_PLATFORMS
        } catch (e: RouterException) {
            false
        }
        if (!tested) report(Progress.UNTESTED_PLATFORM)
    }

    /** Switches slot 2 to the SIM 2 tray and gives the card there its pair; null if the user cancels. */
    private fun writeCardsImeis(
        factory: ImeiPair,
        record: Card?,
        before: String?,
        places: PlaceCards,
        random: SecureRandom,
    ): Card? {
        val iccid = cardInTray()
        val recorded = record?.takeIf { it.iccid == iccid }
        val card = if (recorded != null && iccid == before) {
            // The card from before the swap, kept after all or left in by mistake. Without a question, a user who
            // meant to put in a new card would go on with this one, believing it new.
            report(Progress.CARD_FROM_BEFORE)
            askAbout(iccid) {
                recorded.takeIf { ask(Question.CardFromBefore(iccid, places.find(iccid))) == Answer.CONTINUE }
            }
        } else {
            report(Progress.CARD_FOUND)
            recorded ?: askAbout(iccid) { places.choose(Card(iccid, Imei.random(random, factory)), ask) }
        } ?: return null
        // Recorded before the IMEIs are written, so that a step stopped in between gives the card the same pair when
        // it runs again.
        if (card != record) router.recordCard(card)
        router.writeImeis(card.imeis)
        // Saved, so a power-on would now register the card with its own pair.
        cardMayBeOn = false
        checkRadioOff()
        return card
    }

    /**
     * Asks the user about the card [iccid] with [choose], and returns the card with the pair they chose, with slot 2
     * on the tray again; null if they cancel. Meanwhile slot 2 is on the built-in eSIM, so that the card stays off if
     * the router is switched off and on before they answer.
     */
    private fun askAbout(iccid: String, choose: () -> Card?): Card? {
        park()
        val chosen = choose() ?: return null
        // The pair was chosen for this card, so it mustn't go to another.
        if (cardInTray(again = true) != iccid) throw RouterException(Problem.CARD_CHANGED)
        report(Progress.SAME_CARD)
        return chosen
    }

    /** Puts slot 2 back on the built-in eSIM, unless it is there already, so that the card in the tray is off. */
    private fun park() {
        if (router.slot2() == Slot2.ESIM) {
            cardMayBeOn = false
            return
        }
        setSlot2(Slot2.ESIM)
        report(Progress.SLOT2_ON_ESIM)
    }

    /**
     * Switches slot 2 to the SIM 2 tray and returns the ICCID of the card there, digits only. With [again], for the
     * switch back after step 3's question, the log says only that slot 2 is back on the tray.
     */
    protected fun cardInTray(again: Boolean = false): String {
        setSlot2(Slot2.SIM2)
        report(if (again) Progress.SLOT2_BACK_ON_SIM2 else Progress.SLOT2_ON_SIM2)
        if (router.activeSlot() != "2") activate(slot = 2, Slot2.SIM2)
        if (!again) report(Progress.SLOT2_ACTIVE)
        // The card answers a moment after the switch.
        var raw: String? = null
        poll(CARD_TIMEOUT_SECONDS) {
            raw = (router.slot2Card() as? Slot2Card.Subscription)?.iccid
            raw != null
        }
        val cardIccid = raw ?: throw RouterException(Problem.NO_CARD)
        return Imei.iccidDigits(cardIccid) ?: throw RouterException(Problem.UNEXPECTED_ANSWER, "AT+ICCID")
    }

    /**
     * Step 3 with an empty SIM 2 tray, for the tests of router-tests.md: slot 2 on the tray and made the active slot,
     * where no card may answer. A card there stops the test.
     */
    protected fun emptyTray() {
        val iccid = try {
            cardInTray()
        } catch (e: RouterException) {
            if (e.problem != Problem.NO_CARD) throw e
            // Or an eSIM adapter card without an enabled profile, which step 3 counts as no card. The built-in eSIM's
            // placeholder is the dry run's check of E4.
            router.slot2Iccid()?.takeIf { it != Router.PLACEHOLDER_ICCID }
        }
        if (iccid != null) throw RouterException(Problem.CARD_IN_TRAY)
        report(Progress.TRAY_EMPTY)
    }

    /** Whether the router's IMEIs, slot 2's setting and the active slot are [start]'s. */
    protected fun asItWas(start: TestStart) =
        router.imeis() == start.imeis && router.slot2() == start.slot2 && activeSlot() == start.activeSlot

    /** Puts [start]'s IMEIs, slot 2's setting and active slot back, with the radio off, as it must be already. */
    protected fun restore(start: TestStart) {
        if (router.imeis() != start.imeis) {
            router.writeImeis(start.imeis)
            checkRadioOff()
        }
        setSlot2(start.slot2)
        if (activeSlot() != start.activeSlot) activate(start.activeSlot, start.slot2)
    }

    protected fun activeSlot() = router.activeSlot()?.toIntOrNull()?.takeIf { it in 1..2 }
        ?: throw RouterException(Problem.UNEXPECTED_ANSWER, "network.modem_cpu.dds_id")

    protected fun turnAirplaneModeOn() {
        if (!router.airplaneMode()) router.setAirplaneMode(true)
        report(Progress.AIRPLANE_MODE_ON)
    }

    protected fun waitForRadioOff() {
        // GL's cellular manager turns the radio off right after airplane mode is set. If it doesn't, the router is
        // in a state we don't understand, so the step stops.
        if (!poll(RADIO_TIMEOUT_SECONDS) { router.radio() == RADIO_OFF }) throw RouterException(Problem.RADIO_ON)
        report(Progress.RADIO_OFF)
    }

    /**
     * Turns GL's Enhanced Kill Switch on if it is off, or with [reload] in any case, and checks that the radio is still
     * off afterwards.
     */
    private fun turnKillSwitchOn(reload: Boolean) {
        if (reload || !router.enhancedKillSwitch()) {
            router.turnEnhancedKillSwitchOn()
            checkRadioOff()
        }
        report(if (reload) Progress.NETWORK_RELOADED else Progress.KILL_SWITCH_ON)
    }

    /**
     * Puts Churn's firewall rules in place if they aren't, or with [reload] in any case, and checks that the radio is
     * still off afterwards.
     */
    private fun putFirewallInPlace(reload: Boolean) {
        if (reload || !router.firewallInPlace()) {
            router.installFirewall()
            checkRadioOff()
        }
        report(if (reload) Progress.FIREWALL_RELOADED else Progress.FIREWALL)
    }

    /**
     * Turns GL's connection check off for the SIM if it is on, or restarts it with [reload] in any case, and checks
     * that the radio is still off afterwards.
     */
    private fun turnConnectionCheckOff(reload: Boolean) {
        if (reload || !router.simConnectionCheckOff()) {
            router.turnSimConnectionCheckOff()
            checkRadioOff()
        }
        report(if (reload) Progress.CONNECTION_CHECK_RESTARTED else Progress.CONNECTION_CHECK_OFF)
    }

    protected fun checkRadioOff() {
        if (!router.airplaneMode() || router.radio() != RADIO_OFF) throw RouterException(Problem.RADIO_ON)
    }

    protected fun setSlot2(slot2: Slot2) {
        if (router.slot2() != slot2) {
            // Before the switch to the tray: once GL has it, a power-on would register the card there.
            if (slot2 == Slot2.SIM2) cardMayBeOn = true
            switching { router.setSlot2(slot2) }
            if (!poll(SWITCH_TIMEOUT_SECONDS) { router.slot2() == slot2 }) {
                throw RouterException(Problem.SLOT_NOT_SWITCHED)
            }
        }
        cardMayBeOn = slot2 == Slot2.SIM2
        checkRadioOff()
    }

    /** Makes [slot] the active slot, with slot 2 set to [slot2], and checks that the radio is still off. */
    protected fun activate(slot: Int, slot2: Slot2) {
        switching { router.activateSlot(slot, slot2) }
        if (!poll(SWITCH_TIMEOUT_SECONDS) { router.activeSlot() == "$slot" }) {
            throw RouterException(Problem.SLOT_NOT_SWITCHED)
        }
        checkRadioOff()
    }

    /** Runs [switch], a slot switch, up to [SWITCH_TRIES] times while GL's cellular manager answers that it is busy. */
    private fun switching(switch: () -> Unit) {
        repeat(SWITCH_TRIES - 1) {
            try {
                switch()
                return
            } catch (e: RouterException) {
                if (e.problem != Problem.LOCK_FAILED) throw e
            }
            report(Progress.SWITCHING_AGAIN)
            sleep(SWITCH_PAUSE_MILLISECONDS)
        }
        switch()
    }

    /**
     * Leaves the built-in eSIM, which slot 2 must be set to, with no profile enabled, asking GL's eSIM manager up to
     * [PROFILE_REQUESTS] times while a profile stays enabled.
     */
    private fun disableEsimProfile() {
        var answer: String? = null
        repeat(PROFILE_REQUESTS) { sent ->
            val enabled = enabledProfile()
            if (enabled == null) {
                report(if (sent == 0) Progress.NO_PROFILE else Progress.PROFILE_DISABLED)
                return
            }
            if (sent > 0) report(Progress.DISABLING_AGAIN)
            // The answer doesn't tell whether it worked, so only the ICCID and IMSI read afterwards count.
            answer = router.disableProfile(enabled)
            // GL's eSIM manager waits about 5 s for the modem to reload the card.
            sleep(5_000)
            val disabled = poll(PROFILE_TIMEOUT_SECONDS) {
                router.slot2Iccid() == Router.PLACEHOLDER_ICCID &&
                    router.slot2Imsi()?.startsWith(Router.TEST_NETWORK) == true
            }
            if (disabled) {
                report(Progress.PROFILE_DISABLED)
                return
            }
        }
        throw RouterException(Problem.PROFILE_STILL_ENABLED, answer)
    }

    /** The ICCID of the profile enabled on the built-in eSIM, as [Router.slot2Iccid] gives it; null if none is. */
    private fun enabledProfile(): String? {
        var iccid: String? = null
        // The chip answers a moment after slot 2 is switched to it.
        poll(CARD_TIMEOUT_SECONDS) {
            iccid = router.slot2Iccid()
            iccid != null
        }
        val enabled = iccid ?: throw RouterException(Problem.UNEXPECTED_ANSWER, "AT+ICCID")
        return enabled.takeIf { it != Router.PLACEHOLDER_ICCID }
    }

    /**
     * Checks [condition] every few seconds until it holds, for up to [seconds]. An answer that doesn't parse
     * counts as "not yet", since the modem doesn't answer for a while after power-on.
     */
    protected fun poll(seconds: Int, condition: () -> Boolean): Boolean {
        val deadline = now() + seconds * 1_000L
        while (true) {
            try {
                if (condition()) return true
            } catch (_: RouterException) {
            }
            if (now() >= deadline) return false
            sleep(POLL_MILLISECONDS)
        }
    }

    companion object {
        private const val RADIO_OFF = 4
        private const val POLL_MILLISECONDS = 2_000L
        private const val RADIO_TIMEOUT_SECONDS = 30
        private const val SWITCH_TIMEOUT_SECONDS = 30

        /**
         * Slot switches sent before a step stops at GL's "lock failed"; problem_lock_failed says how many. GL refused
         * 1 of 27 switches in our tests. That it rarely refuses again 5 s later is our guess, not a measurement.
         */
        private const val SWITCH_TRIES = 3
        private const val SWITCH_PAUSE_MILLISECONDS = 5_000L
        private const val CARD_TIMEOUT_SECONDS = 60

        /** For the placeholder after a disable request, which showed 5 s after the answer in our recordings. */
        private const val PROFILE_TIMEOUT_SECONDS = 30

        /**
         * Disable requests sent before step 1 stops; problem_profile_still_enabled says how many. Each comes at least
         * 35 s after the last, later than GL's eSIM manager reused its channel to the chip in our recordings
         * (development.md, "Disabling the enabled profile"). Our guess, not a measurement.
         */
        private const val PROFILE_REQUESTS = 3
        private const val BOOT_TIMEOUT_SECONDS = 600

        /** About twice the latest end of the radio-on window we measured after power-on, 58 s. */
        const val SETTLED_UPTIME_SECONDS = 120.0
    }
}
