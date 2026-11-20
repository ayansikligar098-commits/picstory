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
        // Global stale-session handling: a 401 means the saved account no
        // longer exists on the server (e.g. free-server DB wipe) — Api
        // clears the token and routes here, to the login screen.
        Api.appContext = applicationContext
        Api.onUnauthorized = {
            val intent = Intent(applicationContext, AuthActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK
            applicationContext.startActivity(intent)
        }
        if (Prefs.loggedIn) {
            startActivity(Intent(this, HomeActivity::class.java))
        } else {
            startActivity(Intent(this, AuthActivity::class.java))
        }
        finish()
    }
}
