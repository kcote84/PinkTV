package ca.pinktv

import android.app.Activity
import android.view.View

/** Keep interactive UI inside the TV overscan area; video and backgrounds stay full screen. */
object TvSafeArea {
    data class Insets(val horizontal: Int, val vertical: Int)
    fun insets(activity: Activity, wide: Boolean = false): Insets {
        val metrics = activity.resources.displayMetrics
        if(!DeviceLayout.isTv(activity)) return Insets((16 * metrics.density).toInt(), (12 * metrics.density).toInt())
        val fraction = if(wide) .08f else .05f
        return Insets((metrics.widthPixels * fraction).toInt(), (metrics.heightPixels * fraction).toInt())
    }
    fun apply(view: View, activity: Activity, wide: Boolean = false) {
        val inset = insets(activity, wide)
        view.setPadding(inset.horizontal, inset.vertical, inset.horizontal, inset.vertical)
    }
}
