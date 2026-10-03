package ph.getmeds.card

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * "Contact us": a ticket to the Getmeds Card developer, emailed by the
 * backend. Screenshots or PDFs can be attached as proof, but don't have to be.
 * Photos are shrunk before upload so a few fit under the server's size limit.
 */
class SupportActivity : Activity() {

    private lateinit var topics: RadioGroup
    private lateinit var message: EditText
    private lateinit var filesView: LinearLayout
    private lateinit var addFile: Button
    private lateinit var send: Button
    private lateinit var progress: ProgressBar
    private lateinit var error: TextView

    private val files = mutableListOf<Api.TicketFile>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val card = Session.card(this)
        if (Session.token(this) == null || card == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_support)
        findViewById<View>(R.id.root).padForSystemBars()

        topics = findViewById(R.id.topics)
        message = findViewById(R.id.message)
        filesView = findViewById(R.id.files)
        addFile = findViewById(R.id.addFile)
        send = findViewById(R.id.send)
        progress = findViewById(R.id.progress)
        error = findViewById(R.id.error)

        findViewById<TextView>(R.id.intro).text = getString(R.string.ticket_intro, card.email) +
            if (CrashLog.last(this) != null) "\n\n" + getString(R.string.ticket_crash_note) else ""
        // Same list, same order as TICKET_TOPICS in the backend.
        resources.getStringArray(R.array.ticket_topics).forEachIndexed { i, label ->
            topics.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                tag = label
                textSize = 15f
                minHeight = dp(44)
                buttonTintList = getColorStateList(R.color.brand)
                isChecked = i == 0
            })
        }

        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        addFile.setOnClickListener { pickFiles() }
        send.setOnClickListener { sendTicket() }
        renderFiles()
    }

    private fun pickFiles() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(pick, PICK_FILES)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FILES || resultCode != RESULT_OK || data == null) return
        val uris = buildList {
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri) }
            if (isEmpty()) data.data?.let(::add)
        }
        val room = MAX_FILES - files.size
        if (uris.size > room) showError(getString(R.string.ticket_too_many, MAX_FILES))
        if (room <= 0) return

        busy(true)
        Bg.run(this, { uris.take(room).map { runCatching { readFile(it) }.getOrNull() } }) { result ->
            busy(false)
            result.onSuccess { read ->
                val skipped = read.count { it == null }
                files += read.filterNotNull()
                renderFiles()
                if (skipped > 0) showError(getString(R.string.ticket_file_unreadable))
                else if (totalBytes() > MAX_TOTAL_BYTES) showError(getString(R.string.ticket_too_big))
            }.onFailure { showError(getString(R.string.ticket_file_unreadable)) }
        }
    }

    /** Blocking. Photos come back as a ~1600px JPEG; PDFs as they are. Null if unusable. */
    private fun readFile(uri: Uri): Api.TicketFile? {
        val type = contentResolver.getType(uri).orEmpty()
        val name = displayName(uri)
        if (type.startsWith("image/")) {
            val bitmap = decodeScaled(uri) ?: return null
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
            bitmap.recycle()
            return Api.TicketFile(name.substringBeforeLast('.') + ".jpg", "image/jpeg", out.toByteArray())
        }
        if (type == "application/pdf") {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            return if (bytes.size <= MAX_TOTAL_BYTES) Api.TicketFile(name, type, bytes) else null
        }
        return null
    }

    private fun decodeScaled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PHOTO_SIDE) sample *= 2
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        // Camera photos are often stored sideways with a note to rotate them.
        val rotation = runCatching {
            contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        val scale = minOf(1f, MAX_PHOTO_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        if (scale == 1f && rotation == 0f) return decoded
        val matrix = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            .also { if (it !== decoded) decoded.recycle() }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "attachment"

    private fun renderFiles() {
        filesView.removeAllViews()
        files.forEachIndexed { index, file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_panel)
                setPadding(dp(14), dp(4), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
            }
            row.addView(TextView(this).apply {
                text = "${file.name}  ·  ${sizeLabel(file.bytes.size)}"
                setTextColor(getColor(R.color.ink))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                setBackgroundResource(R.drawable.bg_icon_button)
                contentDescription = getString(R.string.ticket_remove_file, file.name)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                setOnClickListener {
                    files.removeAt(index)
                    renderFiles()
                    error.visibility = View.GONE
                }
            })
            filesView.addView(row)
        }
        addFile.isEnabled = files.size < MAX_FILES
        addFile.text = getString(if (files.isEmpty()) R.string.ticket_add_file else R.string.ticket_add_more)
    }

    private fun sendTicket() {
        val text = message.text.toString().trim()
        if (text.isEmpty()) return showError(getString(R.string.ticket_need_message))
        if (totalBytes() > MAX_TOTAL_BYTES) return showError(getString(R.string.ticket_too_big))
        val token = Session.token(this) ?: return finish()
        val topic = findViewById<RadioButton>(topics.checkedRadioButtonId)?.tag as? String ?: "Other"
        val device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
        // The app's last crash, if any, so the developer can see what broke.
        val crash = CrashLog.last(this)
        val body = if (crash == null) text else "$text\n\n--- Last app crash (added automatically) ---\n$crash"

        busy(true)
        Bg.run(this, { Api.sendTicket(token, Session.card(this)?.id.orEmpty(), topic, body.take(5000), device, files.toList()) }) { result ->
            busy(false)
            result.onSuccess { ticketId ->
                CrashLog.clear(this)
                AlertDialog.Builder(this)
                    .setTitle(R.string.ticket_sent_title)
                    .setMessage(getString(R.string.ticket_sent_message, ticketId, Session.card(this)?.email.orEmpty()))
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }.onFailure { e ->
                if (e is Api.ApiException && (e.status == 401 || e.status == 403)) {
                    Session.clear(this)
                    startActivity(
                        Intent(this, LoginActivity::class.java)
                            .putExtra(LoginActivity.EXTRA_MESSAGE, e.message)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    finish()
                    return@onFailure
                }
                showError(
                    when (e) {
                        is Api.ApiException -> e.message.orEmpty()
                        is IOException -> "No internet connection. Check your data or Wi-Fi and try again."
                        else -> "Something went wrong. Please try again."
                    }
                )
            }
        }
    }

    private fun totalBytes() = files.sumOf { it.bytes.size }

    private fun busy(on: Boolean) {
        progress.visibility = if (on) View.VISIBLE else View.GONE
        send.isEnabled = !on
        addFile.isEnabled = !on && files.size < MAX_FILES
        if (on) error.visibility = View.GONE
    }

    private fun showError(text: String) {
        error.text = text
        error.visibility = View.VISIBLE
    }

    private fun sizeLabel(bytes: Int): String =
        if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1048576f) else "${maxOf(1, bytes / 1024)} KB"

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PICK_FILES = 7
        // Match MAX_TICKET_FILES / MAX_TICKET_BYTES in the backend.
        const val MAX_FILES = 5
        const val MAX_TOTAL_BYTES = 3 * 1024 * 1024
        const val MAX_PHOTO_SIDE = 1600
    }
}
