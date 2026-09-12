package com.aali.ebookreader

import android.app.Activity
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Distraction free reading: hides the app's top and bottom bars together with
 * the phone's status and navigation bars. Swiping from an edge brings the
 * system bars back temporarily, and tapping the page restores everything.
 */
object Immersive {

    fun apply(activity: Activity, on: Boolean, vararg bars: View?) {
        try {
            val window = activity.window
            WindowCompat.setDecorFitsSystemWindows(window, !on)
            val c = WindowInsetsControllerCompat(window, window.decorView)
            if (on) {
                c.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                c.show(WindowInsetsCompat.Type.systemBars())
            }
        } catch (_: Exception) {
        }
        for (b in bars) b?.visibility = if (on) View.GONE else View.VISIBLE
    }
}
