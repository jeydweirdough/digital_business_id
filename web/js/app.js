import * as Api from "./api.js";
import * as Session from "./session.js";
import { vcard } from "./contact.js";
import { drawBranded } from "./qr.js";

/**
 * The web version of the Android app, for iPhones (and anyone without the
 * APK): the same sign-in, the same branded QR in Online ID and Offline ID,
 * and Contact us. No phone tap: browsers can't act as an NFC tag.
 */

const $ = (id) => document.getElementById(id);
const MIN_PASSWORD = 8;
const QR_SIZE = 300;

// ── Screens ────────────────────────────────────────────────────────────────

function showScreen(id) {
  for (const s of ["install", "login", "share", "support"]) $(s).hidden = s !== id;
  window.scrollTo(0, 0);
  if (id === "share") keepAwake();
}

function start() {
  if (needsInstall()) openInstall();
  else if (Session.token() && Session.card()) openCard();
  else openLogin();
}

// ── Sign in / set password (LoginActivity) ─────────────────────────────────

let loginMode = "signIn"; // "signIn" | "resetEmail" | "resetCode"
let ticket = null;
let sentTo = "";

function openLogin(message) {
  showScreen("login");
  showLoginMode("signIn");
  if (message) loginError(message);
}

function showLoginMode(next) {
  loginMode = next;
  $("loginError").hidden = true;
  $("stepEmail").hidden = next === "resetCode";
  $("passwordGroup").hidden = next !== "signIn";
  $("stepCode").hidden = next !== "resetCode";
  // Asked wherever the next tap signs in.
  $("rememberRow").hidden = next === "resetEmail";
  if (next === "signIn") {
    ticket = null;
    $("loginHeading").textContent = "Sign in";
    $("loginSubtitle").textContent = "Sign in with the email on your business card.";
    $("primary").textContent = "Sign in";
    $("switchMode").textContent = "First time / Forgot password?";
  } else if (next === "resetEmail") {
    ticket = null;
    $("loginHeading").textContent = "Set your password";
    $("loginSubtitle").textContent = "Enter the email on your business card. We'll email you a 6-digit code.";
    $("primary").textContent = "Send code";
    $("switchMode").textContent = "Back to sign in";
  } else {
    $("loginHeading").textContent = "Set your password";
    $("loginSubtitle").textContent = `We emailed a code to ${sentTo}. Enter it and choose a password. Check spam if you don't see it.`;
    $("primary").textContent = "Save password and sign in";
    $("switchMode").textContent = "Back to sign in";
    $("code").focus();
  }
}

async function signIn() {
  const email = $("email").value.trim();
  const password = $("password").value;
  if (!email || !password) return loginError("Enter your email and password.");
  await loginBusy(async () => {
    try {
      Session.save(await Api.login(email, password, $("remember").checked));
      openCard();
    } catch (e) {
      // No password yet: go straight to setting one.
      if (e instanceof Api.ApiError && e.status === 409) showLoginMode("resetEmail");
      loginError(failureText(e));
    }
  });
}

async function requestCode() {
  const email = $("email").value.trim();
  if (!email) return loginError("Enter the email on your business card.");
  await loginBusy(async () => {
    try {
      const res = await Api.requestCode(email);
      ticket = res.ticket;
      sentTo = res.sentTo;
      for (const id of ["code", "newPassword", "confirmPassword"]) $(id).value = "";
      showLoginMode("resetCode");
    } catch (e) {
      loginError(failureText(e));
    }
  });
}

async function resetPassword() {
  if (!ticket) return showLoginMode("resetEmail");
  const code = $("code").value.trim();
  const password = $("newPassword").value;
  if (code.length < 4) return loginError("Enter the 6-digit code from the email.");
  if (password.length < MIN_PASSWORD) return loginError(`Use at least ${MIN_PASSWORD} characters for your password.`);
  if (password !== $("confirmPassword").value) return loginError("The two passwords don't match.");
  await loginBusy(async () => {
    try {
      Session.save(await Api.resetPassword(ticket, code, password, $("remember").checked));
      openCard();
    } catch (e) {
      loginError(failureText(e));
    }
  });
}

async function loginBusy(work) {
  $("loginProgress").hidden = false;
  $("loginError").hidden = true;
  $("primary").disabled = true;
  $("switchMode").disabled = true;
  try {
    await work();
  } finally {
    $("loginProgress").hidden = true;
    $("primary").disabled = false;
    $("switchMode").disabled = false;
  }
}

