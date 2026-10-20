package id.churn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceCardsTest {

    private var json: String? = null
    private val places = PlaceCards(load = { json }, save = { json = it })
    private val asked = mutableListOf<Question>()
    private val card = Card("8941000000000000015", ImeiPair("356090213059463", "356090213059539"))

    private fun answering(answer: Answer): (Question) -> Answer = { question ->
        asked += question
        answer
    }

    @Test
    fun keepsANewPlaceCardWithItsPair() {
        assertEquals(emptyList<PlaceCard>(), places.all)
        assertEquals(card, places.choose(card, answering(Answer.PLACE_CARD)))
        assertEquals(listOf(Question.NewCard(card.iccid)), asked)
        assertEquals(listOf(PlaceCard(card)), places.all)
        // As the app reads it after a restart.
        assertEquals(listOf(PlaceCard(card)), PlaceCards(load = { json }, save = {}).all)
    }

    @Test
    fun givesAPlaceCardItsKeptPair() {
        places.choose(card, answering(Answer.PLACE_CARD))
        val again = Card(card.iccid, ImeiPair("356090219876548", "356090219876613"))
        assertEquals(card, places.choose(again, answering(Answer.CONTINUE)))
        assertEquals(Question.KnownPlaceCard(PlaceCard(card)), asked.last())
        assertEquals(listOf(PlaceCard(card)), places.all)
    }

    @Test
    fun leavesATravelCardOffTheList() {
        assertEquals(card, places.choose(card, answering(Answer.TRAVEL_CARD)))
        assertEquals(emptyList<PlaceCard>(), places.all)
    }

    @Test
    fun cancelingChangesNothing() {
        assertNull(places.choose(card, answering(Answer.CANCEL)))
        assertEquals(emptyList<PlaceCard>(), places.all)
        places.choose(card, answering(Answer.PLACE_CARD))
        assertNull(places.choose(card, answering(Answer.CANCEL)))
        assertEquals(listOf(PlaceCard(card)), places.all)
    }

    @Test
    fun namesAPlaceCard() {
        places.choose(card, answering(Answer.PLACE_CARD))
        places.name(card.iccid, " Lake ")
        assertEquals(listOf(PlaceCard(card, name = "Lake")), places.all)
        places.name(card.iccid, " ")
        assertEquals(listOf(PlaceCard(card)), places.all)
    }

    @Test
    fun destroysOnlyATravelCardTakenOut() {
        val other = "8949000077777777777"
        assertNull(places.toDestroy(before = null, after = card.iccid))
        // Kept after all.
        assertNull(places.toDestroy(before = card.iccid, after = card.iccid))
        assertEquals(other, places.toDestroy(before = other, after = card.iccid))
        places.choose(Card(other, card.imeis), answering(Answer.PLACE_CARD))
        assertNull(places.toDestroy(before = other, after = card.iccid))
    }

    @Test
    fun forgetsAPlaceCard() {
        val other = Card("8949000077777777777", ImeiPair("356090219876548", "356090219876613"))
        places.choose(card, answering(Answer.PLACE_CARD))
        places.choose(other, answering(Answer.PLACE_CARD))
        places.forget(card.iccid)
        assertEquals(listOf(PlaceCard(other)), places.all)
    }
}
