package tv.qissat.app.input

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

object DeviceType {
    /** Android TV, Google TV, Fire TV / Fire Stick, or anything without a touchscreen. */
    fun isTv(context: Context): Boolean {
        val pm = context.packageManager
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
            pm.hasSystemFeature("amazon.hardware.fire_tv") ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
}
