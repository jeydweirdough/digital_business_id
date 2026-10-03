package ph.getmeds.card

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

object Qr {

    private const val BLUE = 0x1EA0DA
    private const val GREEN = 0x60A644
    // Brand blue and green, deepened just enough for cameras to read them
    // as dark against white. The plain brand colours are too light for that.
    private val EYE_BLUE = darken(BLUE, 0.78)
    private val EYE_GREEN = darken(GREEN, 0.78)
    private const val NAVY = 0xFF0F3B57.toInt()

    /**
     * Branded QR: the Getmeds cross [logo] fills the code, every module is a
     * small rounded dot, and the three corner eyes are rounded in brand blue
     * and green. Used for both Online ID and Offline ID.
     *
     * Why it still scans: cameras only sample the centre of each module. So
     * each module is a dot at its centre, dark where the code is dark (a deep
     * shade of the logo colour under it) and white where it's light, even on
     * top of the logo, and the logo only shows in the gaps between dots.
     *
     * [level]: H for a short link (checked with zxing down to 220 px, blurred).
     * L for a contact: since every module centre is right, the code doesn't
     * lean on error correction, and a heavier level made the long contact so
     * dense it scanned worse (checked: L reads at 300 px blurred, H didn't).
     */
    fun branded(text: String, sizePx: Int, logo: Bitmap, level: ErrorCorrectionLevel): Bitmap {
        val hints = mutableMapOf<EncodeHintType, Any>()
        if (needsUtf8(text)) hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        val code = Encoder.encode(text, level, hints)
        val m = code.matrix
        val n = m.width
        val quiet = 2
        val cell = sizePx.toFloat() / (n + 2 * quiet)
        val origin = quiet * cell
        val align = code.version.alignmentPatternCenters

        val qr = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(qr)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // 1. The logo, large, behind everything; also kept to look up the
        //    colour under each module.
        val area = n * cell * 0.86f
        val scale = minOf(area / logo.width, area / logo.height)
        val lw = logo.width * scale
        val lh = logo.height * scale
        val layer = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        Canvas(layer).drawBitmap(
            logo, null, RectF((sizePx - lw) / 2f, (sizePx - lh) / 2f, (sizePx + lw) / 2f, (sizePx + lh) / 2f), paint,
        )
        canvas.drawBitmap(layer, 0f, 0f, paint)

        fun inEye(x: Int, y: Int) = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)
        val alignCentres = align.flatMap { ay -> align.map { ax -> ax to ay } }.filter { (ax, ay) -> !inEye(ax, ay) }
        fun inAlign(x: Int, y: Int) = alignCentres.any { (ax, ay) -> kotlin.math.abs(x - ax) <= 2 && kotlin.math.abs(y - ay) <= 2 }

        // 2. Every other module as a rounded dot at its centre.
        val dot = cell * 0.55f
        for (y in 0 until n) for (x in 0 until n) {
            if (inEye(x, y) || inAlign(x, y)) continue
            val cx = origin + (x + 0.5f) * cell
            val cy = origin + (y + 0.5f) * cell
            val under = layer.getPixel(cx.toInt().coerceIn(0, sizePx - 1), cy.toInt().coerceIn(0, sizePx - 1))
            val onLogo = Color.alpha(under) > 128
            val dark = m.get(x, y).toInt() == 1
            if (!dark && !onLogo) continue // white on white
            paint.color = when {
                !dark -> Color.WHITE
                onLogo -> darken(under and 0xFFFFFF, 0.42)
                else -> NAVY
            }
            canvas.drawRoundRect(RectF(cx - dot / 2, cy - dot / 2, cx + dot / 2, cy + dot / 2), dot * 0.25f, dot * 0.25f, paint)
        }

        // 3. Small alignment marks and the three eyes: a rounded ring and a
        //    rounded centre each, with white between.
        for ((ax, ay) in alignCentres) {
            eye(canvas, paint, origin + (ax - 2) * cell, origin + (ay - 2) * cell, cell, rings = 5, ringRadius = 0.9f)
        }
        for ((ex, ey) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
            eye(canvas, paint, origin + ex * cell, origin + ey * cell, cell, rings = 7, ringRadius = 1.3f)
        }
        layer.recycle()
        return qr
    }

    /** A finder (7 modules) or alignment (5 modules) mark at (left, top). */
    private fun eye(canvas: Canvas, paint: Paint, left: Float, top: Float, cell: Float, rings: Int, ringRadius: Float) {
        val outer = RectF(left, top, left + rings * cell, top + rings * cell)
        val inner = RectF(left + cell, top + cell, left + (rings - 1) * cell, top + (rings - 1) * cell)
        paint.color = Color.WHITE
        canvas.drawRect(outer, paint)
        paint.color = EYE_BLUE
        canvas.drawPath(
            Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRoundRect(outer, ringRadius * cell, ringRadius * cell, Path.Direction.CW)
                addRoundRect(inner, ringRadius * 0.6f * cell, ringRadius * 0.6f * cell, Path.Direction.CW)
            },
            paint,
        )
        val c = (rings - 3) / 2f // centre square: 3 modules in an eye, 1 in an alignment mark
        val size = if (rings == 7) 3 else 1
        val centre = RectF(left + (c + if (rings == 7) 0f else 1f) * cell, top + (c + if (rings == 7) 0f else 1f) * cell, 0f, 0f)
        centre.right = centre.left + size * cell
        centre.bottom = centre.top + size * cell
        paint.color = EYE_GREEN
        canvas.drawRoundRect(centre, 0.45f * size * cell, 0.45f * size * cell, paint)
    }

    /** Declaring UTF-8 adds a marker some camera apps misread; only when needed. */
    private fun needsUtf8(text: String) = !Charsets.ISO_8859_1.newEncoder().canEncode(text)

    private fun darken(rgb: Int, f: Double): Int {
        val r = (((rgb shr 16) and 255) * f).toInt()
        val g = (((rgb shr 8) and 255) * f).toInt()
        val b = ((rgb and 255) * f).toInt()
        return Color.rgb(r, g, b)
    }
}
