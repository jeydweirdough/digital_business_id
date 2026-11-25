package ph.getmeds.card

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Edit my details" (⋮ menu): the holder changes the details on the card on
 * screen, saved through /api/card/profile. Only what the backend lists in
 * profileFields is shown; the email, company and card link stay with the admin.
 * Only changed fields are sent, so an edit made in the Studio meanwhile to
 * another field isn't overwritten.
 */
class ProfileActivity : Activity() {

    private class FieldView(val key: String, val label: TextView, val input: EditText, val multi: Boolean = false)

    private lateinit var fields: List<FieldView>
    private lateinit var save: Button
    private lateinit var progress: ProgressBar
    private lateinit var error: TextView
    private var cardId = ""
    /** What each field held when the screen opened, to send only the changes. */
    private val original = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val card = Session.card(this)
        val allowed = Session.profileFields(this)
        if (Session.token(this) == null || card == null || allowed.isEmpty()) {
            finish()
            return
        }
        cardId = card.id
        setContentView(R.layout.activity_profile)
        findViewById<View>(R.id.root).padForSystemBars()

        fields = listOf(
            FieldView("fullName", findViewById(R.id.fullNameLabel), findViewById(R.id.fullName)),
            FieldView("jobTitle", findViewById(R.id.jobTitleLabel), findViewById(R.id.jobTitle)),
            FieldView("mobile", findViewById(R.id.mobileLabel), findViewById(R.id.mobile)),
            FieldView("otherMobiles", findViewById(R.id.otherMobilesLabel), findViewById(R.id.otherMobiles), multi = true),
            FieldView("officePhone", findViewById(R.id.officePhoneLabel), findViewById(R.id.officePhone)),
            FieldView("whatsapp", findViewById(R.id.whatsappLabel), findViewById(R.id.whatsapp)),
            FieldView("viber", findViewById(R.id.viberLabel), findViewById(R.id.viber)),
            FieldView("wechatId", findViewById(R.id.wechatIdLabel), findViewById(R.id.wechatId)),
        )
        save = findViewById(R.id.save)
        progress = findViewById(R.id.progress)
        error = findViewById(R.id.error)

        val values = mapOf(
            "fullName" to card.fullName,
            "jobTitle" to card.jobTitle,
            "mobile" to card.mobile,
            "otherMobiles" to card.otherMobiles.joinToString("\n"),
            "officePhone" to card.officePhone,
            "whatsapp" to card.whatsapp,
            "viber" to card.viber,
            "wechatId" to card.wechatId,
        )
        for (f in fields) {
            val shown = f.key in allowed
            f.label.visibility = if (shown) View.VISIBLE else View.GONE
            f.input.visibility = if (shown) View.VISIBLE else View.GONE
            val value = values[f.key].orEmpty()
            f.input.setText(value)
            original[f.key] = value
        }
        findViewById<TextView>(R.id.intro).text = getString(R.string.edit_details_intro, card.email.ifBlank { "-" })

        findViewById<ImageButton>(R.id.back).setOnClickListener { leave() }
        save.setOnClickListener { saveChanges() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = leave()

    /** Asks before throwing away unsaved changes. */
    private fun leave() {
        if (changes().length() == 0) return finish()
        AlertDialog.Builder(this)
            .setMessage(R.string.edit_details_discard)
            .setPositiveButton(R.string.edit_details_discard_yes) { _, _ -> finish() }
            .setNegativeButton(R.string.edit_details_keep, null)
            .show()
    }

    private fun current(f: FieldView): String {
        val text = f.input.text.toString()
        return if (f.multi) text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        else text.trim().replace(Regex("\\s+"), " ")
    }

    private fun changes(): JSONObject {
        val out = JSONObject()
        for (f in fields) {
            if (f.input.visibility != View.VISIBLE) continue
            val now = current(f)
            if (now == original[f.key]) continue
            if (f.multi) out.put(f.key, JSONArray(now.lines().filter { it.isNotBlank() }))
            else out.put(f.key, now)
        }
        return out
    }

    private fun saveChanges() {
        val changes = changes()
        if (changes.length() == 0) return finish()
        if (changes.has("fullName") && changes.optString("fullName").isBlank()) {
            return showError(getString(R.string.edit_details_name_required))
        }
        val token = Session.token(this) ?: return finish()
        busy(true)
        Bg.run(this, { Api.updateProfile(token, cardId, changes) }) { result ->
            busy(false)
            result.onSuccess {
                Session.saveCards(this, it)
                Toast.makeText(this, R.string.edit_details_saved, Toast.LENGTH_LONG).show()
                finish()
            }.onFailure { e ->
                showError(
                    when {
                        e is Api.ApiException && e.status == 404 -> getString(R.string.edit_details_unavailable)
                        e is Api.ApiException -> e.message.orEmpty()
                        else -> getString(R.string.edit_details_offline)
                    }
                )
            }
        }
    }

    private fun busy(on: Boolean) {
        progress.visibility = if (on) View.VISIBLE else View.GONE
        save.isEnabled = !on
        if (on) error.visibility = View.GONE
    }

    private fun showError(text: String) {
        error.text = text
        error.visibility = View.VISIBLE
    }
}
