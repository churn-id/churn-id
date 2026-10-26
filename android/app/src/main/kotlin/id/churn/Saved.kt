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
    val written: ImeiPair? get() = pair(WRITTEN_1, WRITTEN_2)

    /**
     * A copy of the factory IMEIs of the router Churn wrote [written] to, which setup writes back if Churn's folder
     * there is lost (design.md, section 7).
     */
    val factory: ImeiPair? get() = pair(FACTORY_1, FACTORY_2)

    /**
     * Keeps [imeis], which Churn wrote to a router with the factory IMEIs [factory]. The two are kept together, so
     * that the copy of the factory IMEIs belongs to the router that has [imeis].
     */
    fun wrote(imeis: ImeiPair, factory: ImeiPair) = save {
        putString(WRITTEN_1, imeis.imei1).putString(WRITTEN_2, imeis.imei2)
        putString(FACTORY_1, factory.imei1).putString(FACTORY_2, factory.imei2)
    }

    /**
     * The ID of the router's run that the last shutdown of a swap ended. Step 3 starts only on a router that has been
     * switched off and on since.
     */
    var bootId: String?
        get() = preferences.getString(BOOT_ID, null)
        set(value) = save { putString(BOOT_ID, value) }

    /** The ICCID of the card recorded when step 1 ran: the card the user takes out, unless they keep it. */
    var cardBeforeSwap: String?
        get() = preferences.getString(CARD_BEFORE_SWAP, null)
        set(value) = save { putString(CARD_BEFORE_SWAP, value) }

    /**
     * Whether the swap began on a router with its factory IMEIs, as the first swap does. Step 1 then says that steps 1
     * to 3 can run at home or at work if the router was used there before Churn (design.md, section 7).
     */
    var firstSwap: Boolean
        get() = preferences.getBoolean(FIRST_SWAP, false)
        set(value) = save { putBoolean(FIRST_SWAP, value) }

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

    private fun pair(key1: String, key2: String): ImeiPair? {
        val imei1 = preferences.getString(key1, null) ?: return null
        val imei2 = preferences.getString(key2, null) ?: return null
        return ImeiPair(imei1, imei2)
    }

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
        const val FACTORY_1 = "factory_imei1"
        const val FACTORY_2 = "factory_imei2"
        const val PLACE_CARDS = "place_cards"
        const val CARD_BEFORE_SWAP = "card_before_swap"
        const val FIRST_SWAP = "first_swap"
        const val PLACE_CARD_IN = "place_card_in"
        const val CARD_TO_DESTROY = "card_to_destroy"
        const val BOOT_ID = "boot_id"
    }
}
