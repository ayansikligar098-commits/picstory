package com.picstory.app

import android.app.Activity

/**
 * 70a is dark-only since v1.5. The old approach (UiModeManager.setNightMode
 * on API 31+, Configuration mutation below it) never applied the
 * values-night palette on the user's phone — screenshots stayed light —
 * and AppCompat (the proper fix) is not available in the offline build
 * mirror, so activities cannot migrate to AppCompatActivity.
 *
 * Instead the palette itself is dark: values/colors.xml carries the black
 * palette, and the base theme is the dark Material theme, so a fresh
 * install renders black with light text whatever the system theme is.
 * apply() is kept (every activity calls it) but is now a deliberate no-op.
 */
object ThemeHelper {
    fun apply(activity: Activity) {
        // Dark-only: nothing to switch. Kept so call sites stay unchanged.
    }
}
