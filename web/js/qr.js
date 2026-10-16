/**
 * Branded QR, drawn the same way as Qr.kt: the Getmeds cross logo fills the
 * code, every module is a small rounded dot, and the three corner eyes are
 * rounded in brand blue and green.
 *
 * Why it still scans: cameras only sample the centre of each module. So each
 * module is a dot at its centre, dark where the code is dark (a deep shade of
 * the logo colour under it) and white where it's light, even on top of the
 * logo, and the logo only shows in the gaps between dots.
 *
 * level: "H" for the short link, "L" for the long contact (see Qr.kt).
 * Encoding is qrcode-generator (web/vendor/qrcode.js, loaded as a global).
 */

const BLUE = [0x1e, 0xa0, 0xda];
const GREEN = [0x60, 0xa6, 0x44];
// Brand blue and green, deepened just enough for cameras to read them as
// dark against white. The plain brand colours are too light for that.
const EYE_BLUE = rgb(darken(BLUE, 0.78));
const EYE_GREEN = rgb(darken(GREEN, 0.78));
const NAVY = "#0F3B57";

// Alignment pattern centres per QR version (ISO 18004, Annex E).
const ALIGN = [
  [], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34], [6, 22, 38], [6, 24, 42], [6, 26, 46], [6, 28, 50],
  [6, 30, 54], [6, 32, 58], [6, 34, 62], [6, 26, 46, 66], [6, 26, 48, 70], [6, 26, 50, 74], [6, 30, 54, 78],
  [6, 30, 56, 82], [6, 30, 58, 86], [6, 34, 62, 90], [6, 28, 50, 72, 94], [6, 26, 50, 74, 98],
  [6, 30, 54, 78, 102], [6, 28, 54, 80, 106], [6, 32, 58, 84, 110], [6, 30, 58, 86, 114],
  [6, 34, 62, 90, 118], [6, 26, 50, 74, 98, 122], [6, 30, 54, 78, 102, 126], [6, 26, 52, 78, 104, 130],
  [6, 30, 56, 82, 108, 134], [6, 34, 60, 86, 112, 138], [6, 30, 58, 86, 114, 142], [6, 34, 62, 90, 118, 146],
  [6, 30, 54, 78, 102, 126, 150], [6, 24, 50, 76, 102, 128, 154], [6, 28, 54, 80, 106, 132, 158],
  [6, 32, 58, 84, 110, 136, 162], [6, 26, 54, 82, 110, 138, 166], [6, 30, 58, 86, 114, 142, 170],
];

/** Draws the QR for text onto canvas at sizePx square (CSS px; sharp on retina). */
export function drawBranded(canvas, text, sizePx, logo, level) {
  const qrcode = window.qrcode;
  // Byte mode as Latin-1 when it fits, UTF-8 otherwise, like zxing in the app.
  qrcode.stringToBytes = /^[\u0000-ÿ]*$/.test(text)
    ? qrcode.stringToBytesFuncs.default
    : qrcode.stringToBytesFuncs["UTF-8"];
  const code = qrcode(0, level);
  code.addData(text, "Byte");
  code.make();
  const n = code.getModuleCount();
  const version = (n - 17) / 4;

  const scale = window.devicePixelRatio || 1;
  const px = Math.round(sizePx * scale);
  canvas.width = px;
  canvas.height = px;
  canvas.style.width = `${sizePx}px`;
  canvas.style.height = `${sizePx}px`;
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "#FFFFFF";
  ctx.fillRect(0, 0, px, px);

  const quiet = 2;
  const cell = px / (n + 2 * quiet);
  const origin = quiet * cell;

  // 1. The logo, large, behind everything; also kept to look up the colour
  //    under each module.
  const area = n * cell * 0.86;
  const s = Math.min(area / logo.naturalWidth, area / logo.naturalHeight);
  const lw = logo.naturalWidth * s;
  const lh = logo.naturalHeight * s;
  const layer = document.createElement("canvas");
  layer.width = px;
  layer.height = px;
  const lctx = layer.getContext("2d");
  lctx.drawImage(logo, (px - lw) / 2, (px - lh) / 2, lw, lh);
  const under = lctx.getImageData(0, 0, px, px).data;
  ctx.drawImage(layer, 0, 0);

  const inEye = (x, y) => (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7);
  const align = ALIGN[version - 1] || [];
  const alignCentres = align.flatMap((ay) => align.map((ax) => [ax, ay])).filter(([ax, ay]) => !inEye(ax, ay));
  const inAlign = (x, y) => alignCentres.some(([ax, ay]) => Math.abs(x - ax) <= 2 && Math.abs(y - ay) <= 2);

  // 2. Every other module as a rounded dot at its centre.
  const dot = cell * 0.55;
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      if (inEye(x, y) || inAlign(x, y)) continue;
      const cx = origin + (x + 0.5) * cell;
      const cy = origin + (y + 0.5) * cell;
      const i = (clamp(Math.floor(cy), px) * px + clamp(Math.floor(cx), px)) * 4;
      const onLogo = under[i + 3] > 128;
      // qrcode-generator takes (row, col).
      const dark = code.isDark(y, x);
      if (!dark && !onLogo) continue; // white on white
      ctx.fillStyle = !dark ? "#FFFFFF" : onLogo ? rgb(darken([under[i], under[i + 1], under[i + 2]], 0.42)) : NAVY;
      roundRect(ctx, cx - dot / 2, cy - dot / 2, dot, dot, dot * 0.25);
      ctx.fill();
    }
  }

  // 3. Small alignment marks and the three eyes: a rounded ring and a
  //    rounded centre each, with white between.
  for (const [ax, ay] of alignCentres) {
    eye(ctx, origin + (ax - 2) * cell, origin + (ay - 2) * cell, cell, 5, 0.9);
  }
  for (const [ex, ey] of [[0, 0], [n - 7, 0], [0, n - 7]]) {
    eye(ctx, origin + ex * cell, origin + ey * cell, cell, 7, 1.3);
  }
}

/** A finder (7 modules) or alignment (5 modules) mark at (left, top). */
function eye(ctx, left, top, cell, rings, ringRadius) {
  const size = rings * cell;
  ctx.fillStyle = "#FFFFFF";
  ctx.fillRect(left, top, size, size);
  ctx.fillStyle = EYE_BLUE;
  ctx.beginPath();
  roundRect(ctx, left, top, size, size, ringRadius * cell, false);
  roundRect(ctx, left + cell, top + cell, size - 2 * cell, size - 2 * cell, ringRadius * 0.6 * cell, false);
  ctx.fill("evenodd");
  // Centre square: 3 modules in an eye, 1 in an alignment mark.
  const centre = rings === 7 ? 3 : 1;
  const offset = (rings - centre) / 2;
  ctx.fillStyle = EYE_GREEN;
  roundRect(ctx, left + offset * cell, top + offset * cell, centre * cell, centre * cell, 0.45 * centre * cell);
  ctx.fill();
}

function roundRect(ctx, x, y, w, h, r, fresh = true) {
  if (fresh) ctx.beginPath();
  r = Math.min(r, w / 2, h / 2);
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

function clamp(v, size) {
  return Math.min(Math.max(v, 0), size - 1);
}

function darken([r, g, b], f) {
  return [Math.floor(r * f), Math.floor(g * f), Math.floor(b * f)];
}

function rgb([r, g, b]) {
  return `rgb(${r},${g},${b})`;
}
