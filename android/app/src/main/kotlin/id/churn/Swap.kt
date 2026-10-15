package id.churn

import java.security.SecureRandom

/** The steps of a SIM swap, design.md section 7. The app saves the one reached. */
enum class Step {
    /** 1: the app parks slot 2 on the built-in eSIM and shuts the router down. */
    PREPARE,

    /** 2: the user swaps the card, goes elsewhere and switches the router on. */
    SWAP_CARD,

    /** 3: the app gives the new card its IMEIs. */
    NEW_IMEIS,

    /** 4: the user turns airplane mode off later, at another place and time. */
    GO_ONLINE,
}

/** What a step reports as it goes. */
enum class Progress {
    AIRPLANE_MODE_ON, RADIO_OFF, THROWAWAY_IMEIS, SLOT2_ON_ESIM, NO_PROFILE, PROFILE_DISABLED, WAITING_FOR_START,
    KILL_SWITCH_ON, FIREWALL, SLOT2_ON_SIM2, SLOT2_ACTIVE, CARD_FOUND, NEW_IMEIS,
}

/**
 * The router's part of steps 1 and 3. Each run starts from the beginning and checks every state it relies on,
 * so a step that stopped is simply run again.
 */
class Swap(
    private val router: Router,
    private val report: (Progress) -> Unit,
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
        val throwaway = Imei.throwaway(random, factory)
        router.writeImeis(throwaway)
        checkRadioOff()
        report(Progress.THROWAWAY_IMEIS)
        setSlot2(Slot2.ESIM)
        report(Progress.SLOT2_ON_ESIM)
        disableEsimProfile()
        checkRadioOff()
        return throwaway
    }

    /** Ends step 1. */
    fun shutDown() = router.powerOff()

    /** Step 3: slot 2 on the new card, which gets its own pair. Returns that pair. */
    fun giveNewImeis(factory: ImeiPair, secret: ByteArray): ImeiPair {
        // Before the wait, in case the user turned it off, so that the radio goes off after its burst at power-on.
        turnAirplaneModeOn()
        report(Progress.WAITING_FOR_START)
        // At every power-on the radio is on for some 12 s, ending 51 to 58 s after power-on in our measurements,
        // whatever airplane mode says (design.md, section 6). Slot 2 stays on the eSIM until well after that.
        if (!poll(BOOT_TIMEOUT_SECONDS) { router.uptimeSeconds() >= SETTLED_UPTIME_SECONDS }) {
            throw RouterException(Problem.NOT_STARTED)
        }
        waitForRadioOff()
        // Step 4 lets the router go online, so the switch and Churn's firewall rules must be in place by then
        // (rule 5). This comes while slot 2 is still on the eSIM, in case a reload they take turns the radio on.
        turnKillSwitchOn()
        putFirewallInPlace()
        val pair = try {
            writeCardsImeis(factory, secret)
        } catch (e: RouterException) {
            // Back to the eSIM, so that the card stays off if the router is switched off and on before the next try.
            setSlot2(Slot2.ESIM)
            report(Progress.SLOT2_ON_ESIM)
            throw e
        }
        report(Progress.NEW_IMEIS)
        return pair
    }

    /** Switches slot 2 to the SIM 2 tray and gives the card there its pair. */
    private fun writeCardsImeis(factory: ImeiPair, secret: ByteArray): ImeiPair {
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
            raw = router.slot2Iccid()?.takeIf { it != Router.PLACEHOLDER_ICCID }
            raw != null
        }
        val iccid = Imei.iccidDigits(raw ?: throw RouterException(Problem.NO_CARD))
            ?: throw RouterException(Problem.UNEXPECTED_ANSWER, "AT+ICCID")
        // A card without an enabled profile might show an ICCID of its own, which would give every such card
        // the same IMEIs. Only a card with a real network's IMSI goes on.
        val imsi = router.slot2Imsi()
        if (imsi == null || imsi.startsWith(TEST_NETWORK)) throw RouterException(Problem.NO_CARD)
        report(Progress.CARD_FOUND)
        val pair = Imei.derive(secret, iccid, factory)
        router.writeImeis(pair)
        checkRadioOff()
        return pair
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
            router.slot2Iccid() == Router.PLACEHOLDER_ICCID && router.slot2Imsi()?.startsWith(TEST_NETWORK) == true
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
        const val TEST_NETWORK = "00101"
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
