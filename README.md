# Getmeds Card (Android)

Lets an employee share their Getmeds business card by **tapping phones (NFC)**, a **QR code**, or a **link**.
The person receiving it needs no app: their phone opens `https://getmeds.ph/card/<slug>`, where they choose to save the contact or not.

## How it fits together

| Piece | Where |
|---|---|
| Card data (name, title, numbers, card photos, active switch) | Sanity `businessCard` documents (`getmeds_database`) |
| Employee sign-in + card API (`/api/card/otp/request`, `/api/card/otp/verify`, `/api/card/me`) | `getmeds_backend/app/api/routes/card.py` |
| Public card page the receiver sees | `getmeds_frontend` (`/card/<slug>`) |
| This app (holder side) | here |

- **Sign-in:** SMS code to the mobile number on the employee's card. Only numbers on an active, published card get a code.
- **Turning a card off** in the Studio (`active` = false) signs the holder out the next time the app refreshes.
- **Phone tap:** while the Share screen is open, the phone acts as an NFC tag (Type 4, NDEF URI record) holding the card link — see `CardHceService.kt`. Android phones and iPhone XS or newer read it with no app. The holder's phone must be Android with NFC; everyone else uses the QR on the same screen.

## Build

Needs JDK 17 and the Android SDK (easiest: install Android Studio, then *Open* this folder).

```
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

**Deploy:** bump `versionCode`/`versionName`, push, then run **Deploy APK** from the Actions tab (`.github/workflows/deploy.yml`). It builds a signed release APK and publishes it as the GitHub Release `v<versionName>`. It needs the secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. Phones only accept an update signed with the same key as the installed app.

The backend URL is `API_BASE` in `app/build.gradle.kts`.

**In-app updates** (`Updates.kt`): the app checks the latest GitHub Release every few hours. If it's newer than the installed version, it offers **Update**, downloads the APK inside the app and hands it to Android's installer, keeping the sign-in. Tick **Required update** when running Deploy APK to make the prompt impossible to put off (it adds `[required]` to the release notes). Offline, the check is skipped.

**Save QR as image**: ⋮ → *Save QR as image* saves the QR on screen (Online ID or Offline ID) as a PNG in the phone's photos, in a "Getmeds Card" album, and offers to share it.

## Web app and download page (`web/`)

`web/index.html` is the page to send people to: **Download for Android** (the APK from the latest GitHub Release) and **Get it on iPhone**, which opens the web app `web/app.html`. iPhone users add it to their home screen from Safari's Share menu. It has the same sign-in, Online ID / Offline ID QR and Contact us as the Android app, and works offline after the first visit. Phone tap is Android only, because browsers can't act as an NFC tag.

- Plain HTML/CSS/JS, with no build step. To try it locally, run `python -m http.server -d web` and open `http://localhost:8000`.
- **Deploy web** (`.github/workflows/pages.yml`) publishes `web/` to GitHub Pages on every push to `main` that touches it. One-time setup: *Settings → Pages → Source: GitHub Actions*.
- After changing anything in `web/`, bump `CACHE` in `web/sw.js` and `WEB_VERSION` in `web/js/config.js` so installed copies update.
- **Deploy APK** also attaches the APK as `GetmedsCard.apk`, which is what the download button links to.

## Testing the tap

1. Install the APK on an Android phone with NFC, sign in, open **Share my card**.
2. Unlock the other phone and touch the backs together (on iPhone, the top edge to the Android's back).
3. The other phone shows the card link; the holder's screen shows "Sent!" and vibrates.

Phone-to-phone NFC varies by model: if nothing happens, hold still for a second or shift the phones a little. Test on the models employees actually carry.
