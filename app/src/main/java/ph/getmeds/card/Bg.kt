package ph.getmeds.card

import android.app.Activity
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Runs blocking work off the main thread and hands the result back on it. */
object Bg {
    private val pool = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    /** [done] is skipped if [activity] has gone away in the meantime. */
    fun <T> run(activity: Activity, work: () -> T, done: (Result<T>) -> Unit) {
        pool.execute {
            val result = runCatching(work)
            main.post { if (!activity.isFinishing && !activity.isDestroyed) done(result) }
        }
    }
}
