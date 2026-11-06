package id.churn

/**
 * Tier 4's leak watch (docs/testing.md, "Leak watch"): the router goes online with the card that step 3 gave its IMEIs,
 * as at step 4, while the test counts for a minute what leaves over the SIM. Only debug builds have it.
 */
class LeakWatch(
    router: Router,
    report: (Progress) -> Unit,
    /** Reports a [Progress] whose line names numbers, such as of packets, in their order. */
    private val measured: (Progress, List<Long>) -> Unit,
    check: () -> List<String>,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : Swap(router, report, ask = { error("The leak watch asks nothing") }, check, sleep, now) {

    /** The IDs of the claims that the part of the test under way confirms, which a failure names. */
    var testing = emptyList<String>()
        private set

    /**
     * Runs the leak watch with [card], the card that step 3 last gave its IMEIs, as Churn's folder records it. Until
     * airplane mode goes off, anything not as step 3 leaves it stops the test. Once it is off, packets other than
     * WireGuard's, or anything else that stops the test, turn it on again, and a test that ends leaves the router
     * online, as step 4 does.
     */
    fun run(card: Card?) {
        checkReady(card)
        try {
            router.addLeakCounters()
            checkRadioOff()
            part("D9", "D11") { watch() }
            router.removeLeakCounters()
        } catch (e: Exception) {
            // Whatever left over the SIM, nothing more does.
            runCatching {
                router.setAirplaneMode(true)
                report(Progress.AIRPLANE_MODE_ON)
            }
            runCatching { router.removeLeakCounters() }
            throw e
        }
        testing = emptyList()
        report(Progress.LEAK_WATCH_PASSED)
    }

    /**
     * Checks that the router is as step 3 leaves it, with airplane mode on: the VPN set up, the router started, the
     * router checks that swaps rely on passed, the radio off, the SIM 1 tray empty, [card] answering in slot 2, the
     * active slot, and the router having its IMEIs, the Enhanced Kill Switch and Churn's firewall rules in place, and
     * no uplink other than the SIM.
     */
    private fun checkReady(card: Card?) {
        turnAirplaneModeOn()
        // Rule 5.
        if (!router.vpnSetUp()) throw RouterException(Problem.NO_VPN)
        waitForStart()
        runChecks()
        waitForRadioOff()
        // As at step 3: a card there would go online with the one in SIM 2 and link the two.
        if (router.cardInSim1Tray()) notReady("A card answers in the SIM 1 tray")
        report(Progress.SIM1_EMPTY)
        if (router.slot2() != Slot2.SIM2 || activeSlot() != 2) notReady("Slot 2 isn't on the SIM 2 tray or not active")
        // Any other card, or other IMEIs, would go online with IMEIs that aren't the card's and link them to it.
        val iccid = (router.slot2Card() as? Slot2Card.Subscription)?.iccid?.let(Imei::iccidDigits)
        if (card == null || iccid != card.iccid || router.imeis() != card.imeis) {
            notReady("The card in SIM 2 isn't the one that step 3 last gave its IMEIs, or doesn't have them")
        }
        report(Progress.CARD_READY)
        if (!router.enhancedKillSwitch()) notReady("The Enhanced Kill Switch is off")
        report(Progress.KILL_SWITCH_ON)
        if (!router.firewallInPlace()) notReady("Churn's firewall rules aren't in place")
        report(Progress.FIREWALL)
        // WireGuard might send over another uplink instead, past the counters.
        router.routes("main").firstOrNull { it.startsWith("default ") && !SIM_ROUTE.containsMatchIn(it) }?.let {
            notReady("The router has an uplink other than the SIM: $it")
        }
        report(Progress.SIM_ONLY)
    }

    private fun notReady(what: String): Nothing = throw RouterException(Problem.NOT_READY, what)

    /**
     * Commits airplane mode off, as step 4 does, and reads the SIM's traffic every 2 s for 60 s. A packet that left
     * over the SIM without passing Churn's rules stops the test, and so do bytes that got past them beyond those that
     * WireGuard sent, or fewer, as WireGuard's packets then didn't all get past the rules over the SIM.
     */
    private fun watch() {
        // In airplane mode, so that nothing leaves while it is read.
        val start = router.simTraffic()
        router.setAirplaneMode(false)
        report(Progress.GOING_ONLINE)
        val from = now()
        var traffic = start
        val wireguard = WireguardCount(start)
        while (now() - from < WATCH_MILLIS) {
            sleep(READ_MILLIS)
            traffic = router.simTraffic()
            val unseen = sentSince(start, traffic) - passedSince(start, traffic)
            // The interfaces count each fragment that the router made of a packet that passed the rules.
            if (unseen > traffic.fragments - start.fragments) {
                throw RouterException(Problem.LEAK, "$unseen packets left over the SIM that Churn's rules didn't see")
            }
            wireguard.compare(traffic)
        }
        val sent = sentSince(start, traffic)
        val passed = passedSince(start, traffic)
        val dropped = traffic.tried.packets - start.tried.packets - passed
        when {
            !traffic.sent.keys.containsAll(start.sent.keys) -> report(Progress.INTERFACES_CHANGED)
            sent == 0L -> measured(Progress.NOTHING_LEFT, listOf(dropped))
            // No more than the fragments, or the last read would have stopped the test.
            sent > passed -> {
                measured(Progress.FRAGMENTS_UNCLEAR, listOf(sent - passed, traffic.fragments - start.fragments))
            }
            else -> measured(Progress.SIM_PACKETS, listOf(sent, dropped))
        }
        wireguard.matched?.let { measured(Progress.WIREGUARD_MATCHED, listOf(it)) }
            ?: report(Progress.WIREGUARD_UNCLEAR)
    }

    /**
     * The bytes that got past Churn's rules over the SIM, without their IP and UDP headers, against those that
     * WireGuard sent, from [base]: the leak watch's first read, or the first quiet read after a WireGuard interface
     * was made anew, which starts its count again. A read is quiet if WireGuard sent nothing while it was under way.
     */
    private inner class WireguardCount(private var base: SimTraffic) {

        /** WireGuard's bytes from [base] to the last quiet read, if they were exactly those that got past the rules. */
        var matched: Long? = null
            private set

        fun compare(traffic: SimTraffic) {
            val tunnels = base.wireguardAfter.keys
            val counts = listOf(base.wireguardBefore, traffic.wireguardBefore, traffic.wireguardAfter)
            if (counts.any { it.keys != tunnels }) {
                if (quiet(traffic)) {
                    base = traffic
                    report(Progress.TUNNEL_MADE_ANEW)
                }
                return
            }
            val passed = payload(traffic) - payload(base)
            // WireGuard counts a packet after the counters do, so its counts before and after them bound its bytes.
            val most = traffic.wireguardAfter.values.sum() - base.wireguardBefore.values.sum()
            val least = traffic.wireguardBefore.values.sum() - base.wireguardAfter.values.sum()
            if (passed > most) {
                throw RouterException(Problem.LEAK, "${passed - most} bytes got past Churn's rules beyond WireGuard's")
            }
            if (least > passed) {
                throw RouterException(
                    Problem.UNEXPECTED_ANSWER,
                    "wg show all transfer: WireGuard sent ${least - passed} bytes more than got past Churn's rules",
                )
            }
            if (quiet(base) && quiet(traffic) && passed > 0) matched = passed
        }

        private fun quiet(traffic: SimTraffic) = traffic.wireguardBefore == traffic.wireguardAfter

        /** The bytes that got past Churn's rules over the SIM, without the IP and UDP headers that WireGuard adds. */
        private fun payload(traffic: SimTraffic) = traffic.passed4.bytes - IPV4_UDP_HEADERS * traffic.passed4.packets +
            traffic.passed6.bytes - IPV6_UDP_HEADERS * traffic.passed6.packets
    }

    /**
     * The packets that the SIM's interfaces sent from [start] to [traffic]. An interface made anew since counts in
     * full, and one that went away not at all, so that the packets it sent meanwhile are missing.
     */
    private fun sentSince(start: SimTraffic, traffic: SimTraffic) =
        traffic.sent.entries.sumOf { (name, sent) -> sent - (start.sent[name] ?: 0L) }

    /** The packets that got past Churn's rules from [start] to [traffic]. */
    private fun passedSince(start: SimTraffic, traffic: SimTraffic) =
        traffic.passed4.packets + traffic.passed6.packets - start.passed4.packets - start.passed6.packets

    /** Runs [part] of the test, which confirms the claims with the IDs [ids], and returns what it returns. */
    private fun <T> part(vararg ids: String, part: () -> T): T {
        testing = ids.toList()
        return part()
    }

    companion object {
        /** Debug builds have the leak watch. */
        const val AVAILABLE = true

        /** How long the test watches after airplane mode goes off, as testing.md says: a minute. */
        private const val WATCH_MILLIS = 60_000L

        /** How often it reads the SIM's traffic meanwhile. */
        private const val READ_MILLIS = 2_000L

        /**
         * The bytes of the IP and UDP headers of each of WireGuard's packets, over IPv4 and over IPv6, which the
         * counters count and WireGuard doesn't.
         */
        private const val IPV4_UDP_HEADERS = 28
        private const val IPV6_UDP_HEADERS = 48

        /** A route over one of the SIM's data interfaces. */
        private val SIM_ROUTE = Regex(""" dev rmnet_data\d+( |$)""")
    }
}
