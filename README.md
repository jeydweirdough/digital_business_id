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

Pushing to GitHub also builds the APK (`.github/workflows/build.yml`); download it from the run's artifacts.

The backend URL is `API_BASE` in `app/build.gradle.kts`.

## Testing the tap

1. Install the APK on an Android phone with NFC, sign in, open **Share my card**.
2. Unlock the other phone and touch the backs together (on iPhone, the top edge to the Android's back).
3. The other phone shows the card link; the holder's screen shows "Sent!" and vibrates.

Phone-to-phone NFC varies by model: if nothing happens, hold still for a second or shift the phones a little. Test on the models employees actually carry.
