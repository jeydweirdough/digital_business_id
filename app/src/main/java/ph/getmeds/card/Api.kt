package ph.getmeds.card

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The getmeds_backend endpoints this app uses (app/api/routes/card.py).
 * Calls block, so run them through [Bg].
 */
object Api {

    /** A response the backend refused; [message] is written to be shown as-is. */
    class ApiException(val status: Int, message: String) : Exception(message)

    class CodeTicket(val ticket: String, val sentTo: String)

    /**
     * [card]: the one signed in with; [cards]: every card under the email;
     * [profileFields]: what "Edit my details" may change (empty on backends without it).
     */
    class SignIn(
        val token: String, val card: JSONObject, val cards: JSONArray, val expiresInSeconds: Long,
        val profileFields: Set<String>,
    )

    /** /api/card/me and /api/card/profile. */
    class Me(val cards: JSONArray, val profileFields: Set<String>)

    /** [remember]: stay signed in 30 days instead of 1. */
    fun login(email: String, password: String, remember: Boolean): SignIn =
        signIn(call(
            "POST", "/api/card/login",
            JSONObject().put("email", email).put("password", password).put("remember", remember), null,
        ))

    /** First time / forgot password: emails a 6-digit code to this address, if it's on a card. */
    fun requestCode(email: String): CodeTicket {
        val res = call("POST", "/api/card/otp/request", JSONObject().put("email", email), null)
        return CodeTicket(res.getString("ticket"), res.optString("sentTo"))
    }

    fun resetPassword(ticket: String, code: String, password: String, remember: Boolean): SignIn =
        signIn(call(
            "POST", "/api/card/password/reset",
            JSONObject().put("ticket", ticket).put("code", code).put("password", password).put("remember", remember),
            null,
        ))

    /** Every card the holder can show (just one on older backends). */
    fun me(token: String): Me = meOf(call("GET", "/api/card/me", null, token))

    /**
     * "Edit my details": [changes] holds only the fields that changed ("" clears
     * one). Returns the holder's cards with the change applied.
     */
    fun updateProfile(token: String, cardId: String, changes: JSONObject): Me {
        val body = JSONObject(changes.toString()).put("cardId", cardId)
        return meOf(call("POST", "/api/card/profile", body, token))
    }

    class AppUpdate(
        val latestVersion: String, val available: Boolean, val required: Boolean,
        val notes: String, val downloadUrl: String,
    )

    /** Whether this build should update, and where to get the new one. Null if the backend can't say. */
    fun appUpdate(): AppUpdate? {
        val version = java.net.URLEncoder.encode(BuildConfig.VERSION_NAME, "UTF-8")
        val android = call("GET", "/api/card/app?platform=android&version=$version", null, null, readTimeoutMs = 15_000)
            .optJSONObject("android") ?: return null
        val url = android.optString("downloadUrl")
        if (url.isBlank()) return null
        return AppUpdate(
            android.optString("latestVersion"), android.optBoolean("updateAvailable"), android.optBoolean("required"),
            android.optString("notes"), url,
        )
    }

    class TicketFile(val name: String, val type: String, val bytes: ByteArray)

    /** "Contact us": emails a ticket to the developer. Returns the ticket number. */
    fun sendTicket(
        token: String, cardId: String, topic: String, message: String, device: String, files: List<TicketFile>,
    ): String {
        val attachments = JSONArray()
        for (f in files) {
            attachments.put(
                JSONObject().put("name", f.name).put("type", f.type)
                    .put("base64", android.util.Base64.encodeToString(f.bytes, android.util.Base64.NO_WRAP))
            )
        }
        val body = JSONObject()
            .put("cardId", cardId).put("topic", topic).put("message", message)
            .put("device", device).put("appVersion", BuildConfig.VERSION_NAME)
            .put("attachments", attachments)
        // Attachments take a while to upload on mobile data.
        return call("POST", "/api/card/support", body, token, readTimeoutMs = 60_000).getString("ticketId")
    }

    private fun signIn(res: JSONObject) =
        SignIn(
            res.getString("token"), res.getJSONObject("card"), cardsOf(res), res.optLong("expiresIn", 86_400),
            fieldsOf(res),
        )

    private fun meOf(res: JSONObject) = Me(cardsOf(res), fieldsOf(res))

    private fun fieldsOf(res: JSONObject): Set<String> {
        val array = res.optJSONArray("profileFields") ?: return emptySet()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }.toSet()
    }

    private fun cardsOf(res: JSONObject): JSONArray =
        res.optJSONArray("cards")?.takeIf { it.length() > 0 } ?: JSONArray().put(res.getJSONObject("card"))

    private fun call(
        method: String, path: String, body: JSONObject?, token: String?, readTimeoutMs: Int = 20_000,
    ): JSONObject {
        val conn = URL(BuildConfig.API_BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (status >= 400) throw ApiException(status, messageOf(json, status))
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun messageOf(json: JSONObject, status: Int): String {
        // FastAPI sends a string for our own errors and a list for validation errors.
        val detail = json.opt("detail")
        if (detail is String && detail.isNotBlank()) return detail
        if (detail is JSONArray) return "Please check what you entered and try again."
        return "Something went wrong (error $status). Please try again."
    }
}
