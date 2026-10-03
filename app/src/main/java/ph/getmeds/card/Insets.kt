package ph.getmeds.card

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Keeps a screen's content clear of the status bar, navigation bar and
 * keyboard. From Android 15 apps are drawn edge to edge, under those bars,
 * so without this the top of every screen sits behind the clock and icons.
 * On older versions the bars are already outside the app and this adds nothing.
 */
fun View.padForSystemBars() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    setOnApplyWindowInsetsListener { view, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + maxOf(bars.bottom, ime.bottom))
        } else {
            @Suppress("DEPRECATION")
            view.setPadding(
                left + insets.systemWindowInsetLeft, top + insets.systemWindowInsetTop,
                right + insets.systemWindowInsetRight, bottom + insets.systemWindowInsetBottom,
            )
        }
        insets
    }
    requestApplyInsets()
}
