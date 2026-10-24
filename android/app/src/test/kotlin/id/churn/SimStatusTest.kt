package id.churn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimStatusTest {

    private val place = PlaceCard(
        Card("8941000000000000015", ImeiPair("356090213059463", "356090213059539")),
        name = "Lake",
    )
    private val travel = "8944000000000000123"

    private fun status(
        activeSlot: String? = "2",
        slot2: Slot2? = Slot2.SIM2,
        card: String? = travel,
        noProfile: Boolean = false,
        recorded: String? = travel,
    ) = SimStatus.of(
        activeSlot = activeSlot,
        slot2 = slot2,
        card = card,
        noProfile = noProfile,
        recorded = recorded,
        places = listOf(place),
    )

    @Test
    fun namesThePlaceCardInTheTray() {
        assertEquals(SimStatus.Place(place), status(card = place.card.iccid, recorded = place.card.iccid))
        // Swapped in without the app: still named, while the router screen warns about its IMEIs.
        assertEquals(SimStatus.Place(place), status(card = place.card.iccid))
    }

    @Test
    fun takesTheRecordedCardOffThePlaceListForATravelCard() {
        assertEquals(SimStatus.Travel(travel), status())
    }

    @Test
    fun callsAnyOtherCardUnknown() {
        assertEquals(SimStatus.Unknown(travel), status(recorded = place.card.iccid))
        assertEquals(SimStatus.Unknown(travel), status(recorded = null))
    }

    @Test
    fun findsNoCardInAnEmptyTray() {
        assertEquals(SimStatus.NoCard, status(card = null))
    }

    @Test
    fun saysWhenTheCardInTheTrayHasNoProfileEnabled() {
        assertEquals(SimStatus.NoProfile, status(card = null, noProfile = true))
    }

    @Test
    fun saysTheTrayIsOffWhileSlot2IsOnTheEsim() {
        assertEquals(SimStatus.Parked, status(slot2 = Slot2.ESIM, card = null))
        // The built-in eSIM without an enabled profile.
        assertEquals(SimStatus.Parked, status(slot2 = Slot2.ESIM, card = null, noProfile = true))
    }

    @Test
    fun warnsAboutAnEnabledEsimProfile() {
        assertEquals(SimStatus.EsimProfile, status(slot2 = Slot2.ESIM, card = "8944000000000000999"))
    }

    @Test
    fun warnsWhenSlot1IsActive() {
        assertEquals(SimStatus.Slot1Active, status(activeSlot = "1"))
        assertEquals(SimStatus.Slot1Active, status(activeSlot = "1", slot2 = Slot2.ESIM, card = null))
    }

    @Test
    fun showsNothingWithoutSlot2sSetting() {
        assertNull(status(slot2 = null))
        assertNull(status(activeSlot = null, slot2 = null))
    }
}