function loginError(message) {
  $("loginError").textContent = message;
  $("loginError").hidden = false;
}

function failureText(e) {
  if (e instanceof Api.ApiError || e instanceof Api.NetworkError) return e.message;
  return "Something went wrong. Please try again.";
}

$("loginForm").addEventListener("submit", (event) => {
  event.preventDefault();
  if (loginMode === "signIn") signIn();
  else if (loginMode === "resetEmail") requestCode();
  else resetPassword();
});
$("switchMode").addEventListener("click", () => showLoginMode(loginMode === "signIn" ? "resetEmail" : "signIn"));

// Show/hide eye on each password box (PasswordToggle.kt).
const EYE = '<svg viewBox="0 0 24 24" fill="none" stroke="#64748B" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" style="width:22px;height:22px"><path d="M2 12C4.5 7 8 5 12 5s7.5 2 10 7c-2.5 5-6 7-10 7S4.5 17 2 12z"/><circle cx="12" cy="12" r="3"/></svg>';
const EYE_OFF = '<svg viewBox="0 0 24 24" fill="none" stroke="#2563EB" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" style="width:22px;height:22px"><path d="M2 12C4.5 7 8 5 12 5s7.5 2 10 7c-2.5 5-6 7-10 7S4.5 17 2 12z"/><circle cx="12" cy="12" r="3"/><path d="M4 4l16 16"/></svg>';
for (const button of document.querySelectorAll("[data-toggle]")) {
  const input = $(button.dataset.toggle);
  button.innerHTML = EYE;
  button.addEventListener("click", () => {
    const show = input.type === "password";
    input.type = show ? "text" : "password";
    button.innerHTML = show ? EYE_OFF : EYE;
    button.setAttribute("aria-label", show ? "Hide password" : "Show password");
  });
}

// ── The QR (ShareActivity) ─────────────────────────────────────────────────

const logo = new Image();
logo.src = "img/getmeds_mark.png";
let shownPayload = "";

function openCard() {
  showScreen("share");
  const card = Session.card();
  if (!card) return signedOut(null);
  render(card);
  lastOnline = null;
  applyInternet();
  refresh();
}

/** Picks up changes made in the Studio, and notices a card switched off. */
async function refresh() {
  const token = Session.token();
  if (!token) return signedOut("You've been signed out. Please sign in again.");
  try {
    Session.saveCards(await Api.me(token));
    const card = Session.card();
    if (card && !$("share").hidden) render(card);
  } catch (e) {
    if (e instanceof Api.ApiError && (e.status === 401 || e.status === 403)) signedOut(e.message);
    // Offline: the saved QR still works, so carry on quietly.
  }
}

function render(card) {
  $("name").textContent = card.fullName || "";
  $("jobTitle").textContent = card.jobTitle || "";
  $("jobTitle").hidden = !card.jobTitle?.trim();

  const mode = Session.shareMode();
  $("modeWebsite").setAttribute("aria-pressed", String(mode === "website"));
  $("modeDirect").setAttribute("aria-pressed", String(mode === "direct"));

  // Online: the card page link. Offline: the contact itself, built here from
  // the saved card, so it works with no internet on either phone.
  const payload = mode === "website" ? card.shareUrl || "" : vcard(card);
  const canShare = mode === "website" ? !!card.shareUrl?.trim() : !!card.fullName?.trim();
  $("notice").hidden = canShare;
  for (const id of ["qr", "scanLine", "howToScan"]) $(id).hidden = !canShare;
  $("scanLine").textContent =
    mode === "website" ? "Scan to open my card on getmeds.ph" : "Scan to add my contact. No internet needed.";

  if (canShare && payload !== shownPayload) {
    const size = Math.min(QR_SIZE, window.innerWidth - 48);
    const draw = () => {
      // Strong error correction for the short link; light for the long
      // contact, which keeps it from getting too dense to scan.
      drawBranded($("qr"), payload, size, logo, mode === "website" ? "H" : "L");
      shownPayload = payload;
    };
    if (logo.complete && logo.naturalWidth && window.qrcode) draw();
    else whenReady(draw);
  }
}

function whenReady(draw) {
  const check = () => (logo.complete && logo.naturalWidth && window.qrcode ? draw() : setTimeout(check, 50));
  check();
}

