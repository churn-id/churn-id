package id.churn

import android.content.Context
import android.content.SharedPreferences

/** What the app keeps on the phone. Every write is saved before the call returns. */
class Saved(context: Context) {

    private val preferences = context.getSharedPreferences("churn", Context.MODE_PRIVATE)

    var introAccepted: Boolean
        get() = preferences.getBoolean(INTRO, false)
        set(value) = save { putBoolean(INTRO, value) }

    var address: String
        get() = preferences.getString(ADDRESS, null) ?: DEFAULT_ADDRESS
        set(value) = save { putString(ADDRESS, value) }

    /** The router's host key that the user trusted, as [HostKeys.encode] writes it. */
    var hostKey: String?
        get() = preferences.getString(HOST_KEY, null)
        set(value) = save { putString(HOST_KEY, value) }

    /** The admin password, sealed by [PasswordVault]. */
    var sealedPassword: String?
        get() = preferences.getString(PASSWORD, null)
        set(value) = save { putString(PASSWORD, value) }

    /** The swap step reached, or null when no swap is under way. */
    var step: Step?
        get() = preferences.getString(STEP, null)?.let { name -> Step.entries.firstOrNull { it.name == name } }
        set(value) = save { putString(STEP, value?.name) }

    /** The IMEIs Churn last wrote, which the router should still have. */
    var written: ImeiPair?
        get() {
            val imei1 = preferences.getString(WRITTEN_1, null) ?: return null
            val imei2 = preferences.getString(WRITTEN_2, null) ?: return null
            return ImeiPair(imei1, imei2)
        }
        set(value) = save { putString(WRITTEN_1, value?.imei1).putString(WRITTEN_2, value?.imei2) }

    /** The ICCID of the card recorded when step 1 ran: the card the user takes out, unless they keep it. */
    var cardBeforeSwap: String?
        get() = preferences.getString(CARD_BEFORE_SWAP, null)
        set(value) = save { putString(CARD_BEFORE_SWAP, value) }

    /** Whether step 3 gave a place card its IMEIs, which step 4 tells the user to use only at its place. */
    var placeCardIn: Boolean
        get() = preferences.getBoolean(PLACE_CARD_IN, false)
        set(value) = save { putBoolean(PLACE_CARD_IN, value) }

    /** The last four digits of the ICCID of the card that step 4 tells the user to destroy. */
    var cardToDestroy: String?
        get() = preferences.getString(CARD_TO_DESTROY, null)
        set(value) = save { putString(CARD_TO_DESTROY, value) }

    /** The place cards, with their pairs and the names the user gave them. */
    val placeCards = PlaceCards(
        load = { preferences.getString(PLACE_CARDS, null) },
        save = { json -> save { putString(PLACE_CARDS, json) } },
    )

    private fun save(edit: SharedPreferences.Editor.() -> Unit) {
        check(preferences.edit().apply(edit).commit()) { "Couldn't save the app's state" }
    }

    private companion object {
        const val DEFAULT_ADDRESS = "192.168.8.1"
        const val INTRO = "intro_accepted"
        const val ADDRESS = "address"
        const val HOST_KEY = "host_key"
        const val PASSWORD = "password"
        const val STEP = "step"
        const val WRITTEN_1 = "written_imei1"
        const val WRITTEN_2 = "written_imei2"
        const val PLACE_CARDS = "place_cards"
        const val CARD_BEFORE_SWAP = "card_before_swap"
        const val PLACE_CARD_IN = "place_card_in"
        const val CARD_TO_DESTROY = "card_to_destroy"
    }
}
