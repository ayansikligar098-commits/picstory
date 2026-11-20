package com.picstory.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Entry point: routes to Auth or Home depending on the saved token. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ThemeHelper.apply(this)
        if (Prefs.loggedIn) {
            startActivity(Intent(this, HomeActivity::class.java))
        } else {
            startActivity(Intent(this, AuthActivity::class.java))
        }
        finish()
    }
}
