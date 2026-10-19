package id.churn

import org.json.JSONArray
import org.json.JSONObject

/**
 * A card for one place the user returns to, other than home and work, which keeps its pair for every visit there
 * (design.md, section 4). [name] is one the user gave it.
 */
data class PlaceCard(val card: Card, val name: String? = null)

/** A question step 3 asks about the card in SIM 2 before it writes the record or the IMEIs. */
sealed interface Question {

    /** The card is [place]: continue, which gives it its kept pair, or cancel the swap. */
    data class KnownPlaceCard(val place: PlaceCard) : Question

    /** Any other card but the recorded one, by its ICCID's digits: a new place card, a travel card, or cancel. */
    data class NewCard(val iccid: String) : Question
}

enum class Answer { CONTINUE, PLACE_CARD, TRAVEL_CARD, CANCEL }

/**
 * The place list, which stays on the phone (design.md, section 7). [load] and [save] keep it as a JSON array, in
 * the order the cards joined it.
 */
class PlaceCards(private val load: () -> String?, private val save: (String) -> Unit) {

    val all: List<PlaceCard> get() = load()?.let(::decode) ?: emptyList()

    fun find(iccid: String) = all.firstOrNull { it.card.iccid == iccid }

    /**
     * Step 3's choice for [card], a card in SIM 2 other than the recorded one, with a new random pair: asks the user
     * with [ask] and returns the card with the pair to give it, or null if the user cancels. A card on the list gets
     * its kept pair. A new place card joins the list at once, before step 3 records it: run again, step 3 asks
     * nothing about the recorded card.
     */
    fun choose(card: Card, ask: (Question) -> Answer): Card? {
        val place = find(card.iccid)
        if (place != null) return place.card.takeIf { ask(Question.KnownPlaceCard(place)) == Answer.CONTINUE }
        return when (ask(Question.NewCard(card.iccid))) {
            Answer.PLACE_CARD -> card.also { save(encode(all + PlaceCard(it))) }
            Answer.TRAVEL_CARD -> card
            else -> null
        }
    }

    /**
     * The card step 4 tells the user to destroy, of [before], the card recorded before the swap, and [after], the
     * card step 3 gave IMEIs: [before], unless the user kept it or it's a place card.
     */
    fun toDestroy(before: String?, after: String) = before?.takeIf { it != after && find(it) == null }

    /** Gives the place card with [iccid] the name [name], or no name if it is blank. */
    fun name(iccid: String, name: String) = save(
        encode(all.map { if (it.card.iccid == iccid) it.copy(name = name.trim().ifEmpty { null }) else it }),
    )

    fun forget(iccid: String) = save(encode(all.filter { it.card.iccid != iccid }))

    private fun encode(places: List<PlaceCard>) = JSONArray(
        places.map { place ->
            JSONObject()
                .put(ICCID, place.card.iccid)
                .put(IMEI_1, place.card.imeis.imei1)
                .put(IMEI_2, place.card.imeis.imei2)
                .putOpt(NAME, place.name)
        },
    ).toString()

    private fun decode(json: String): List<PlaceCard> {
        val array = JSONArray(json)
        return List(array.length()) { i ->
            val place = array.getJSONObject(i)
            val card = Card(place.getString(ICCID), ImeiPair(place.getString(IMEI_1), place.getString(IMEI_2)))
            PlaceCard(card, name = if (place.has(NAME)) place.getString(NAME) else null)
        }
    }

    private companion object {
        const val ICCID = "iccid"
        const val IMEI_1 = "imei1"
        const val IMEI_2 = "imei2"
        const val NAME = "name"
    }
}
