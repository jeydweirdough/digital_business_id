package ph.getmeds.card

import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

/** Starts [CrashLog] before any screen opens. */
class GetmedsCardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

/**
 * Keeps the technical details of the app's last crash, so the next
 * "Contact us" ticket can carry them to the developer. Only the error and
 * where in the code it happened; nothing about the person or their card.
 */
object CrashLog {
    private const val PREFS = "crash"
    private const val KEEP_MS = 7L * 24 * 60 * 60 * 1000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                // commit(), not apply(): the process is about to die.
                prefs(app).edit()
                    .putString("trace", "App ${BuildConfig.VERSION_NAME}\n" + trace.take(3000))
                    .putLong("at", System.currentTimeMillis())
                    .commit()
            }
            // Then let Android show its usual "app has stopped" message.
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash in the past week, if any. */
    fun last(context: Context): String? {
        val p = prefs(context)
        if (System.currentTimeMillis() - p.getLong("at", 0) > KEEP_MS) return null
        return p.getString("trace", null)
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