function setMode(mode) {
  if (mode === Session.shareMode()) return;
  Session.setShareMode(mode);
  const card = Session.card();
  if (card) render(card);
}

$("modeWebsite").addEventListener("click", () => setMode("website"));
$("modeDirect").addEventListener("click", () => setMode("direct"));

// Picking the mode from this phone's internet, like the Android app: each
// change in connection picks the mode again, and the holder can still tap the
// other one in between.
let lastOnline = null;
function applyInternet() {
  const online = navigator.onLine;
  if (online === lastOnline || $("share").hidden) return;
  lastOnline = online;
  setMode(online ? "website" : "direct");
}
window.addEventListener("online", applyInternet);
window.addEventListener("offline", applyInternet);

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState !== "visible" || $("share").hidden) return;
  // The sign-in can run out while the app sits in the background.
  if (!Session.token()) return signedOut("You've been signed out. Please sign in again.");
  keepAwake();
  refresh();
});

// Awake while the QR is up, so the screen doesn't lock mid-scan.
let wakeLock = null;
async function keepAwake() {
  if (!("wakeLock" in navigator) || wakeLock) return;
  try {
    wakeLock = await navigator.wakeLock.request("screen");
    wakeLock.addEventListener("release", () => (wakeLock = null));
  } catch {
    // Not allowed here (battery saver, older iOS); the QR still works.
  }
}

// ── Guidance and menu, one tap away ────────────────────────────────────────

const STEPS_WEBSITE =
  "1. Open your phone camera and point it at this QR.\n2. Tap the link that appears.\n3. On the card page, tap Save to contacts.\n4. Tap Save.";
const STEPS_DIRECT =
  "1. Open your phone camera and point it at this QR.\n2. Tap the contact that appears (View).\n3. If it asks to add to an existing contact, tap Create New Contact.\n4. Tap Save.";

$("howToScan").addEventListener("click", () => {
  const website = Session.shareMode() === "website";
  dialog({
    title: website ? "Online ID" : "Offline ID",
    body: website
      ? `Opens your card on getmeds.ph. They need internet.\n\n${STEPS_WEBSITE}`
      : `Goes straight to their contacts. No internet needed.\n\n${STEPS_DIRECT}`,
    actions: [{ label: "Got it" }],
  });
});

$("menu").addEventListener("click", () => {
  const count = Session.cards().length;
  const list = [];
  if (count > 1) list.push({ label: `Switch card (${count} cards)`, onClick: pickCard });
  list.push({ label: "How it works", onClick: howItWorks });
  list.push({ label: "Sign out", onClick: confirmSignOut });
  dialog({ list, actions: [{ label: "Cancel" }] });
});

function howItWorks() {
  dialog({
    title: "How it works",
    body:
      "Online ID: the QR opens your card on getmeds.ph, where they can save your contact. They need internet.\n\n" +
      "Offline ID: the QR holds your contact itself, so their phone adds it straight away. No internet needed on either phone.\n\n" +
      "The app picks Online ID when your phone has internet and Offline ID when it doesn't. You can tap either one yourself.",
    actions: [{ label: "Got it" }],
  });
}

/** One person, several cards: choose which one the QR shares. */
function pickCard() {
  const current = Session.card()?.id;
  dialog({
    title: "Which card do you want to share?",
    list: Session.cards().map((c) => ({
      label: c.fullName,
      detail: [c.jobTitle, c.company].filter((s) => s?.trim()).join(" · "),
      selected: c.id === current,
      onClick: () => {
        Session.select(c.id);
        render(c);
      },
    })),
    actions: [{ label: "Cancel" }],
  });
}

function confirmSignOut() {
  dialog({
    body: "Sign out of Getmeds Card on this phone?",
    actions: [{ label: "Cancel" }, { label: "Sign out", onClick: () => signedOut(null) }],
  });
}

function signedOut(message) {
  Session.clear();
  shownPayload = "";
  wakeLock?.release().catch(() => {});
  openLogin(message);
}

// ── Contact us (SupportActivity) ───────────────────────────────────────────

// Match MAX_TICKET_FILES / MAX_TICKET_BYTES in the backend.
const MAX_FILES = 5;
const MAX_TOTAL_BYTES = 3 * 1024 * 1024;
const MAX_PHOTO_SIDE = 1600;
let files = []; // {name, type, bytes: Uint8Array}

