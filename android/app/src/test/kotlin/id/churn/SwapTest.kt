package id.churn

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwapTest {

    private val fake = FakeRouter()
    private val progress = mutableListOf<Progress>()
    private val asked = mutableListOf<Question>()

    /** The user's answer to step 3's question. */
    private var answer = Answer.TRAVEL_CARD

    /** Runs while step 3 waits for the answer. */
    private var whileAsked = {}
    private var placesJson: String? = null
    private val places = PlaceCards(load = { placesJson }, save = { placesJson = it })
    private val swap = Swap(
        Router(fake),
        { progress += it },
        ask = { question ->
            asked += question
            whileAsked()
            answer
        },
        sleep = { fake.clock += it },
        now = { fake.clock },
    )
    private val factory = ImeiPair(fake.imei1, fake.imei2)

    /** Step 1, the shutdown and the next power-on. Returns the IMEIs the router had, which stay until step 3. */
    private fun prepareAndRestart(): ImeiPair {
        val oldImeis = ImeiPair(fake.imei1, fake.imei2)
        swap.prepare()
        swap.shutDown()
        fake.boot()
        return oldImeis
    }

    /** Step 3, with the record that the app reads first. Null if the user canceled. */
    private fun giveNewImeis() = swap.giveNewImeis(factory, record(), places, SecureRandom())

    private fun record() = Router(fake).readChurnFolder().card

    private fun assertStops(problem: Problem, step: () -> Unit): RouterException {
        try {
            step()
        } catch (e: RouterException) {
            assertEquals(problem, e.problem)
            return e
        }
        throw AssertionError("The step didn't stop")
    }

    private fun assertSafe() {
        assertEquals(emptyList<String>(), fake.unsafe)
        assertEquals(emptyList<String>(), fake.unlocked)
    }

    @Test
    fun step1ParksSlot2OnTheEsimWithTheRadioOff() {
        swap.prepare()
        assertTrue(fake.airplaneMode)
        assertEquals(4, fake.radio)
        assertEquals(Slot2.ESIM, fake.slot2)
        assertNull(fake.esimProfile)
        assertSafe()
        assertEquals(
            listOf(Progress.AIRPLANE_MODE_ON, Progress.RADIO_OFF, Progress.SLOT2_ON_ESIM, Progress.PROFILE_DISABLED),
            progress,
        )
        swap.shutDown()
        assertTrue(fake.poweredOff)
        assertEquals(Progress.SHUTTING_DOWN, progress.last())
    }

    @Test
    fun step3StartsOnlyOnceTheRouterHasRestarted() {
        swap.prepare()
        val bootId = swap.bootId()
        // Still shutting down after step 1, or not shut down at all.
        assertFalse(swap.restartedSince(bootId))
        swap.shutDown()
        fake.boot()
        assertTrue(swap.restartedSince(bootId))
        // A swap that an earlier version of the app began, which kept no boot ID.
        assertTrue(swap.restartedSince(null))
    }

    @Test
    fun step1LeavesTheImeisAsTheyAre() {
        val oldImeis = prepareAndRestart()
        assertEquals(oldImeis, ImeiPair(fake.imei1, fake.imei2))
        assertTrue(fake.commands.none { "AT+EGMR=1," in it })
    }

    @Test
    fun step1LeavesAnEsimWithoutProfileAsItIs() {
        fake.esimProfile = null
        swap.prepare()
        assertTrue(Progress.NO_PROFILE in progress)
        assertTrue(fake.commands.none { "curl" in it })
        assertSafe()
    }

    @Test
    fun step1StopsWithTheEsimManagersAnswerIfTheProfileStaysEnabled() {
        fake.esimRequestsIgnored = 1
        val stop = assertStops(Problem.PROFILE_STILL_ENABLED) { swap.prepare() }
        assertEquals("""http://127.0.0.1:3456/sdk/v1: {"tx_id":"","code":200,"message":"ok"}""", stop.detail)
        assertEquals("8944000000000000123F", fake.esimProfile)
        // So that the user can disable the profile in GL's web interface and run the step again.
        assertTrue(fake.airplaneMode)
        assertEquals(Slot2.ESIM, fake.slot2)
        assertSafe()
    }

    @Test
    fun step1ChangesNothingWhileTheScheduledRebootIsOn() {
        fake.scheduledReboot = true
        assertStops(Problem.SCHEDULED_REBOOT) { swap.prepare() }
        assertEquals(emptyList<String>(), fake.changes)
        assertFalse(fake.airplaneMode)
    }

    @Test
    fun step1ChangesNothingWithoutAVpn() {
        fake.vpnTunnel = null
        assertStops(Problem.NO_VPN) { swap.prepare() }
        assertEquals(emptyList<String>(), fake.changes)
        assertFalse(fake.airplaneMode)
    }

    @Test
    fun step1ChangesNothingWhileTheRadioStaysOn() {
        fake.radioStuckOn = true
        assertStops(Problem.RADIO_ON) { swap.prepare() }
        assertEquals(emptyList<String>(), fake.changes)
    }

    @Test
    fun step3WritesTheNewCardsImeisOnceTheRadioIsOff() {
        val oldImeis = prepareAndRestart()
        progress.clear()
        val card = giveNewImeis()!!
        assertEquals("8941000000000000015", card.iccid)
        assertTrue(Imei.isValid(card.imeis.imei1) && Imei.isValid(card.imeis.imei2))
        assertEquals(Imei.offset(factory), Imei.offset(card.imeis))
        assertNotEquals(oldImeis, card.imeis)
        assertEquals(card.imeis, ImeiPair(fake.imei1, fake.imei2))
        assertEquals(card, record())
        assertEquals(Slot2.SIM2, fake.slot2)
        assertEquals(2, fake.activeSlot)
        assertTrue(fake.airplaneMode)
        assertEquals(4, fake.radio)
        assertSafe()
        val firstChange = fake.changes.drop(fake.changes.indexOfFirst { it.startsWith("eSIM") } + 1).first()
        assertTrue(firstChange, firstChange.substringAfter(" at ").substringBefore(" s").toInt() >= 120)
        assertEquals(
            listOf(
                Progress.AIRPLANE_MODE_ON, Progress.WAITING_FOR_START, Progress.RADIO_OFF, Progress.SIM1_EMPTY,
                Progress.KILL_SWITCH_ON, Progress.FIREWALL, Progress.SLOT2_ON_SIM2, Progress.SLOT2_ACTIVE,
                Progress.CARD_FOUND,
                // The question, with slot 2 on the eSIM until the answer.
                Progress.SLOT2_ON_ESIM, Progress.SLOT2_BACK_ON_SIM2, Progress.SAME_CARD,
                Progress.NEW_IMEIS, Progress.CLEARING_HISTORY,
            ),
            progress,
        )
    }

    @Test
    fun step3ClearsTheHistoryOfEarlierCards() {
        val newCard = fake.trayCard!!.iccid
        fake.recordCards("8949000077777777777F", newCard)
        // As on a router set up by an earlier version of the app.
        fake.files["/etc/churn/secret"] = ByteArray(32)
        prepareAndRestart()
        giveNewImeis()
        assertNull(fake.files["/etc/churn/secret"])
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
        assertStops(Problem.RADIO_ON) { giveNewImeis() }
        assertEquals(Progress.CLEARING_HISTORY, progress.last())
    }

    @Test
    fun step3LeavesTheHistoryAloneWithoutACard() {
        fake.recordCards("8949000077777777777F", fake.trayCard!!.iccid)
        prepareAndRestart()
        fake.trayCard = null
        assertStops(Problem.NO_CARD) { giveNewImeis() }
        assertFalse(Router(fake).flashLogOff())
        assertEquals(2, fake.cardsInSimMap.size)
        assertFalse(fake.freeSpaceOverwritten)
    }

    @Test
    fun step3TurnsTheKillSwitchOnBeforeTheCardComesOn() {
        prepareAndRestart()
        fake.killSwitchSet = false
        fake.killSwitchInUse = false
        giveNewImeis()
        assertTrue(fake.killSwitchSet && fake.killSwitchInUse)
        val changes = fake.changes.map { it.substringBefore(" at ") }
        assertTrue(changes.indexOf("Enhanced Kill Switch") < changes.lastIndexOf("slot 2"))
        assertSafe()
    }

    @Test
    fun step3LeavesAKillSwitchThatIsOnAsItIs() {
        prepareAndRestart()
        giveNewImeis()
        assertTrue(fake.commands.none { "network reload" in it })
    }

    @Test
    fun step3StopsIfTheRadioComesOnWithTheKillSwitch() {
        prepareAndRestart()
        fake.killSwitchSet = false
        fake.killSwitchInUse = false
        fake.radioOnAtReload = true
        assertStops(Problem.RADIO_ON) { giveNewImeis() }
        assertEquals(Slot2.ESIM, fake.slot2)
        assertSafe()
    }

    @Test
    fun step3PutsTheFirewallRulesInPlaceBeforeTheCardComesOn() {
        prepareAndRestart()
        giveNewImeis()
        assertTrue(Router(fake).firewallInPlace())
        val changes = fake.changes.map { it.substringBefore(" at ") }
        assertTrue(changes.indexOf("firewall") in 0 until changes.lastIndexOf("slot 2"))
        assertSafe()
    }

    @Test
    fun step3StopsIfTheRadioComesOnWithTheFirewallRules() {
        prepareAndRestart()
        fake.radioOnAtReload = true
        assertStops(Problem.RADIO_ON) { giveNewImeis() }
        assertEquals(Slot2.ESIM, fake.slot2)
        assertSafe()
    }

    @Test
    fun step3LeavesFirewallRulesInPlaceAsTheyAre() {
        Router(fake).installFirewall()
        prepareAndRestart()
        val reloads = fake.commands.count { it == "fw4 reload" }
        giveNewImeis()
        assertEquals(reloads, fake.commands.count { it == "fw4 reload" })
    }

    @Test
    fun step3StopsIfTheFirewallRulesDoNotLoad() {
        prepareAndRestart()
        fake.fw4Rejects = true
        assertStops(Problem.FIREWALL_NOT_LOADED) { giveNewImeis() }
        assertNull(fake.files[Router.FIREWALL])
        assertEquals(Slot2.ESIM, fake.slot2)
        assertSafe()
    }

    @Test
    fun step3MakesSlot2TheActiveSlot() {
        fake.activeSlot = 1
        prepareAndRestart()
        giveNewImeis()
        assertEquals(2, fake.activeSlot)
        assertSafe()
    }

    @Test
    fun step3TurnsAirplaneModeOnIfItIsOff() {
        prepareAndRestart()
        fake.airplaneMode = false
        giveNewImeis()
        assertTrue(fake.airplaneMode)
        assertSafe()
    }

    @Test
    fun step3StopsWithoutACard() {
        prepareAndRestart()
        fake.trayCard = null
        val before = ImeiPair(fake.imei1, fake.imei2)
        assertStops(Problem.NO_CARD) { giveNewImeis() }
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
        assertStops(Problem.NO_CARD) { giveNewImeis() }
        assertEquals(before, ImeiPair(fake.imei1, fake.imei2))
        assertEquals(Slot2.ESIM, fake.slot2)
    }

    @Test
    fun step3StopsForA9esimCardWithoutAnEnabledProfile() {
        prepareAndRestart()
        // As ours showed itself.
        fake.trayCard = FakeRouter.Card("89111111111111111111", "111111111111111")
        assertStops(Problem.NO_CARD) { giveNewImeis() }
        assertNull(record())
        assertEquals(Slot2.ESIM, fake.slot2)
    }

    @Test
    fun step3WorksAfterACardWasMissing() {
        prepareAndRestart()
        val card = fake.trayCard
        fake.trayCard = null
        assertStops(Problem.NO_CARD) { giveNewImeis() }
        fake.trayCard = card
        fake.boot()
        assertEquals(giveNewImeis()!!.imeis, ImeiPair(fake.imei1, fake.imei2))
        assertSafe()
    }

    @Test
    fun step3sImeisOutlastTheShutdown() {
        prepareAndRestart()
        val card = giveNewImeis()!!
        swap.shutDown()
        assertTrue(fake.poweredOff)
        fake.boot()
        assertEquals(card.imeis, ImeiPair(fake.imei1, fake.imei2))
    }

    @Test
    fun step3RunAgainKeepsTheRecordedPair() {
        prepareAndRestart()
        fake.imeiWritesRefused = true
        assertStops(Problem.UNEXPECTED_ANSWER) { giveNewImeis() }
        val recorded = record()!!
        fake.imeiWritesRefused = false
        assertEquals(recorded, giveNewImeis())
        assertEquals(recorded.imeis, ImeiPair(fake.imei1, fake.imei2))
        // The recorded card brings no question.
        assertEquals(1, asked.size)
    }

    @Test
    fun aNewCardGetsANewPairAndReplacesTheRecord() {
        val firstCard = fake.trayCard
        prepareAndRestart()
        val first = giveNewImeis()!!
        prepareAndRestart()
        fake.trayCard = OTHER_CARD
        val second = giveNewImeis()!!
        assertEquals("8949000077777777777", second.iccid)
        assertNotEquals(first.imeis, second.imeis)
        assertEquals(second, record())
        // Used again after another card, the first card gets a new pair too.
        prepareAndRestart()
        fake.trayCard = firstCard
        val third = giveNewImeis()!!
        assertEquals(first.iccid, third.iccid)
        assertNotEquals(first.imeis, third.imeis)
    }

    @Test
    fun step3WritesNothingBeforeTheAnswer() {
        prepareAndRestart()
        val first = giveNewImeis()
        val oldImeis = prepareAndRestart()
        fake.trayCard = OTHER_CARD
        whileAsked = {
            assertEquals(first, record())
            assertEquals(oldImeis, ImeiPair(fake.imei1, fake.imei2))
            // So that the card stays off if the router is switched off and on before the answer.
            assertEquals(Slot2.ESIM, fake.slot2)
            assertSafe()
        }
        val second = giveNewImeis()!!
        assertEquals(Question.NewCard(second.iccid), asked.last())
        assertEquals(second, record())
        // A travel card stays off the place list.
        assertEquals(emptyList<PlaceCard>(), places.all)
    }

    @Test
    fun aNewPlaceCardJoinsThePlaceListBeforeItsImeisAreWritten() {
        val oldImeis = prepareAndRestart()
        answer = Answer.PLACE_CARD
        fake.imeiWritesRefused = true
        assertStops(Problem.UNEXPECTED_ANSWER) { giveNewImeis() }
        assertEquals(oldImeis, ImeiPair(fake.imei1, fake.imei2))
        assertEquals(listOf(PlaceCard(record()!!)), places.all)
    }

    @Test
    fun aPlaceCardGetsItsKeptPairWhenTheUserContinues() {
        val placeCard = fake.trayCard
        prepareAndRestart()
        answer = Answer.PLACE_CARD
        val place = giveNewImeis()!!
        prepareAndRestart()
        fake.trayCard = OTHER_CARD
        answer = Answer.TRAVEL_CARD
        giveNewImeis()
        prepareAndRestart()
        fake.trayCard = placeCard
        answer = Answer.CONTINUE
        assertEquals(place, giveNewImeis())
        assertEquals(Question.KnownPlaceCard(PlaceCard(place)), asked.last())
        assertEquals(place.imeis, ImeiPair(fake.imei1, fake.imei2))
        assertEquals(place, record())
    }

    @Test
    fun cancelingLeavesSlot2OnTheEsimAndWritesNothing() {
        val oldImeis = prepareAndRestart()
        answer = Answer.CANCEL
        assertNull(giveNewImeis())
        // Before the shutdown, so that the card stays off at the next power-on.
        assertEquals(Slot2.ESIM, fake.slot2)
        assertEquals(oldImeis, ImeiPair(fake.imei1, fake.imei2))
        assertNull(record())
        assertEquals(emptyList<PlaceCard>(), places.all)
        assertFalse(fake.freeSpaceOverwritten)
        assertSafe()
        // Back at step 2, the user puts in another card.
        swap.shutDown()
        fake.trayCard = OTHER_CARD
        fake.boot()
        answer = Answer.TRAVEL_CARD
        assertEquals(giveNewImeis(), record())
    }

    @Test
    fun step3StopsIfTheCardChangesWhileItAsks() {
        val oldImeis = prepareAndRestart()
        whileAsked = { fake.trayCard = OTHER_CARD }
        assertStops(Problem.CARD_CHANGED) { giveNewImeis() }
        assertFalse(Progress.SAME_CARD in progress)
        assertEquals(oldImeis, ImeiPair(fake.imei1, fake.imei2))
        assertNull(record())
        assertEquals(Slot2.ESIM, fake.slot2)
        assertSafe()
    }

    @Test
    fun step3ChangesNothingWhileTheRadioStaysOn() {
        prepareAndRestart()
        fake.radioStuckOn = true
        val changes = fake.changes.size
        assertStops(Problem.RADIO_ON) { giveNewImeis() }
        assertEquals(changes, fake.changes.size)
        assertEquals(Slot2.ESIM, fake.slot2)
    }

    @Test
    fun step3StopsWhileTheCardIsOffIfTheVpnIsGone() {
        val oldImeis = prepareAndRestart()
        fake.vpnTunnel = null
        progress.clear()
        val changes = fake.changes.size
        assertStops(Problem.VPN_GONE) { giveNewImeis() }
        // Before the wait for the router to finish starting.
        assertEquals(listOf(Progress.AIRPLANE_MODE_ON), progress)
        assertEquals(changes, fake.changes.size)
        assertEquals(oldImeis, ImeiPair(fake.imei1, fake.imei2))
        assertEquals(Slot2.ESIM, fake.slot2)
        // Run again once a VPN is set up.
        fake.vpnTunnel = "wgclient1"
        val card = giveNewImeis()!!
        assertEquals(card.imeis, ImeiPair(fake.imei1, fake.imei2))
        assertSafe()
    }

    @Test
    fun step3StopsWhileTheCardIsOffIfTheSim1TrayHoldsACard() {
        val oldImeis = prepareAndRestart()
        fake.tray1Card = OTHER_CARD
        progress.clear()
        val changes = fake.changes.size
        assertStops(Problem.SIM1_CARD) { giveNewImeis() }
        // Once the router has finished starting, before anything changes.
        assertEquals(listOf(Progress.AIRPLANE_MODE_ON, Progress.WAITING_FOR_START, Progress.RADIO_OFF), progress)
        assertEquals(changes, fake.changes.size)
        assertEquals(oldImeis, ImeiPair(fake.imei1, fake.imei2))
        assertEquals(Slot2.ESIM, fake.slot2)
        assertNull(record())
        // Run again once the user has switched the router off, taken the card out and switched it on.
        fake.tray1Card = null
        fake.boot()
        val card = giveNewImeis()!!
        assertEquals(card.imeis, ImeiPair(fake.imei1, fake.imei2))
        assertSafe()
    }

    @Test
    fun step3ChangesNothingIfTheRouterDoesNotFinishStarting() {
        prepareAndRestart()
        fake.uptimeBroken = true
        val changes = fake.changes.size
        assertStops(Problem.NOT_STARTED) { giveNewImeis() }
        assertEquals(changes, fake.changes.size)
        assertEquals(Slot2.ESIM, fake.slot2)
    }

    @Test
    fun stepsCanRunAgain() {
        swap.prepare()
        swap.prepare()
        swap.shutDown()
        fake.boot()
        val card = giveNewImeis()
        assertEquals(card, giveNewImeis())
        assertSafe()
    }

    @Test
    fun keepingTheOldCardKeepsItsImeis() {
        prepareAndRestart()
        val first = giveNewImeis()
        prepareAndRestart()
        assertEquals(first, giveNewImeis())
        // The recorded card brings no question.
        assertEquals(1, asked.size)
    }

    private companion object {
        val OTHER_CARD = FakeRouter.Card("8949000077777777777F", "228010000000002")
    }
}
