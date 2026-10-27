package ca.pinktv

import android.content.Context
import android.content.res.Configuration

object LayoutRules {
    fun posterColumns(widthDp: Int) = when { widthDp < 600 -> 2; widthDp < 800 -> 4; else -> 6 }
}
object DeviceLayout {
    fun isTv(context: Context) = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION || context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    fun compact(context: Context) = !isTv(context) && context.resources.configuration.screenWidthDp < 600
}
