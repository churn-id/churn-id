package id.churn

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeakWatchTest {

    private val fake = FakeRouter().apply {
        // As step 3 leaves the router, switched on again where it is to go online: airplane mode on, Churn's firewall
        // rules in place, and the card in the SIM 2 tray, which slot 2 is on and is the active slot.
        airplaneMode = true
        Router(this).installFirewall()
        commands.clear()
        changes.clear()
    }

    /** The card that step 3 gave its IMEIs, as Churn's folder records it: the router has them. */
    private val card = Card(Imei.iccidDigits(fake.trayCard!!.iccid)!!, ImeiPair(fake.imei1, fake.imei2))

    /** Runs after each command that [fake] answered, with the command. */
    private var after: (String) -> Unit = {}

    /** The commands run privately, whose answers the debug log leaves out. */
    private val privately = mutableListOf<String>()
    private val shell = object : Shell {
        override fun run(command: String, input: ByteArray?, timeoutSeconds: Long) =
            fake.run(command, input, timeoutSeconds).also { after(command) }

        override fun runPrivately(command: String, input: ByteArray?, timeoutSeconds: Long): Output {
            privately += command
            return run(command, input, timeoutSeconds)
        }
    }
    private val progress = mutableListOf<Progress>()

    /** The numbers that the lines naming numbers named, in turn. */
    private val numbers = mutableListOf<Long>()

    /** The claims that the test's parts named while they reported. */
    private val claims = mutableSetOf<String>()

    /** Runs where the leak watch runs the router checks, and returns the lines of those that failed. */
    private var checks = { emptyList<String>() }

    private val leakWatch = leakWatch()

    private fun leakWatch(): LeakWatch {
        lateinit var made: LeakWatch
        made = LeakWatch(
            Router(shell),
            report = {
                progress += it
                claims += made.testing
            },
            measured = { line, values ->
                progress += line
                numbers += values
                claims += made.testing
            },
            check = { checks() },
            sleep = { fake.clock += it },
            now = { fake.clock },
        )
        return made
    }

    private fun run(card: Card? = this.card) = leakWatch.run(card)

    /** Whether [command] reads the SIM's traffic. */
    private fun readsTraffic(command: String) = command.startsWith("cd /sys/class/net || exit 1\n")

    /** Whether [command] reads WireGuard's latest handshake and chrony's reference. */
    private fun readsTunnelClock(command: String) = command.startsWith("h=\$(wg show all latest-handshakes)")

    private fun assertStops(problem: Problem, run: () -> Unit): RouterException {
        try {
            run()
        } catch (e: RouterException) {
            assertEquals(problem, e.problem)
            return e
        }
        throw AssertionError("The leak watch didn't stop")
    }

    /**
     * Asserts that the test stopped before it turned airplane mode off or added its counters, and returns what
     * it named as the reason.
     */
    private fun assertStoppedOffline(problem: Problem, watch: () -> Unit = { run() }): String? {
        val e = assertStops(problem, watch)
        assertTrue(fake.airplaneMode)
        assertFalse(fake.commands.any { it.startsWith("uci set 'glconfig.general.airplane_mode=0'") })
        assertFalse("nft -f -" in fake.commands)
        assertNull(fake.leakCounters)
        assertEquals(emptyList<String>(), fake.changes)
        return e.detail
    }

    /**
     * Asserts that the test stopped while it watched, with airplane mode turned on again and its counters gone, and
     * returns why.
     */
    private fun assertStoppedWatching(problem: Problem): String? {
        val e = assertStops(problem) { run() }
        assertEquals(listOf("D9", "D11", "D16"), leakWatch.testing)
        assertEquals(Progress.AIRPLANE_MODE_ON, progress.last())
        assertTrue(fake.airplaneMode)
        assertNull(fake.leakCounters)
        return e.detail
    }

    private fun assertSafe() {
        assertEquals(emptyList<String>(), fake.unsafe)
        assertEquals(emptyList<String>(), fake.unlocked)
    }

    @Test
    fun leakWatchGoesOnlineAndCountsWhatLeavesOverTheSimForAMinute() {
        run()
        assertEquals(
            listOf(
                Progress.AIRPLANE_MODE_ON, Progress.WAITING_FOR_START, Progress.RADIO_OFF, Progress.SIM1_EMPTY,
                Progress.CARD_READY, Progress.KILL_SWITCH_ON, Progress.FIREWALL, Progress.SIM_ONLY,
                Progress.GOING_ONLINE, Progress.HANDSHAKE, Progress.CLOCK_SYNCED, Progress.SIM_PACKETS,
                Progress.WIREGUARD_MATCHED, Progress.LEAK_WATCH_PASSED,
            ),
            progress,
        )
        // WireGuard's handshake 3 s after airplane mode went off, seen at the read at 4 s, and then 5 packets a
        // second; the clock synced at 20 s; the router's own packets that Churn's rules dropped; WireGuard's bytes.
        assertEquals(listOf(4L, 20L, 286L, 8L, 28_600L), numbers)
        // Once in airplane mode, then every 2 s for 60 s.
        assertEquals(31, fake.commands.count(::readsTraffic))
        assertEquals(31, fake.commands.count(::readsTunnelClock))
        val commit = fake.commands.indexOf("uci set 'glconfig.general.airplane_mode=0' && uci commit 'glconfig'")
        assertTrue(fake.commands.indexOfFirst(::readsTraffic) in fake.commands.indexOf("nft -f -")..commit)
        // As step 4 leaves the router.
        assertFalse(fake.airplaneMode)
        assertNull(fake.leakCounters)
        assertEquals(emptyList<String>(), fake.changes)
        assertTrue(leakWatch.testing.isEmpty())
        assertSafe()
    }

    @Test
    fun leakWatchConfirmsTier4ClaimsOfTestingMd() {
        run()
        assertEquals(setOf("D9", "D11", "D16"), claims)
        for (id in claims) {
            val tiers = ChecksTest.TIERS[id] ?: throw AssertionError("$id isn't a claim in testing.md")
            assertTrue("$id isn't a tier 4 claim in testing.md", "4" in tiers)
        }
    }

    @Test
    fun packetsThatChurnsRulesDidNotSeeStopTheWatch() {
        fake.unseenGoingOnline = 1
        assertStoppedWatching(Problem.LEAK)
        // At the first read, 2 s after airplane mode went off.
        assertEquals(2, fake.commands.count(::readsTraffic))
        assertEquals(listOf(Progress.GOING_ONLINE, Progress.AIRPLANE_MODE_ON), progress.takeLast(2))
        assertTrue("nft delete table inet churn_watch" in fake.commands)
    }

    @Test
    fun fragmentedWireGuardPacketsLeaveD11Unconfirmed() {
        fake.fragmentsWireguard = true
        run()
        assertEquals(Progress.FRAGMENTS_UNCLEAR, progress[progress.size - 3])
        // Each of WireGuard's 286 packets left as two fragments.
        assertEquals(listOf(4L, 20L, 286L, 572L, 28_600L), numbers)
        assertFalse(fake.airplaneMode)
    }

    @Test
    fun fragmentsMadeBeforeTheWatchExcuseNoPackets() {
        fake.fragmentsMade = 1_000
        fake.unseenGoingOnline = 1
        assertStoppedWatching(Problem.LEAK)
    }

    @Test
    fun aSimInterfaceMadeAnewLeavesD11Unconfirmed() {
        fake.remakesInterface = true
        run()
        assertEquals(Progress.INTERFACES_CHANGED, progress[progress.size - 3])
        assertEquals(listOf(4L, 20L, 28_600L), numbers)
    }

    @Test
    fun aSimInterfaceMadeAnewHidesNoPacketsThatItSent() {
        fake.remakesInterface = true
        fake.unseenGoingOnline = 1
        assertStoppedWatching(Problem.LEAK)
    }

    @Test
    fun noHandshakeWithinAMinuteStopsTheWatch() {
        fake.handshakeMillis = 120_000
        assertEquals(
            "wg show all latest-handshakes: no new handshake within 60 s",
            assertStoppedWatching(Problem.UNEXPECTED_ANSWER),
        )
        assertEquals(31, fake.commands.count(::readsTraffic))
    }

    @Test
    fun anEarlierHandshakeIsNoNewOne() {
        fake.latestHandshake = fake.time - 3_600
        run()
        assertEquals(4L, numbers.first())
    }

    @Test
    fun aClockThatSyncsLaterKeepsTheTestWatching() {
        fake.clockSyncMillis = 100_000
        run()
        assertEquals(Progress.LEAK_WATCH_PASSED, progress.last())
        assertEquals(listOf(4L, 100L, 486L, 8L, 48_600L), numbers)
        assertEquals(51, fake.commands.count(::readsTraffic))
    }

    @Test
    fun aClockThatDoesNotSyncWithin5MinutesStopsTheWatch() {
        fake.clockSyncMillis = null
        assertEquals("chronyc tracking: no update within 300 s", assertStoppedWatching(Problem.UNEXPECTED_ANSWER))
        assertEquals(151, fake.commands.count(::readsTraffic))
    }

    @Test
    fun chronyTakingItsOwnClockIsNoSync() {
        fake.chronyLocal = true
        assertEquals("chronyc tracking: no update within 300 s", assertStoppedWatching(Problem.UNEXPECTED_ANSWER))
    }

    @Test
    fun theHandshakeAndTheClockStayOutOfTheDebugLog() {
        run()
        assertEquals(fake.commands.count(::readsTunnelClock), privately.count(::readsTunnelClock))
    }

    @Test
    fun aRouterOnlineAtTheStartGoesOfflineBeforeTheWatch() {
        fake.airplaneMode = false
        // Online for 30 s first, so that the counts don't start from nothing.
        Router(fake).airplaneMode()
        fake.clock += 30_000
        run()
        assertTrue("uci set 'glconfig.general.airplane_mode=1' && uci commit 'glconfig'" in fake.commands)
        assertEquals(Progress.LEAK_WATCH_PASSED, progress.last())
        assertEquals(listOf(4L, 20L, 286L, 8L, 28_600L), numbers)
    }

    @Test
    fun markedPacketsOtherThanWireGuardsStopTheWatch() {
        fake.markedGoingOnline = 1
        // A ping's 84 bytes without the 28 of the IP and UDP headers that WireGuard's packets have.
        assertEquals("56 bytes got past Churn's rules beyond WireGuard's", assertStoppedWatching(Problem.LEAK))
        // At the first read, 2 s after airplane mode went off.
        assertEquals(2, fake.commands.count(::readsTraffic))
    }

    @Test
    fun wireguardPacketsThatChurnsRulesDropStopTheWatch() {
        fake.wireguardUnmarked = true
        // The handshake at 3 s and 5 more packets by the read at 4 s.
        assertEquals(
            "wg show all transfer: WireGuard sent 600 bytes more than got past Churn's rules",
            assertStoppedWatching(Problem.UNEXPECTED_ANSWER),
        )
        assertEquals(3, fake.commands.count(::readsTraffic))
    }

    @Test
    fun aTunnelMadeAnewIsComparedFromTheNextQuietRead() {
        fake.tunnelRemadeAfter = 9_000
        run()
        assertEquals(1, progress.count { it == Progress.TUNNEL_MADE_ANEW })
        // From the read at 10 s, with 36 of WireGuard's packets by then.
        assertEquals(listOf(4L, 20L, 286L, 8L, 25_000L), numbers)
    }

    @Test
    fun wireguardSendingWhileEachReadIsUnderWayLeavesD9Unconfirmed() {
        fake.wireguardWhileRead = true
        run()
        assertEquals(
            listOf(Progress.SIM_PACKETS, Progress.WIREGUARD_UNCLEAR, Progress.LEAK_WATCH_PASSED),
            progress.takeLast(3),
        )
        // One more packet during each of the 30 reads, after the interfaces' counts.
        assertEquals(listOf(4L, 20L, 315L, 8L), numbers)
    }

    @Test
    fun aTunnelOverIpv6IsCountedAlike() {
        fake.tunnelOverIpv6 = true
        run()
        assertEquals(Progress.LEAK_WATCH_PASSED, progress.last())
        assertEquals(listOf(286L, 8L, 28_600L), numbers.takeLast(3))
    }

    @Test
    fun anUplinkOtherThanTheSimStopsTheWatchBeforeItGoesOnline() {
        fake.wiredUplink = true
        assertEquals(
            "The router has an uplink other than the SIM: " +
                "default via 192.168.1.1 dev eth0 proto static src 192.168.1.2 metric 10",
            assertStoppedOffline(Problem.NOT_READY),
        )
        assertEquals(Progress.FIREWALL, progress.last())
    }

    @Test
    fun theSimsOwnDefaultRouteInAirplaneModeIsNoOtherUplink() {
        fake.keepsSimRoute = true
        run()
        assertEquals(Progress.LEAK_WATCH_PASSED, progress.last())
    }

    @Test
    fun countersThatGoAwayStopTheWatch() {
        var reads = 0
        after = { if (readsTraffic(it) && ++reads == 3) fake.leakCounters = null }
        assertStoppedWatching(Problem.UNEXPECTED_ANSWER)
    }

    @Test
    fun aCardInTheSim1TrayStopsTheWatchBeforeItGoesOnline() {
        fake.tray1Card = FakeRouter.Card("8941000000000000023F", "228010000000002")
        assertEquals("A card answers in the SIM 1 tray", assertStoppedOffline(Problem.NOT_READY))
        assertEquals(Progress.RADIO_OFF, progress.last())
    }

    @Test
    fun slot2OnTheEsimStopsTheWatchBeforeItGoesOnline() {
        fake.slot2 = Slot2.ESIM
        assertEquals(NOT_ON_SIM2, assertStoppedOffline(Problem.NOT_READY))
        assertEquals(Progress.SIM1_EMPTY, progress.last())
    }

    @Test
    fun slot1ActiveStopsTheWatchBeforeItGoesOnline() {
        fake.activeSlot = 1
        assertEquals(NOT_ON_SIM2, assertStoppedOffline(Problem.NOT_READY))
    }

    @Test
    fun noCardInSim2StopsTheWatchBeforeItGoesOnline() {
        fake.trayCard = null
        assertEquals(NOT_THE_CARD, assertStoppedOffline(Problem.NOT_READY))
        assertEquals(Progress.SIM1_EMPTY, progress.last())
    }

    @Test
    fun anotherCardInSim2StopsTheWatchBeforeItGoesOnline() {
        fake.trayCard = FakeRouter.Card("8941000000000000098F", "228010000000009")
        assertEquals(NOT_THE_CARD, assertStoppedOffline(Problem.NOT_READY))
    }

    @Test
    fun imeisThatAreNotTheCardsStopTheWatchBeforeItGoesOnline() {
        val other = Imei.random(Serials(123_456), card.imeis)
        assertEquals(NOT_THE_CARD, assertStoppedOffline(Problem.NOT_READY) { run(card.copy(imeis = other)) })
    }

    @Test
    fun noCardInChurnsFolderStopsTheWatchBeforeItGoesOnline() {
        assertEquals(NOT_THE_CARD, assertStoppedOffline(Problem.NOT_READY) { run(null) })
    }

    @Test
    fun aKillSwitchOffStopsTheWatchBeforeItGoesOnline() {
        fake.killSwitchSet = false
        fake.killSwitchInUse = false
        assertEquals("The Enhanced Kill Switch is off", assertStoppedOffline(Problem.NOT_READY))
        assertEquals(Progress.CARD_READY, progress.last())
    }

    @Test
    fun churnsRulesNotInPlaceStopTheWatchBeforeItGoesOnline() {
        fake.loadedChains.clear()
        assertEquals("Churn's firewall rules aren't in place", assertStoppedOffline(Problem.NOT_READY))
        assertEquals(Progress.KILL_SWITCH_ON, progress.last())
    }

    @Test
    fun noVpnStopsTheWatchBeforeItGoesOnline() {
        fake.vpnTunnel = null
        assertStoppedOffline(Problem.NO_VPN)
    }

    @Test
    fun aFailedCheckStopsTheWatchBeforeItGoesOnline() {
        checks = { listOf("✗ A check (D7)\nFailed: one thing") }
        assertEquals("✗ A check (D7)\nFailed: one thing", assertStoppedOffline(Problem.CHECK_FAILED))
    }

    /** Gives [values] in turn, as the serials of test pairs. */
    private class Serials(private vararg val values: Int) : SecureRandom() {
        private var next = 0

        override fun nextInt(bound: Int) = values[next++]
    }

    companion object {
        /** Why the test stops with slot 2 not on the SIM 2 tray, or not active. */
        private const val NOT_ON_SIM2 = "Slot 2 isn't on the SIM 2 tray or not active"

        /** Why the test stops without the card that step 3 last gave its IMEIs, with them. */
        private const val NOT_THE_CARD =
            "The card in SIM 2 isn't the one that step 3 last gave its IMEIs, or doesn't have them"
    }
}
