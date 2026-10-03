package ph.getmeds.card

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * What the Share screen is offering right now. The service only answers
 * while [active], so the phone never hands out the card from a pocket.
 */
object ShareState {
    @Volatile var active = false
    /** The NDEF message to hand over (a link to the holder's card page). */
    @Volatile var ndef: ByteArray? = null
    /** Called on the main thread when a phone has read the whole contact. */
    @Volatile var onSent: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var lastSentAt = 0L

    internal fun notifySent() {
        val now = System.currentTimeMillis()
        // Readers often read the tag twice in one touch; report it once.
        if (now - lastSentAt < 3_000) return
        lastSentAt = now
        main.post { onSent?.invoke() }
    }
}

/**
 * Emulates an NFC Forum Type 4 tag holding one NDEF URI record: the link to
 * the holder's card page on getmeds.ph. Any Android phone, and iPhone XS or newer,
 * reads that tag with no app installed and opens it.
 *
 * The reader's side of the exchange (NFC Forum T4T spec):
 *   SELECT NDEF app (AID D2760000850101)
 *   SELECT E103 (capability container) -> READ BINARY
 *   SELECT E104 (NDEF file)            -> READ BINARY (length, then message)
 */
class CardHceService : HostApduService() {

    private var selected: ByteArray? = null
    private var ndefFile: ByteArray = ByteArray(0)

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        val message = ShareState.ndef
        if (!ShareState.active || message == null || apdu.size < 4) return SW_NOT_FOUND

        val ins = apdu[1]
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF

        return when {
            ins == INS_SELECT && p1 == 0x04 -> {
                if (!data(apdu).contentEquals(NDEF_AID)) return SW_NOT_FOUND
                selected = null
                ndefFile = buildNdefFile(message)
                SW_OK
            }
            ins == INS_SELECT && p1 == 0x00 -> {
                val fileId = data(apdu)
                selected = when {
                    fileId.contentEquals(CC_FILE_ID) -> CC_FILE
                    fileId.contentEquals(NDEF_FILE_ID) -> ndefFile
                    else -> return SW_NOT_FOUND
                }
                SW_OK
            }
            ins == INS_READ_BINARY -> {
                val file = selected ?: return SW_NOT_FOUND
                val offset = ((p1 and 0x7F) shl 8) or p2
                if (offset > file.size) return SW_WRONG_OFFSET
                val le = if (apdu.size >= 5) (apdu[apdu.size - 1].toInt() and 0xFF).let { if (it == 0) 256 else it } else 256
                val end = minOf(file.size, offset + le)
                if (file === ndefFile && end == file.size && end > 2) ShareState.notifySent()
                file.copyOfRange(offset, end) + SW_OK
            }
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    override fun onDeactivated(reason: Int) {
        selected = null
    }

    /** The command's data field (Lc bytes after the 5-byte header). */
    private fun data(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return ByteArray(0)
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return ByteArray(0)
        return apdu.copyOfRange(5, 5 + lc)
    }

    private fun buildNdefFile(message: ByteArray): ByteArray {
        // NLEN: two-byte big-endian length, then the message itself.
        return byteArrayOf((message.size shr 8).toByte(), message.size.toByte()) + message
    }

    private companion object {
        val INS_SELECT = 0xA4.toByte()
        val INS_READ_BINARY = 0xB0.toByte()

        val NDEF_AID = hex("D2760000850101")
        val CC_FILE_ID = hex("E103")
        val NDEF_FILE_ID = hex("E104")

        val CC_FILE = hex(
            "000F" +   // CC length: 15 bytes
            "20" +     // mapping version 2.0
            "003B" +   // max bytes per READ BINARY
            "0034" +   // max bytes per UPDATE BINARY
            "04" + "06" + // NDEF file control TLV, 6 bytes:
            "E104" +   //   file ID
            "0800" +   //   max NDEF file size (2 KB; the link is ~60 bytes)
            "00" +     //   read: open
            "FF"       //   write: never
        )

        val SW_OK = hex("9000")
        val SW_NOT_FOUND = hex("6A82")
        val SW_WRONG_OFFSET = hex("6B00")
        val SW_INS_NOT_SUPPORTED = hex("6D00")

        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
