package com.picstory.app

import android.app.Activity
import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Build

/** Applies the user's theme choice (system / light / dark). */
object ThemeHelper {
    fun apply(activity: Activity) {
        when (Prefs.theme) {
            "light" -> setNight(activity, false)
            "dark" -> setNight(activity, true)
            else -> setNight(activity, null)
        }
    }

    private fun setNight(activity: Activity, night: Boolean?) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val um = activity.getSystemService(UiModeManager::class.java)
                um?.nightMode = when (night) {
                    true -> UiModeManager.MODE_NIGHT_YES
                    false -> UiModeManager.MODE_NIGHT_NO
                    else -> UiModeManager.MODE_NIGHT_AUTO
                }
            } else {
                // Pre-31: mutate the app configuration (best effort; falls
                // back to the system theme if the device ignores it).
                val res = activity.resources
                val conf = Configuration(res.configuration)
                val mode = when (night) {
                    true -> Configuration.UI_MODE_NIGHT_YES
                    false -> Configuration.UI_MODE_NIGHT_NO
                    else -> Configuration.UI_MODE_NIGHT_UNDEFINED
                }
                conf.uiMode = (conf.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
                @Suppress("DEPRECATION")
                res.updateConfiguration(conf, res.displayMetrics)
            }
        } catch (e: Exception) {
            // Theme failures must never crash the app.
        }
    }
}
