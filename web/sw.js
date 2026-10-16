// Keeps the web app working with no signal, so the Offline ID QR can still be
// shown. The app's own files come from the cache first; the backend API is
// never cached (it goes straight to the network).
//
// Bump CACHE (and WEB_VERSION in js/config.js) on every change, so phones
// drop the old files.
const CACHE = "getmeds-card-web-1.1.0";

const FILES = [
  "./",
  "index.html",
  "app.html",
  "manifest.webmanifest",
  "css/styles.css",
  "js/app.js",
  "js/api.js",
  "js/config.js",
  "js/contact.js",
  "js/qr.js",
  "js/session.js",
  "vendor/qrcode.js",
  "img/getmeds_logo.png",
  "img/getmeds_mark.png",
  "img/icon-192.png",
  "img/icon-512.png",
  "img/apple-touch-icon.png",
  "img/screens/login.webp",
  "img/screens/online.webp",
  "img/screens/offline.webp",
  "fonts/poppins_regular.ttf",
  "fonts/poppins_medium.ttf",
  "fonts/poppins_semibold.ttf",
  "fonts/poppins_bold.ttf",
];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(FILES)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET" || url.origin !== self.location.origin) return;
  event.respondWith(
    caches.match(event.request, { ignoreSearch: true }).then((hit) => hit || fetch(event.request)),
  );
});
