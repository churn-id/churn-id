package id.churn

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SwapTest {

    private val fake = FakeRouter()
    private val progress = mutableListOf<Progress>()
    private val swap = Swap(Router(fake), { progress += it }, sleep = { fake.clock += it }, now = { fake.clock })
    private val factory = ImeiPair(fake.imei1, fake.imei2)
    private val secret = ByteArray(32) { it.toByte() }

    private fun prepareAndRestart() {
        swap.prepare(factory, SecureRandom())
        swap.shutDown()
        fake.boot()
    }

    private fun assertStops(problem: Problem, step: () -> Unit) {
        try {
            step()
            fail("The step didn't stop")
        } catch (e: RouterException) {
            assertEquals(problem, e.problem)
        }
    }

    private fun assertSafe() {
        assertEquals(emptyList<String>(), fake.unsafe)
        assertEquals(emptyList<String>(), fake.unlocked)
    }

    @Test
    fun step1ParksSlot2OnTheEsimWithTheRadioOff() {
        val throwaway = swap.prepare(factory, SecureRandom())
        assertEquals(throwaway, ImeiPair(fake.imei1, fake.imei2))
        assertNotEquals(factory, throwaway)
        assertEquals(Imei.offset(factory), Imei.offset(throwaway))
        assertTrue(fake.airplaneMode)
        assertEquals(4, fake.radio)
        assertEquals(Slot2.ESIM, fake.slot2)
        assertNull(fake.esimProfile)
        assertSafe()
        assertEquals(
            listOf(
                Progress.AIRPLANE_MODE_ON, Progress.RADIO_OFF, Progress.THROWAWAY_IMEIS, Progress.SLOT2_ON_ESIM,
                Progress.PROFILE_DISABLED,
            ),
            progress,
        )
        swap.shutDown()
        assertTrue(fake.poweredOff)
    }

    @Test
    fun step1sThrowawayImeisOutlastTheShutdown() {
        val throwaway = swap.prepare(factory, SecureRandom())
        swap.shutDown()
        fake.boot()
        assertEquals(throwaway, ImeiPair(fake.imei1, fake.imei2))
    }

    @Test
    fun step1LeavesAnEsimWithoutProfileAsItIs() {
        fake.esimProfile = null
        swap.prepare(factory, SecureRandom())
        assertTrue(Progress.NO_PROFILE in progress)
        assertTrue(fake.commands.none { "curl" in it })
        assertSafe()
    }

    @Test
    fun step1ChangesNothingWhileTheScheduledRebootIsOn() {
        fake.scheduledReboot = true
        assertStops(Problem.SCHEDULED_REBOOT) { swap.prepare(factory, SecureRandom()) }
        assertEquals(emptyList<String>(), fake.changes)
        assertFalse(fake.airplaneMode)
    }

    @Test
    fun step1ChangesNothingWithoutAVpn() {
        fake.vpnTunnel = null
        assertStops(Problem.NO_VPN) { swap.prepare(factory, SecureRandom()) }
        assertEquals(emptyList<String>(), fake.changes)
        assertFalse(fake.airplaneMode)
    }

    @Test
    fun step1ChangesNothingWhileTheRadioStaysOn() {
        fake.radioStuckOn = true
        assertStops(Problem.RADIO_ON) { swap.prepare(factory, SecureRandom()) }
        assertEquals(emptyList<String>(), fake.changes)
    }

    @Test
    fun step1StopsIfTheRadioComesOnAfterTheThrowawayImeis() {
        // Reading IMEI 2 back is the last command of the write.
        fake.radioOnAfter = "AT+EGMR=0,11"
        assertStops(Problem.RADIO_ON) { swap.prepare(factory, SecureRandom()) }
        assertEquals(Slot2.SIM2, fake.slot2)
        assertSafe()
    }

    @Test
    fun step3WritesTheNewCardsImeisOnceTheRadioIsOff() {
        prepareAndRestart()
        progress.clear()
        val pair = swap.giveNewImeis(factory, secret)
        // The card's ICCID is the first test vector's, 8941000000000000015.
        assertEquals(ImeiPair("356090213059463", "356090213059539"), pair)
        assertEquals(pair, ImeiPair(fake.imei1, fake.imei2))
        assertEquals(Slot2.SIM2, fake.slot2)
        assertEquals(2, fake.activeSlot)
        assertTrue(fake.airplaneMode)
        assertEquals(4, fake.radio)
        assertSafe()
        val firstChange = fake.changes.drop(fake.changes.indexOfFirst { it.startsWith("eSIM") } + 1).first()
        assertTrue(firstChange, firstChange.substringAfter(" at ").substringBefore(" s").toInt() >= 120)
        assertEquals(
            listOf(
                Progress.AIRPLANE_MODE_ON, Progress.WAITING_FOR_START, Progress.RADIO_OFF, Progress.KILL_SWITCH_ON,
                Progress.FIREWALL, Progress.SLOT2_ON_SIM2, Progress.SLOT2_ACTIVE, Progress.CARD_FOUND,
                Progress.NEW_IMEIS, Progress.CLEARING_HISTORY,
            ),
            progress,
        )
    }

    @Test
    fun step3ClearsTheHistoryOfEarlierCards() {
        val newCard = fake.trayCard!!.iccid
        fake.recordCards("8949000077777777777F", newCard)
        prepareAndRestart()
        swap.giveNewImeis(factory, secret)
        assertTrue(Router(fake).flashLogOff())
        assertNull(fake.files["/etc/sys_log"])
        assertEquals(setOf(newCard), fake.cardsInSimMap)
        assertEquals(setOf(newCard), fake.cardsInApnHistory)
        assertEquals(setOf(newCard), fake.cardsInEsimRecords)
        assertTrue(fake.freeSpaceOverwritten)
        assertSafe()
    }

    @Test
    fun step3StopsIfTheRadioComesOnWhileClearing() {
        // With the switch and the rules in place, the log service's reload is step 3's only reload.
        Router(fake).installFirewall()
        prepareAndRestart()
        fake.radioOnAtReload = true
        assertStops(Problem.RADIO_ON) { swap.giveNewImeis(factory, secret) }
        assertEquals(Progress.CLEARING_HISTORY, progress.last())
    }

    @Test
    fun step3LeavesTheHistoryAloneWithoutACard() {
        fake.recordCards("8949000077777777777F", fake.trayCard!!.iccid)
        prepareAndRestart()
        fake.trayCard = null
        assertStops(Problem.NO_CARD) { swap.giveNewImeis(factory, secret) }
        assertFalse(Router(fake).flashLogOff())
        assertEquals(2, fake.cardsInSimMap.size)
        assertFalse(fake.freeSpaceOverwritten)
    }

    @Test
    fun step3TurnsTheKillSwitchOnBeforeTheCardComesOn() {
        prepareAndRestart()
        fake.killSwitchSet = false
        fake.killSwitchInUse = false
        swap.giveNewImeis(factory, secret)
        assertTrue(fake.killSwitchSet && fake.killSwitchInUse)
        val changes = fake.changes.map { it.substringBefore(" at ") }
        assertTrue(changes.indexOf("Enhanced Kill Switch") < changes.lastIndexOf("slot 2"))
        assertSafe()
    }

    @Test
    fun step3LeavesAKillSwitchThatIsOnAsItIs() {
        prepareAndRestart()
        swap.giveNewImeis(factory, secret)
        assertTrue(fake.commands.none { "network reload" in it })
    }

    @Test
    fun step3StopsIfTheRadioComesOnWithTheKillSwitch() {
        prepareAndRestart()
        fake.killSwitchSet = false
        fake.killSwitchInUse = false
        fake.radioOnAtReload = true
        assertStops(Problem.RADIO_ON) { swap.giveNewImeis(factory, secret) }
        assertEquals(Slot2.ESIM, fake.slot2)
        assertSafe()
    }

    @Test
    fun step3PutsTheFirewallRulesInPlaceBeforeTheCardComesOn() {
        prepareAndRestart()
        swap.giveNewImeis(factory, secret)
        assertTrue(Router(fake).firewallInPlace())
        val changes = fake.changes.map { it.substringBefore(" at ") }
        assertTrue(changes.indexOf("firewall") in 0 until changes.lastIndexOf("slot 2"))
        assertSafe()
    }

    @Test
    fun step3StopsIfTheRadioComesOnWithTheFirewallRules() {
        prepareAndRestart()
        fake.radioOnAtReload = true
        assertStops(Problem.RADIO_ON) { swap.giveNewImeis(factory, secret) }
        assertEquals(Slot2.ESIM, fake.slot2)
        assertSafe()
    }

    @Test
    fun step3LeavesFirewallRulesInPlaceAsTheyAre() {
        Router(fake).installFirewall()
        prepareAndRestart()
        val reloads = fake.commands.count { it == "fw4 reload" }
        swap.giveNewImeis(factory, secret)
        assertEquals(reloads, fake.commands.count { it == "fw4 reload" })
    }

    @Test
    fun step3StopsIfTheFirewallRulesDoNotLoad() {
        prepareAndRestart()
        fake.fw4Rejects = true
        assertStops(Problem.FIREWALL_NOT_LOADED) { swap.giveNewImeis(factory, secret) }
        assertNull(fake.files[Router.FIREWALL])
        assertEquals(Slot2.ESIM, fake.slot2)
        assertSafe()
    }

    @Test
    fun step3MakesSlot2TheActiveSlot() {
        fake.activeSlot = 1
        prepareAndRestart()
        swap.giveNewImeis(factory, secret)
        assertEquals(2, fake.activeSlot)
        assertSafe()
    }

    @Test
    fun step3TurnsAirplaneModeOnIfItIsOff() {
        prepareAndRestart()
        fake.airplaneMode = false
        swap.giveNewImeis(factory, secret)
        assertTrue(fake.airplaneMode)
        assertSafe()
    }

    @Test
    fun step3StopsWithoutACard() {
        prepareAndRestart()
        fake.trayCard = null
        val before = ImeiPair(fake.imei1, fake.imei2)
        assertStops(Problem.NO_CARD) { swap.giveNewImeis(factory, secret) }
        assertEquals(before, ImeiPair(fake.imei1, fake.imei2))
        // So that the next power-on keeps the card off.
        assertEquals(Slot2.ESIM, fake.slot2)
        assertEquals(Progress.SLOT2_ON_ESIM, progress.last())
        assertSafe()
    }

    @Test
    fun step3StopsForACardWithoutAnEnabledProfile() {
        prepareAndRestart()
        fake.trayCard = FakeRouter.Card("89000123456789000001", "001010000000001")
        val before = ImeiPair(fake.imei1, fake.imei2)
        assertStops(Problem.NO_CARD) { swap.giveNewImeis(factory, secret) }
        assertEquals(before, ImeiPair(fake.imei1, fake.imei2))
        assertEquals(Slot2.ESIM, fake.slot2)
    }

    @Test
    fun step3WorksAfterACardWasMissing() {
        prepareAndRestart()
        val card = fake.trayCard
        fake.trayCard = null
        assertStops(Problem.NO_CARD) { swap.giveNewImeis(factory, secret) }
        fake.trayCard = card
        fake.boot()
        assertEquals(ImeiPair("356090213059463", "356090213059539"), swap.giveNewImeis(factory, secret))
        assertSafe()
    }

    @Test
    fun step3ChangesNothingWhileTheRadioStaysOn() {
        prepareAndRestart()
        fake.radioStuckOn = true
        val changes = fake.changes.size
        assertStops(Problem.RADIO_ON) { swap.giveNewImeis(factory, secret) }
        assertEquals(changes, fake.changes.size)
        assertEquals(Slot2.ESIM, fake.slot2)
    }

    @Test
    fun step3ChangesNothingIfTheRouterDoesNotFinishStarting() {
        prepareAndRestart()
        fake.uptimeBroken = true
        val changes = fake.changes.size
        assertStops(Problem.NOT_STARTED) { swap.giveNewImeis(factory, secret) }
        assertEquals(changes, fake.changes.size)
        assertEquals(Slot2.ESIM, fake.slot2)
    }

    @Test
    fun stepsCanRunAgain() {
        swap.prepare(factory, SecureRandom())
        swap.prepare(factory, SecureRandom())
        swap.shutDown()
        fake.boot()
        val pair = swap.giveNewImeis(factory, secret)
        assertEquals(pair, swap.giveNewImeis(factory, secret))
        assertSafe()
    }

    @Test
    fun keepingTheOldCardGivesItsImeisBack() {
        prepareAndRestart()
        val first = swap.giveNewImeis(factory, secret)
        prepareAndRestart()
        assertEquals(first, swap.giveNewImeis(factory, secret))
    }
}
