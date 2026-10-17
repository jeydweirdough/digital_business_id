package ph.getmeds.card

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * "Save QR as image": the QR on screen (Online ID or Offline ID), as a PNG in
 * the phone's photos, in a "Getmeds Card" album. Large and with a white border,
 * so it can go on an email signature, a slide or a print-out and still scan.
 */
object QrExport {
    private const val SIZE = 1200
    private const val BORDER = 80
    const val ALBUM = "Getmeds Card"

    /** Android 9 and older need the storage permission to write to the photos. */
    val needsPermission: Boolean get() = Build.VERSION.SDK_INT < 29

    /** Blocking: run through [Bg]. Returns the saved image, for sharing. */
    fun save(activity: Activity, payload: String, website: Boolean, fullName: String): Uri {
        val logo = BitmapFactory.decodeResource(activity.resources, R.drawable.getmeds_mark)
        val level = if (website) ErrorCorrectionLevel.H else ErrorCorrectionLevel.L
        val qr = Qr.branded(payload, SIZE, logo, level)
        val image = Bitmap.createBitmap(SIZE + 2 * BORDER, SIZE + 2 * BORDER, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.WHITE)
            drawBitmap(qr, BORDER.toFloat(), BORDER.toFloat(), null)
        }
        qr.recycle()

        val who = fullName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "card" }
        val name = "getmeds-card-$who-${if (website) "online" else "offline"}-id.png"
        return try {
            if (Build.VERSION.SDK_INT >= 29) saveToMediaStore(activity, image, name) else saveToPictures(activity, image, name)
        } finally {
            image.recycle()
        }
    }

    private fun saveToMediaStore(activity: Activity, image: Bitmap, name: String): Uri {
        val resolver = activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create the image")
        try {
            resolver.openOutputStream(uri)?.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ?: throw IllegalStateException("Could not write the image")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToPictures(activity: Activity, image: Bitmap, name: String): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Could not create the album")
        val file = File(dir, name)
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // So it shows in the gallery straight away, and so there's a content:// to share.
        var scanned: Uri? = null
        val done = CountDownLatch(1)
        MediaScannerConnection.scanFile(activity, arrayOf(file.absolutePath), arrayOf("image/png")) { _, uri ->
            scanned = uri
            done.countDown()
        }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return scanned ?: Uri.fromFile(file)
    }

    fun share(activity: Activity, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.qr_share)))
    }
}
