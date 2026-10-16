/**
 * The holder's contact as a vCard, for Offline ID: the QR carries the
 * details themselves, so the other phone offers to add the contact with no
 * website and no internet. Same output as Contact.kt.
 */

export function vcard(card) {
  const lines = ["BEGIN:VCARD", "VERSION:3.0"];
  const fullName = card.fullName || "";
  const [family, given, middle] = nameParts(fullName);
  lines.push(`N:${esc(family)};${esc(given)};${esc(middle)};;`);
  lines.push(`FN:${esc(fullName)}`);
  if (card.company?.trim()) lines.push(`ORG:${esc(card.company)}`);
  if (card.jobTitle?.trim()) lines.push(`TITLE:${esc(card.jobTitle)}`);

  // Same order and de-duplication as the website's "Save to contacts".
  const mobile = toE164(card.mobile);
  const office = toE164(card.officePhone);
  const listed = new Set();
  const tel = (number, type) => {
    if (number && !listed.has(number)) {
      listed.add(number);
      lines.push(`TEL;TYPE=${type}:${number}`);
    }
  };
  tel(mobile, "CELL");
  tel(office, "WORK");
  tel(toE164(card.whatsapp) || mobile, "CELL");
  tel(toE164(card.viber) || mobile, "CELL");
  strings(card.otherMobiles).forEach((m) => tel(toE164(m), "CELL"));

  const emails = [];
  [card.email, ...strings(card.otherEmails)]
    .map((e) => (e || "").trim())
    .filter(Boolean)
    .forEach((e) => {
      if (!emails.some((x) => x.toLowerCase() === e.toLowerCase())) emails.push(e);
    });
  emails.forEach((e) => lines.push(`EMAIL;TYPE=WORK:${esc(e)}`));

  lines.push("END:VCARD");
  return lines.join("\r\n") + "\r\n";
}

/**
 * Whatever was typed into the Studio -> +639171234567; "" if unusable.
 * Same rules as toE164() in getmeds_frontend/src/lib/businessCard.ts.
 */
export function toE164(raw) {
  if (!raw || !raw.trim()) return "";
  const trimmed = raw.trim();
  const digits = trimmed.replace(/\D/g, "");
  if (!digits) return "";
  if (trimmed.startsWith("+")) return `+${digits}`;
  if (digits.startsWith("00")) return `+${digits.slice(2)}`;
  if (digits.startsWith("63") && digits.length === 12) return `+${digits}`;
  if (digits.startsWith("0") && digits.length === 11) return `+63${digits.slice(1)}`;
  if (digits.startsWith("9") && digits.length === 10) return `+63${digits}`;
  if (digits.startsWith("0")) return `+63${digits.slice(1)}`;
  if (digits.length >= 7 && digits.length <= 9) return `+63${digits}`;
  if (digits.length >= 10) return `+${digits}`;
  return "";
}

/** "Juan Dela Cruz" -> family/given/middle, which decides how contacts sort. */
function nameParts(fullName) {
  const bits = fullName.trim().split(/\s+/).filter(Boolean);
  if (bits.length === 0) return ["", "", ""];
  if (bits.length === 1) return ["", bits[0], ""];
  if (bits.length === 2) return [bits[1], bits[0], ""];
  return [bits[bits.length - 1], bits[0], bits.slice(1, -1).join(" ")];
}

function strings(value) {
  return Array.isArray(value) ? value.filter((s) => typeof s === "string" && s.trim()) : [];
}

function esc(value) {
  return value.replace(/\\/g, "\\\\").replace(/\n/g, "\\n").replace(/,/g, "\\,").replace(/;/g, "\\;");
}