$("contactUs").addEventListener("click", () => {
  const card = Session.card();
  $("ticketIntro").textContent = `Having a problem with the app or your card? Send a ticket to the developer. We'll reply to ${card?.email || "your email"}.`;
  $("ticketError").hidden = true;
  showScreen("support");
});
$("supportBack").addEventListener("click", () => showScreen("share"));
$("addFile").addEventListener("click", () => $("fileInput").click());

$("fileInput").addEventListener("change", async (event) => {
  const picked = [...event.target.files];
  event.target.value = "";
  $("ticketError").hidden = true;
  const room = MAX_FILES - files.length;
  if (picked.length > room) ticketError(`You can attach up to ${MAX_FILES} files.`);
  let unreadable = false;
  for (const file of picked.slice(0, room)) {
    const read = await readAttachment(file).catch(() => null);
    if (read) files.push(read);
    else unreadable = true;
  }
  if (unreadable) ticketError("Some files couldn't be attached. Use photos, screenshots or PDFs under 3 MB.");
  else if (totalBytes() > MAX_TOTAL_BYTES) ticketError("The attachments are too big together. Remove one or two.");
  renderFiles();
});

/** Photos are shrunk before upload so a few fit under the server's size limit. */
async function readAttachment(file) {
  if (file.type === "application/pdf") {
    const bytes = new Uint8Array(await file.arrayBuffer());
    return bytes.length <= MAX_TOTAL_BYTES ? { name: file.name, type: file.type, bytes } : null;
  }
  if (!file.type.startsWith("image/")) return null;
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, MAX_PHOTO_SIDE / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  canvas.getContext("2d").drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.85));
  if (!blob) return null;
  const name = file.name.replace(/\.[^.]+$/, "") + ".jpg";
  return { name, type: "image/jpeg", bytes: new Uint8Array(await blob.arrayBuffer()) };
}

function renderFiles() {
  const list = $("files");
  list.replaceChildren(
    ...files.map((file, i) => {
      const row = document.createElement("div");
      row.className = "file";
      const label = document.createElement("span");
      label.textContent = `${file.name}  ·  ${sizeLabel(file.bytes.length)}`;
      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "icon-button";
      remove.setAttribute("aria-label", `Remove ${file.name}`);
      remove.innerHTML =
        '<svg viewBox="0 0 24 24" fill="none" stroke="#64748B" stroke-width="2" stroke-linecap="round" style="width:20px;height:20px"><path d="M6 6l12 12M18 6L6 18"/></svg>';
      remove.addEventListener("click", () => {
        files.splice(i, 1);
        renderFiles();
      });
      row.append(label, remove);
      return row;
    }),
  );
  $("addFile").textContent = files.length ? "Add another" : "Add attachment";
  $("addFile").disabled = files.length >= MAX_FILES;
}

$("ticketForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const message = $("message").value.trim();
  if (!message) return ticketError("Tell us what's wrong in the message box.");
  if (totalBytes() > MAX_TOTAL_BYTES) return ticketError("The attachments are too big together. Remove one or two.");
  const token = Session.token();
  if (!token) return signedOut("You've been signed out. Please sign in again.");

  $("ticketProgress").hidden = false;
  $("ticketError").hidden = true;
  $("sendTicket").disabled = true;
  try {
    const attachments = files.map((f) => ({ name: f.name, type: f.type, base64: toBase64(f.bytes) }));
    const card = Session.card();
    const id = await Api.sendTicket(token, card?.id || "", $("topic").value, message.slice(0, 5000), deviceLabel(), attachments);
    $("message").value = "";
    files = [];
    renderFiles();
    showScreen("share");
    dialog({
      title: "Ticket sent",
      body: `Your ticket number is ${id}. The developer will reply to ${card?.email || "your email"}.`,
      actions: [{ label: "Got it" }],
    });
  } catch (e) {
    if (e instanceof Api.ApiError && (e.status === 401 || e.status === 403)) return signedOut(e.message);
    ticketError(failureText(e));
  } finally {
    $("ticketProgress").hidden = true;
    $("sendTicket").disabled = false;
  }
});

function ticketError(message) {
  $("ticketError").textContent = message;
  $("ticketError").hidden = false;
}

function totalBytes() {
  return files.reduce((sum, f) => sum + f.bytes.length, 0);
}

function sizeLabel(bytes) {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function toBase64(bytes) {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary);
}

