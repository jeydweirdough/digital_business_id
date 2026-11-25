package ph.getmeds.card

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Update notices. The app isn't on the Play Store, so it asks the backend
 * (/api/card/app) whether this version is behind the newest release. If it is,
 * it says so, with what's new, and "Update" opens the download page
 * (https://jeydweirdough.github.io/digital_business_id/) where the new APK is
 * installed over this one, keeping the sign-in.
 *
 * The backend decides: it reads the latest GitHub Release (Deploy APK), and
 * marks an update required when the release says "[required]" or this version
 * is below its minimum. A required update has no Later and comes back until
 * it's done. The link and the rules can change there without an app update.
 *
 * Checked at most every few hours, and never in the way: offline or with the
 * backend unreachable, the app just carries on.
 */
object Updates {
    private const val PREFS = "updates"
    private const val CHECK_EVERY_MS = 3L * 60 * 60 * 1000
    /** "Later" hides a (not required) version for this long. */
    private const val LATER_MS = 24L * 60 * 60 * 1000

    class Release(val version: String, val required: Boolean, val notes: String, val url: String)

    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null

    /** Call when a main screen comes to the front. Shows the notice if there's an update. */
    fun check(activity: Activity) {
        if (dialog?.isShowing == true) return
        val prefs = prefs(activity)
        val cached = cachedRelease(activity)
        val stale = System.currentTimeMillis() - prefs.getLong("checkedAt", 0) > CHECK_EVERY_MS ||
            // Checked by an older version of the app: ask again.
            prefs.getString("checkedBy", null) != BuildConfig.VERSION_NAME
        if (!stale) {
            cached?.let { offer(activity, it) }
            return
        }
        pool.execute {
            val answer = runCatching { Api.appUpdate() }
            main.post {
                answer.getOrNull()?.let { update ->
                    val edit = prefs.edit()
                        .putLong("checkedAt", System.currentTimeMillis())
                        .putString("checkedBy", BuildConfig.VERSION_NAME)
                    if (update.available) {
                        edit.putString("version", update.latestVersion.ifBlank { "?" })
                            .putBoolean("required", update.required)
                            .putString("notes", update.notes)
                            .putString("url", update.downloadUrl)
                    } else {
                        edit.remove("version")
                    }
                    edit.apply()
                }
                val release = if (answer.isSuccess) cachedRelease(activity) else cached
                if (!activity.isFinishing && !activity.isDestroyed) release?.let { offer(activity, it) }
            }
        }
    }

    private fun offer(activity: Activity, release: Release) {
        // Installed since the check: nothing to offer.
        if (release.version != "?" && !isNewer(release.version, BuildConfig.VERSION_NAME)) return
        val prefs = prefs(activity)
        if (!release.required && prefs.getString("laterVersion", null) == release.version &&
            System.currentTimeMillis() < prefs.getLong("laterUntil", 0)
        ) return
        if (dialog?.isShowing == true) return

        val intro = activity.getString(if (release.required) R.string.update_required_message else R.string.update_message)
        val message = if (release.notes.isBlank()) intro
            else intro + "\n\n" + activity.getString(R.string.update_whats_new) + "\n" + release.notes
        val builder = AlertDialog.Builder(activity)
            .setTitle(
                if (release.version == "?") activity.getString(R.string.update_title_plain)
                else activity.getString(R.string.update_title, release.version)
            )
            .setMessage(message)
            .setPositiveButton(R.string.update_now, null)
            .setCancelable(!release.required)
        if (!release.required) {
            builder.setNegativeButton(R.string.update_later) { _, _ ->
                prefs.edit()
                    .putString("laterVersion", release.version)
                    .putLong("laterUntil", System.currentTimeMillis() + LATER_MS)
                    .apply()
            }
        }
        dialog = builder.show().apply {
            setOnDismissListener { dialog = null }
            // Set here rather than in the builder so a required notice stays open behind the browser.
            getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                openDownloadPage(activity, release.url)
                if (!release.required) dismiss()
            }
        }
    }

    private fun openDownloadPage(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, activity.getString(R.string.update_no_browser, url), Toast.LENGTH_LONG).show()
        }
    }

    private fun cachedRelease(context: Context): Release? {
        val p = prefs(context)
        val version = p.getString("version", null) ?: return null
        val url = p.getString("url", null) ?: return null
        return Release(version, p.getBoolean("required", false), p.getString("notes", null).orEmpty(), url)
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
