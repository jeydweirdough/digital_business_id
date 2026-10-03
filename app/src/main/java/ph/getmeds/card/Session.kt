package ph.getmeds.card

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One of the holder's cards as /api/card/me returns it. */
class Card(val json: JSONObject) {
    val id: String get() = json.optString("id")
    val fullName: String get() = json.optString("fullName")
    val jobTitle: String get() = json.optString("jobTitle")
    val company: String get() = json.optString("company")
    val mobile: String get() = json.optString("mobile")
    val officePhone: String get() = json.optString("officePhone")
    val whatsapp: String get() = json.optString("whatsapp")
    val viber: String get() = json.optString("viber")
    val otherMobiles: List<String> get() = strings("otherMobiles")
    val email: String get() = json.optString("email")
    val otherEmails: List<String> get() = strings("otherEmails")
    /**
     * The public card page (getmeds.ph/card/<slug>); what the QR and the NFC
     * tap carry. The other person sees the card there and chooses whether to
     * save the contact. Empty until the card has a slug.
     */
    val shareUrl: String get() = json.optString("shareUrl")

    private fun strings(key: String): List<String> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    }
}

/** How the QR and the NFC tap share the card. */
enum class ShareMode {
    /** Opens getmeds.ph/card/<slug>; needs internet on the other phone. The default. */
    WEBSITE,
    /** The contact itself; the other phone offers to add it, no internet needed. */
    DIRECT,
}

/**
 * Sign-in token plus the holder's cards as last fetched, so the QR (which
 * needs no network) still works offline. One person can have several cards
 * under one email; the one on screen is remembered between launches.
 * The sign-in ends after 1 day, or 30 with "Remember me"; that's checked
 * here too, so it ends even with no signal.
 */
object Session {
    private const val PREFS = "session"

    /** The token, or null if signed out or the sign-in has run out. */
    fun token(context: Context): String? {
        val p = prefs(context)
        val token = p.getString("token", null) ?: return null
        if (System.currentTimeMillis() >= p.getLong("expiresAt", 0)) {
            clear(context)
            return null
        }
        return token
    }

    /** Every card the holder can show, in the backend's order. */
    fun cards(context: Context): List<Card> {
        val raw = prefs(context).getString("cards", null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::Card) }
    }

    /** The card on screen: the one picked last, else the first. */
    fun card(context: Context): Card? {
        val all = cards(context)
        val picked = prefs(context).getString("selected", null)
        return all.firstOrNull { it.id == picked } ?: all.firstOrNull()
    }

    fun select(context: Context, cardId: String) {
        prefs(context).edit().putString("selected", cardId).apply()
    }

    fun save(context: Context, signIn: Api.SignIn) {
        prefs(context).edit()
            .putString("token", signIn.token)
            .putString("cards", signIn.cards.toString())
            .putString("selected", signIn.card.optString("id"))
            .putLong("expiresAt", System.currentTimeMillis() + signIn.expiresInSeconds * 1000)
            .apply()
    }

    /** From /api/card/me: picks up added, changed and removed cards. */
    fun saveCards(context: Context, cards: JSONArray) {
        prefs(context).edit().putString("cards", cards.toString()).apply()
    }

    /** Website card unless the holder picked Direct contact (reset on sign-out). */
    fun shareMode(context: Context): ShareMode =
        runCatching { ShareMode.valueOf(prefs(context).getString("shareMode", null) ?: "") }
            .getOrDefault(ShareMode.WEBSITE)

    fun setShareMode(context: Context, mode: ShareMode) {
        prefs(context).edit().putString("shareMode", mode.name).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
