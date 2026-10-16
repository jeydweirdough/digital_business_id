import { API_BASE, WEB_VERSION } from "./config.js";

/**
 * The getmeds_backend endpoints the web app uses (app/api/routes/card.py).
 * Same calls as Api.kt in the Android app.
 */

/** A response the backend refused; message is written to be shown as-is. */
export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/** No answer at all: offline, or the request timed out. */
export class NetworkError extends Error {}

/** remember: stay signed in 30 days instead of 1. */
export async function login(email, password, remember) {
  return signIn(await call("POST", "/api/card/login", { email, password, remember }));
}

/** First time / forgot password: emails a 6-digit code to this address, if it's on a card. */
export async function requestCode(email) {
  const res = await call("POST", "/api/card/otp/request", { email });
  return { ticket: res.ticket, sentTo: res.sentTo || "" };
}

export async function resetPassword(ticket, code, password, remember) {
  return signIn(await call("POST", "/api/card/password/reset", { ticket, code, password, remember }));
}

/** Every card the holder can show (just one on older backends). */
export async function me(token) {
  return cardsOf(await call("GET", "/api/card/me", null, token));
}

/** "Contact us": emails a ticket to the developer. files: [{name, type, base64}]. Returns the ticket number. */
export async function sendTicket(token, cardId, topic, message, device, files) {
  const body = { cardId, topic, message, device, appVersion: WEB_VERSION, attachments: files };
  // Attachments take a while to upload on mobile data.
  const res = await call("POST", "/api/card/support", body, token, 60_000);
  return res.ticketId;
}

function signIn(res) {
  return { token: res.token, card: res.card, cards: cardsOf(res), expiresIn: res.expiresIn || 86_400 };
}

function cardsOf(res) {
  return Array.isArray(res.cards) && res.cards.length > 0 ? res.cards : [res.card];
}

async function call(method, path, body, token, timeoutMs = 20_000) {
  const headers = { Accept: "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body) headers["Content-Type"] = "application/json; charset=utf-8";
  let response;
  try {
    response = await fetch(API_BASE + path, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout ? AbortSignal.timeout(timeoutMs) : undefined,
    });
  } catch {
    throw new NetworkError("No internet connection. Check your data or Wi-Fi and try again.");
  }
  const json = await response.json().catch(() => ({}));
  if (!response.ok) throw new ApiError(response.status, messageOf(json, response.status));
  return json;
}

function messageOf(json, status) {
  // FastAPI sends a string for our own errors and a list for validation errors.
  const detail = json.detail;
  if (typeof detail === "string" && detail.trim()) return detail;
  if (Array.isArray(detail)) return "Please check what you entered and try again.";
  return `Something went wrong (error ${status}). Please try again.`;
}
