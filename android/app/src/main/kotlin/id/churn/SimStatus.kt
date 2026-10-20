package id.churn

/**
 * The card in use, as the router screen shows it in one line. Everything but a place card's name is read from the
 * router. Slot 1 as the active slot and an enabled profile on the built-in eSIM show as warnings: Churn never uses
 * slot 1 (design.md, section 7), and rule 4 rules out the built-in eSIM.
 */
sealed interface SimStatus {

    /** Slot 1 is the active slot. */
    data object Slot1Active : SimStatus

    /** Slot 2 is on the built-in eSIM, so the card in the tray is off, and no profile is enabled. */
    data object Parked : SimStatus

    /** Slot 2 is on the built-in eSIM, with a profile enabled. */
    data object EsimProfile : SimStatus

    /** Slot 2 is on the tray, and no card with a real network's IMSI answered: an empty tray, or a card not ready. */
    data object NoCard : SimStatus

    /** The card in the tray is [place], from the place list. */
    data class Place(val place: PlaceCard) : SimStatus

    /** The card in the tray, by its ICCID's digits, is the recorded card and not on the place list. */
    data class Travel(val iccid: String) : SimStatus

    /** The card in the tray, by its ICCID's digits, is neither the recorded card nor on the place list. */
    data class Unknown(val iccid: String) : SimStatus

    companion object {

        /**
         * The status of a router whose active slot is [activeSlot], with slot 2 set to [slot2] and [card] answering
         * there, by its ICCID's digits, while the record names [recorded]; null if slot 2's setting wasn't read.
         */
        fun of(
            activeSlot: String?,
            slot2: Slot2?,
            card: String?,
            recorded: String?,
            places: List<PlaceCard>,
        ): SimStatus? = when {
            activeSlot == "1" -> Slot1Active
            slot2 == Slot2.ESIM -> if (card == null) Parked else EsimProfile
            slot2 == null -> null
            card == null -> NoCard
            else -> places.firstOrNull { it.card.iccid == card }?.let(::Place)
                ?: if (card == recorded) Travel(card) else Unknown(card)
        }
    }
}
