// Same backend as the Android app (API_BASE in app/build.gradle.kts).
export const API_BASE = "https://getmeds-admin.vercel.app";

// The newest signed APK. The Deploy APK workflow attaches it to every release
// under this fixed name, so the link never needs updating.
export const APK_URL = "https://github.com/jeydweirdough/digital_business_id/releases/latest/download/GetmedsCard.apk";

// Sent with support tickets, like versionName on Android. Bump with sw.js CACHE.
export const WEB_VERSION = "web-1.0.0";