/** Like "iPhone, iOS 17.5 (web app)": helps the developer reproduce a problem. */
function deviceLabel() {
  const ua = navigator.userAgent;
  const ios = ua.match(/(iPhone|iPad|iPod).*? OS (\d+(?:_\d+)*)/);
  const android = ua.match(/Android ([\d.]+)/);
  let label;
  if (ios) label = `${ios[1]}, iOS ${ios[2].replace(/_/g, ".")}`;
  else if (android) label = `Android ${android[1]}`;
  else label = navigator.platform || "Unknown device";
  return `${label} (${isStandalone() ? "installed web app" : "browser"})`;
}

// ── Pop-ups (AlertDialog) ──────────────────────────────────────────────────

/** {title?, body?, list?: [{label, detail?, selected?, onClick}], actions: [{label, onClick?}]} */
function dialog({ title = "", body = "", list = [], actions = [] }) {
  const box = $("dialog");
  $("dialogTitle").textContent = title;
  $("dialogTitle").hidden = !title;
  $("dialogBody").textContent = body;
  $("dialogBody").hidden = !body;
  const close = (then) => () => {
    box.close();
    then?.();
  };
  $("dialogList").replaceChildren(
    ...list.map((item) => {
      const b = document.createElement("button");
      b.type = "button";
      b.textContent = item.label;
      if (item.detail) {
        const small = document.createElement("small");
        small.textContent = item.detail;
        b.append(small);
      }
      if (item.selected) b.className = "selected";
      b.addEventListener("click", close(item.onClick));
      return b;
    }),
  );
  $("dialogList").hidden = list.length === 0;
  $("dialogActions").replaceChildren(
    ...actions.map((a) => {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "text-button";
      b.textContent = a.label;
      b.addEventListener("click", close(a.onClick));
      return b;
    }),
  );
  box.showModal();
}

// Tap outside a pop-up to close it.
$("dialog").addEventListener("click", (event) => {
  if (event.target === $("dialog")) $("dialog").close();
});

// ── Add to Home Screen, on iPhone ──────────────────────────────────────────
// iPhones install a web app only from the browser's Share menu, and the
// browser can't tell whether it's already on the Home Screen. So in the
// browser we show the steps first; opened from the Home Screen, it's the app.

function isStandalone() {
  return navigator.standalone === true || matchMedia("(display-mode: standalone)").matches;
}

function isIos() {
  return /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
}

const BROWSER_KEY = "getmedsCard.useInBrowser";

function needsInstall() {
  return isIos() && !isStandalone() && !localStorage.getItem(BROWSER_KEY);
}

function openInstall() {
  showScreen("install");
  // Safari's Share button is in the bottom bar; Chrome, Firefox and Edge on
  // iPhone have it at the top right.
  const top = /CriOS|FxiOS|EdgiOS/.test(navigator.userAgent);
  $("shareArrow").classList.toggle("top", top);
  $("shareWhere").textContent = top ? "at the top right of the screen" : "at the bottom of the screen";
}

$("useInBrowser").addEventListener("click", () => {
  localStorage.setItem(BROWSER_KEY, "1");
  showInstallHint();
  start();
});

// Using it in the browser: a small reminder that it can still be installed.
const HINT_KEY = "getmedsCard.installHintDismissed";
function showInstallHint() {
  if (!isIos() || isStandalone() || localStorage.getItem(HINT_KEY)) return;
  for (const hint of document.querySelectorAll("[data-install-hint]")) {
    hint.innerHTML =
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12M8 7l4-4 4 4"/><path d="M6 11H5a1 1 0 0 0-1 1v8a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-8a1 1 0 0 0-1-1h-1"/></svg>' +
      "<span><b>Install this app:</b> tap Share, then <b>Add to Home Screen</b>.</span>" +
      '<button type="button" class="icon-button" aria-label="Dismiss"><svg viewBox="0 0 24 24" fill="none" stroke="#64748B" stroke-width="2" stroke-linecap="round" style="width:18px;height:18px"><path d="M6 6l12 12M18 6L6 18"/></svg></button>';
    hint.querySelector("button").addEventListener("click", () => {
      localStorage.setItem(HINT_KEY, "1");
      document.querySelectorAll("[data-install-hint]").forEach((h) => (h.hidden = true));
    });
    hint.hidden = false;
  }
}
showInstallHint();

// ── Offline support ────────────────────────────────────────────────────────

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("sw.js").catch(() => {});
}

start();
