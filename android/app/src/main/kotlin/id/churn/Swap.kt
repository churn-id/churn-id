package id.churn

import java.security.SecureRandom

/** The steps of a SIM swap, design.md section 7. The app saves the one reached. */
enum class Step {
    /** 1: the app parks slot 2 on the built-in eSIM and shuts the router down. */
    PREPARE,

    /** 2: the user swaps the card, goes elsewhere and switches the router on. */
    SWAP_CARD,

    /** 3: the app gives the new card its IMEIs and shuts the router down. */
    NEW_IMEIS,

    /** 4: the user switches the router on later, at another place and time, and turns airplane mode off. */
    GO_ONLINE,
}

/** What a step reports as it goes. */
enum class Progress {
    AIRPLANE_MODE_ON, RADIO_OFF, THROWAWAY_IMEIS, SLOT2_ON_ESIM, NO_PROFILE, PROFILE_DISABLED, WAITING_FOR_START,
    SIM1_EMPTY, KILL_SWITCH_ON, FIREWALL, SLOT2_ON_SIM2, SLOT2_ACTIVE, CARD_FOUND, NEW_IMEIS, CLEARING_HISTORY,
    SHUTTING_DOWN,
}

/**
 * The router's part of steps 1 and 3. Each run starts from the beginning and checks every state it relies on,
 * so a step that stopped is simply run again. [ask] shows step 3's question and waits for the user's answer.
 */
