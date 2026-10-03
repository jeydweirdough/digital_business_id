package ph.getmeds.card

/**
 * The holder's contact as a vCard, for "Direct contact" sharing: the QR or
 * NFC tap carries the details themselves, so the other phone offers to add
 * the contact with no website and no internet.
 *
 * Deliberately plain (vCard 3.0: name, title, company, numbers, emails).
 * Camera apps read short, simple contact QRs best, and every extra line
 * makes the code denser and harder to scan off a screen.
 */
object Contact {

    fun vcard(card: Card): String {
        val lines = mutableListOf("BEGIN:VCARD", "VERSION:3.0")
        val (family, given, middle) = nameParts(card.fullName)
        lines += "N:${esc(family)};${esc(given)};${esc(middle)};;"
        lines += "FN:${esc(card.fullName)}"
        if (card.company.isNotBlank()) lines += "ORG:${esc(card.company)}"
        if (card.jobTitle.isNotBlank()) lines += "TITLE:${esc(card.jobTitle)}"

        // Same order and de-duplication as the website's "Save to contacts".
        val mobile = toE164(card.mobile)
        val office = toE164(card.officePhone)
        val listed = mutableSetOf<String>()
        fun tel(number: String, type: String) {
            if (number.isNotEmpty() && listed.add(number)) lines += "TEL;TYPE=$type:$number"
        }
        tel(mobile, "CELL")
        tel(office, "WORK")
        tel(toE164(card.whatsapp).ifEmpty { mobile }, "CELL")
        tel(toE164(card.viber).ifEmpty { mobile }, "CELL")
        card.otherMobiles.forEach { tel(toE164(it), "CELL") }

        val emails = linkedSetOf<String>()
        (listOf(card.email) + card.otherEmails).map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { e -> if (emails.none { it.equals(e, ignoreCase = true) }) emails += e }
        emails.forEach { lines += "EMAIL;TYPE=WORK:${esc(it)}" }

        lines += "END:VCARD"
        return lines.joinToString("\r\n") + "\r\n"
    }

    /**
     * Whatever was typed into the Studio -> +639171234567; "" if unusable.
     * Same rules as toE164() in getmeds_frontend/src/lib/businessCard.ts.
     */
    fun toE164(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return when {
            trimmed.startsWith("+") -> "+$digits"
            digits.startsWith("00") -> "+${digits.drop(2)}"
            digits.startsWith("63") && digits.length == 12 -> "+$digits"
            digits.startsWith("0") && digits.length == 11 -> "+63${digits.drop(1)}"
            digits.startsWith("9") && digits.length == 10 -> "+63$digits"
            digits.startsWith("0") -> "+63${digits.drop(1)}"
            digits.length in 7..9 -> "+63$digits"
            digits.length >= 10 -> "+$digits"
            else -> ""
        }
    }

    /** "Juan Dela Cruz" -> family/given/middle, which decides how contacts sort. */
    private fun nameParts(fullName: String): Triple<String, String, String> {
        val bits = fullName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when (bits.size) {
            0 -> Triple("", "", "")
            1 -> Triple("", bits[0], "")
            2 -> Triple(bits[1], bits[0], "")
            else -> Triple(bits.last(), bits.first(), bits.subList(1, bits.size - 1).joinToString(" "))
        }
    }

    private fun esc(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
}
