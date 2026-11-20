package com.picstory.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** View a user's profile (own or someone else's). */
class ProfileActivity : Activity() {
    private var userId: Int = 0
    private var controller: ProfileController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_profile)

        userId = intent.getIntExtra("user_id", 0)
        if (userId == 0) {
            finish()
            return
        }
        findViewById<android.widget.Button>(R.id.btnBack).setOnClickListener { finish() }
        // The 3-line menu shows ONLY when visiting someone else's
        // profile. On your own profile it stays hidden.
        findViewById<android.widget.ImageButton>(R.id.btnMenu).visibility =
            if (userId == Prefs.myUserId) android.view.View.GONE
            else android.view.View.VISIBLE
        controller = ProfileController(this, findViewById(R.id.profileRoot), userId)
        findViewById<android.widget.ImageButton>(R.id.btnMenu).setOnClickListener { v ->
            controller?.showProfileMenu(v)
        }
        controller?.load()
    }

    override fun onResume() {
        super.onResume()
        controller?.load()
    }

    override fun onPause() {
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        controller?.onActivityResult(requestCode, resultCode, data)
    }
}