class Swap(
    private val router: Router,
    private val report: (Progress) -> Unit,
    private val ask: (Question) -> Answer,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    /**
     * Step 1, up to the shutdown: radio off, a throwaway pair, slot 2 on the built-in eSIM with no profile
     * enabled. Returns the throwaway pair.
     */
    fun prepare(factory: ImeiPair, random: SecureRandom): ImeiPair {
        // A reboot is a power-on, so with a SIM inside it registers wherever it happens.
        if (router.scheduledReboot()) throw RouterException(Problem.SCHEDULED_REBOOT)
        // Rule 5. Checked before anything changes, while the old card can still reach the internet to set one up.
        if (!router.vpnSetUp()) throw RouterException(Problem.NO_VPN)
        turnAirplaneModeOn()
        waitForRadioOff()
        val throwaway = Imei.random(random, factory)
        router.writeImeis(throwaway)
        checkRadioOff()
        report(Progress.THROWAWAY_IMEIS)
        setSlot2(Slot2.ESIM)
        report(Progress.SLOT2_ON_ESIM)
        disableEsimProfile()
        checkRadioOff()
        return throwaway
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
     * Step 3: slot 2 on the new card, which gets a pair of its own. The card in [record] keeps its pair. Any other
     * card brings a question: a card in [places] gets its kept pair, and a new card a random one. The pair is
     * recorded before it is written. Returns the card with its pair, or null if the user canceled, with slot 2 back
     * on the built-in eSIM.
     */
    fun giveNewImeis(factory: ImeiPair, record: Card?, places: PlaceCards, random: SecureRandom): Card? {
        // Before the wait, in case the user turned it off, so that the radio goes off after its burst at power-on.
        turnAirplaneModeOn()
        // Rule 5, checked again in case the VPN was deleted since step 1. Slot 2 is still on the eSIM, so the step can
        // stop here and run again once one is set up, and the user learns it before the wait.
        if (!router.vpnSetUp()) throw RouterException(Problem.VPN_GONE)
        report(Progress.WAITING_FOR_START)
        // At every power-on the radio is on for some 12 s, ending 51 to 58 s after power-on in our measurements,
        // whatever airplane mode says (design.md, section 6). Slot 2 stays on the eSIM until well after that.
        if (!poll(BOOT_TIMEOUT_SECONDS) { router.uptimeSeconds() >= SETTLED_UPTIME_SECONDS }) {
            throw RouterException(Problem.NOT_STARTED)
        }
        waitForRadioOff()
        // A card in the SIM 1 tray registers at every power-on, whatever airplane mode says (design.md, section 6), so
        // at step 4 it would go out with the new card and link the two. Slot 2 is still on the eSIM, so the step can
        // stop here and run again once the user has taken the card out.
        if (router.cardInSim1Tray()) throw RouterException(Problem.SIM1_CARD)
        report(Progress.SIM1_EMPTY)
        // Step 4 lets the router go online, so the Enhanced Kill Switch and Churn's firewall rules must be in place
        // by then (rule 5). Turning the switch on takes a network reload, and adding the rules a firewall reload.
        // Either might turn the radio on, so both come while slot 2 is still on the eSIM.
        turnKillSwitchOn()
        putFirewallInPlace()
        val card = try {
            writeCardsImeis(factory, record, places, random) ?: return null
        } catch (e: RouterException) {
            // Back to the eSIM, so that the card stays off if the router is switched off and on before the next try.
            setSlot2(Slot2.ESIM)
            report(Progress.SLOT2_ON_ESIM)
            throw e
        }
        report(Progress.NEW_IMEIS)
        // Only now that the new card is in use: it is the one to keep, and the old cards' records go.
        report(Progress.CLEARING_HISTORY)
        router.clearHistory(card.iccid)
        // The clearing reloads GL's log service and fills the flash for a moment.
        checkRadioOff()
        return card
    }

    /** Switches slot 2 to the SIM 2 tray and gives the card there its pair; null if the user cancels. */
    private fun writeCardsImeis(factory: ImeiPair, record: Card?, places: PlaceCards, random: SecureRandom): Card? {
        val iccid = cardInTray()
        val card = record?.takeIf { it.iccid == iccid }
            ?: askAbout(Card(iccid, Imei.random(random, factory)), places)
            ?: return null
        // Recorded before the IMEIs are written, so that a step stopped in between gives the card the same pair when
        // it runs again.
        if (card != record) router.recordCard(card)
        router.writeImeis(card.imeis)
        checkRadioOff()
        return card
    }

    /**
     * Asks the user about [card], which has a new random pair, and returns it as they chose, with slot 2 on the tray
     * again; null if they cancel. Meanwhile slot 2 is on the built-in eSIM, so that the card stays off if the router
     * is switched off and on before they answer.
     */
    private fun askAbout(card: Card, places: PlaceCards): Card? {
        setSlot2(Slot2.ESIM)
        report(Progress.SLOT2_ON_ESIM)
        val chosen = places.choose(card, ask) ?: return null
        // The pair was chosen for this card, so it mustn't go to another.
        if (cardInTray() != card.iccid) throw RouterException(Problem.CARD_CHANGED)
        return chosen
    }

    /** Switches slot 2 to the SIM 2 tray and returns the ICCID of the card there, digits only. */
    private fun cardInTray(): String {
        setSlot2(Slot2.SIM2)
        report(Progress.SLOT2_ON_SIM2)
        if (router.activeSlot() != "2") {
            router.activateSlot2(Slot2.SIM2)
            if (!poll(SWITCH_TIMEOUT_SECONDS) { router.activeSlot() == "2" }) {
                throw RouterException(Problem.SLOT_NOT_SWITCHED)
            }
            checkRadioOff()
        }
        report(Progress.SLOT2_ACTIVE)
        // The card answers a moment after the switch.
        var raw: String? = null
        poll(CARD_TIMEOUT_SECONDS) {
            raw = (router.slot2Card() as? Slot2Card.Subscription)?.iccid
            raw != null
        }
        val cardIccid = raw ?: throw RouterException(Problem.NO_CARD)
        val iccid = Imei.iccidDigits(cardIccid) ?: throw RouterException(Problem.UNEXPECTED_ANSWER, "AT+ICCID")
        report(Progress.CARD_FOUND)
        return iccid
    }

    private fun turnAirplaneModeOn() {
        if (!router.airplaneMode()) router.setAirplaneMode(true)
        report(Progress.AIRPLANE_MODE_ON)
    }

    private fun waitForRadioOff() {
        // GL's cellular manager turns the radio off right after airplane mode is set. If it doesn't, the router is
        // in a state we don't understand, so the step stops.
        if (!poll(RADIO_TIMEOUT_SECONDS) { router.radio() == RADIO_OFF }) throw RouterException(Problem.RADIO_ON)
        report(Progress.RADIO_OFF)
    }

    /** Turns GL's Enhanced Kill Switch on if it is off, and checks that the radio is still off afterwards. */
    private fun turnKillSwitchOn() {
        if (!router.enhancedKillSwitch()) {
            router.turnEnhancedKillSwitchOn()
            checkRadioOff()
        }
        report(Progress.KILL_SWITCH_ON)
    }

    /** Puts Churn's firewall rules in place if they aren't, and checks that the radio is still off afterwards. */
    private fun putFirewallInPlace() {
        if (!router.firewallInPlace()) {
            router.installFirewall()
            checkRadioOff()
        }
        report(Progress.FIREWALL)
    }

    private fun checkRadioOff() {
        if (!router.airplaneMode() || router.radio() != RADIO_OFF) throw RouterException(Problem.RADIO_ON)
    }

    private fun setSlot2(slot2: Slot2) {
        if (router.slot2() != slot2) {
            router.setSlot2(slot2)
            if (!poll(SWITCH_TIMEOUT_SECONDS) { router.slot2() == slot2 }) {
                throw RouterException(Problem.SLOT_NOT_SWITCHED)
            }
        }
        checkRadioOff()
    }

    /** Leaves the built-in eSIM, which slot 2 must be set to, with no profile enabled. */
    private fun disableEsimProfile() {
        var iccid: String? = null
        // The chip answers a moment after slot 2 is switched to it.
        poll(CARD_TIMEOUT_SECONDS) {
            iccid = router.slot2Iccid()
            iccid != null
        }
        val enabled = iccid ?: throw RouterException(Problem.UNEXPECTED_ANSWER, "AT+ICCID")
        if (enabled == Router.PLACEHOLDER_ICCID) {
            report(Progress.NO_PROFILE)
            return
        }
        // The answer doesn't tell whether it worked, so only the ICCID and IMSI read afterwards count.
        router.disableProfile(enabled)
        // GL's eSIM manager waits about 5 s for the modem to reload the card.
        sleep(5_000)
        val disabled = poll(PROFILE_TIMEOUT_SECONDS) {
            router.slot2Iccid() == Router.PLACEHOLDER_ICCID &&
                router.slot2Imsi()?.startsWith(Router.TEST_NETWORK) == true
        }
        if (!disabled) throw RouterException(Problem.PROFILE_STILL_ENABLED)
        report(Progress.PROFILE_DISABLED)
    }

    /**
     * Checks [condition] every few seconds until it holds, for up to [seconds]. An answer that doesn't parse
     * counts as "not yet", since the modem doesn't answer for a while after power-on.
     */
    private fun poll(seconds: Int, condition: () -> Boolean): Boolean {
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

    private companion object {
        const val RADIO_OFF = 4
        const val POLL_MILLISECONDS = 2_000L
        const val RADIO_TIMEOUT_SECONDS = 30
        const val SWITCH_TIMEOUT_SECONDS = 30
        const val CARD_TIMEOUT_SECONDS = 60
        const val PROFILE_TIMEOUT_SECONDS = 60
        const val BOOT_TIMEOUT_SECONDS = 600

        /** About twice the latest end of the radio-on window we measured after power-on, 58 s. */
        const val SETTLED_UPTIME_SECONDS = 120.0
    }
}
