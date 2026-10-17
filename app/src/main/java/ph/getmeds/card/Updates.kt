package ph.getmeds.card

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * In-app updates. The app isn't on the Play Store, so it looks at the latest
 * GitHub Release (published by the Deploy APK workflow) and, if it's newer,
 * offers to update. "Update" downloads the APK inside the app and hands it to
 * Android's installer, which asks once to allow installs from this app and
 * then installs over the current version, keeping the sign-in.
 *
 * A release whose notes contain "[required]" (Deploy APK → "Required update")
 * can't be put off: the prompt has no Later and comes back until it's done.
 *
 * Checked at most every few hours, and never in the way: offline or with
 * GitHub unreachable, the app just carries on.
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/jeydweirdough/digital_business_id/releases/latest"
    private const val PREFS = "updates"
    private const val CHECK_EVERY_MS = 3L * 60 * 60 * 1000
    /** "Later" hides a (not required) version for this long. */
    private const val LATER_MS = 24L * 60 * 60 * 1000
    const val ACTION_INSTALL_STATUS = "ph.getmeds.card.UPDATE_INSTALL_STATUS"

    class Release(val version: String, val apkUrl: String, val required: Boolean)

    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var installing = false

    /** Call when a main screen comes to the front. Shows the prompt if there's an update. */
    fun check(activity: Activity) {
        if (installing || dialog?.isShowing == true) return
        val prefs = prefs(activity)
        val cached = cachedRelease(activity)
        val stale = System.currentTimeMillis() - prefs.getLong("checkedAt", 0) > CHECK_EVERY_MS
        if (!stale) {
            cached?.let { offer(activity, it) }
            return
        }
        pool.execute {
            val latest = runCatching { fetchLatest() }.getOrNull()
            main.post {
                if (latest != null) {
                    prefs.edit()
                        .putLong("checkedAt", System.currentTimeMillis())
                        .putString("version", latest.version)
                        .putString("apkUrl", latest.apkUrl)
                        .putBoolean("required", latest.required)
                        .apply()
                }
                if (!activity.isFinishing && !activity.isDestroyed) (latest ?: cached)?.let { offer(activity, it) }
            }
        }
    }

    private fun offer(activity: Activity, release: Release) {
        if (!isNewer(release.version, BuildConfig.VERSION_NAME)) return
        val prefs = prefs(activity)
        if (!release.required && prefs.getString("laterVersion", null) == release.version &&
            System.currentTimeMillis() < prefs.getLong("laterUntil", 0)
        ) return
        if (dialog?.isShowing == true) return

        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title, release.version))
            .setMessage(if (release.required) R.string.update_required_message else R.string.update_message)
            .setPositiveButton(R.string.update_now) { _, _ -> install(activity, release) }
            .setCancelable(!release.required)
        if (!release.required) {
            builder.setNegativeButton(R.string.update_later) { _, _ ->
                prefs.edit()
                    .putString("laterVersion", release.version)
                    .putLong("laterUntil", System.currentTimeMillis() + LATER_MS)
                    .apply()
            }
        }
        dialog = builder.show().apply { setOnDismissListener { dialog = null } }
    }

    // ── Download and install ─────────────────────────────────────────────────

    private fun install(activity: Activity, release: Release) {
        installing = true
        val percent = TextView(activity).apply { setText(R.string.update_downloading); gravity = Gravity.CENTER }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
            addView(bar)
            addView(percent)
        }
        var cancelled = false
        val progress = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title, release.version))
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled = true }
            .show()
        dialog = progress.apply { setOnDismissListener { dialog = null } }

        val app = activity.applicationContext
        pool.execute {
            val result = runCatching {
                downloadInto(app, release.apkUrl, isCancelled = { cancelled }) { pct ->
                    main.post {
                        bar.isIndeterminate = pct < 0
                        if (pct >= 0) {
                            bar.progress = pct
                            percent.text = activity.getString(R.string.update_progress, pct)
                        }
                    }
                }
            }
            main.post {
                installing = false
                runCatching { progress.dismiss() }
                result.onFailure {
                    if (!cancelled) Toast.makeText(app, R.string.update_failed, Toast.LENGTH_LONG).show()
                    // Still required: ask again rather than leave the app on an unsupported version.
                    if (release.required && !activity.isFinishing) offer(activity, release)
                }
                // On success Android's installer takes over (see InstallStatusReceiver).
            }
        }
    }

    /**
     * Streams the APK straight into an install session (no file to clean up,
     * no storage permission) and commits it. Android then shows its own
     * "Install this update?" screen.
     */
    private fun downloadInto(context: Context, url: String, isCancelled: () -> Boolean, onProgress: (Int) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            try {
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                session.openWrite("update.apk", 0, if (total > 0) total else -1).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -2
                        while (true) {
                            if (isCancelled()) throw InterruptedException("cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != lastPct) onProgress(pct).also { lastPct = pct }
                        }
                    }
                    session.fsync(out)
                }
            } finally {
                conn.disconnect()
            }
            val status = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, sessionId, status, flags).intentSender)
        } catch (e: Throwable) {
            runCatching { session.abandon() }
            throw e
        } finally {
            session.close()
        }
    }

    // ── GitHub ───────────────────────────────────────────────────────────────

    private fun fetchLatest(): Release? {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.optString("tag_name").removePrefix("v").trim()
            if (version.isEmpty()) return null
            val assets = json.optJSONArray("assets") ?: return null
            var apk = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name == "GetmedsCard.apk") { apk = a.optString("browser_download_url"); break }
                if (apk.isEmpty() && name.endsWith(".apk")) apk = a.optString("browser_download_url")
            }
            if (apk.isEmpty()) return null
            val required = json.optString("body").contains("[required]", ignoreCase = true)
            return Release(version, apk, required)
        } finally {
            conn.disconnect()
        }
    }

    private fun cachedRelease(context: Context): Release? {
        val p = prefs(context)
        val version = p.getString("version", null) ?: return null
        val url = p.getString("apkUrl", null) ?: return null
        return Release(version, url, p.getBoolean("required", false))
    }

    /** "1.15.0" vs "1.14.2": compares each number in turn. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Hears back from Android's installer. The one step that needs the person is
 * the "Install this update?" screen, which the installer hands us to show.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // The new version replaces this one.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // They chose Cancel on the install screen.
            else -> Toast.makeText(context, R.string.update_failed, Toast.LENGTH_LONG).show()
        }
    }
}
