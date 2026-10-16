/**
 * Sign-in token plus the holder's cards as last fetched, so the QR (which
 * needs no network) still works offline. Same rules as Session.kt: the
 * sign-in ends after 1 day, or 30 with "Remember me", checked here so it
 * ends even with no signal.
 */

const KEY = "getmedsCard.session";

function read() {
  try {
    return JSON.parse(localStorage.getItem(KEY)) || {};
  } catch {
    return {};
  }
}

function write(patch) {
  localStorage.setItem(KEY, JSON.stringify({ ...read(), ...patch }));
}

/** The token, or null if signed out or the sign-in has run out. */
export function token() {
  const s = read();
  if (!s.token) return null;
  if (Date.now() >= (s.expiresAt || 0)) {
    clear();
    return null;
  }
  return s.token;
}

/** Every card the holder can show, in the backend's order. */
export function cards() {
  const all = read().cards;
  return Array.isArray(all) ? all.filter(Boolean) : [];
}

/** The card on screen: the one picked last, else the first. */
export function card() {
  const all = cards();
  const picked = read().selected;
  return all.find((c) => c.id === picked) || all[0] || null;
}

export function select(cardId) {
  write({ selected: cardId });
}

export function save(signIn) {
  write({
    token: signIn.token,
    cards: signIn.cards,
    selected: signIn.card?.id,
    expiresAt: Date.now() + signIn.expiresIn * 1000,
  });
}

/** From /api/card/me: picks up added, changed and removed cards. */
export function saveCards(all) {
  write({ cards: all });
}

/** "website" (Online ID) unless the holder picked "direct" (Offline ID). */
export function shareMode() {
  return read().shareMode === "direct" ? "direct" : "website";
}

export function setShareMode(mode) {
  write({ shareMode: mode });
}

export function clear() {
  localStorage.removeItem(KEY);
}
