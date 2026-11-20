package com.picstory.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject

/** Log in or create a new finsta account. */
class AuthActivity : Activity() {
    private var isRegister = false
    private var submitting = false

    private lateinit var etDisplayName: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etServerUrl: EditText
    private lateinit var tvError: TextView
    private lateinit var btnSubmit: Button
    private lateinit var btnTabLogin: Button
    private lateinit var btnTabRegister: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_auth)

        etDisplayName = findViewById(R.id.etDisplayName)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        etServerUrl = findViewById(R.id.etServerUrl)
        etServerUrl.setText(Prefs.baseUrl)
        tvError = findViewById(R.id.tvError)
        btnSubmit = findViewById(R.id.btnSubmit)
        btnTabLogin = findViewById(R.id.btnTabLogin)
        btnTabRegister = findViewById(R.id.btnTabRegister)

        btnTabLogin.setOnClickListener { setMode(false) }
        btnTabRegister.setOnClickListener { setMode(true) }
        btnSubmit.setOnClickListener { submit() }
        setMode(false)
    }

    private fun setMode(register: Boolean) {
        isRegister = register
        etDisplayName.visibility = if (register) View.VISIBLE else View.GONE
        btnSubmit.text = if (register) getString(R.string.register) else getString(R.string.login)
        btnTabLogin.setBackgroundResource(if (!register) R.drawable.btn_primary else R.drawable.edit_bg)
        btnTabRegister.setBackgroundResource(if (register) R.drawable.btn_primary else R.drawable.edit_bg)
        btnTabLogin.setTextColor(
            resources.getColor(if (!register) R.color.white else R.color.textPrimary, theme)
        )
        btnTabRegister.setTextColor(
            resources.getColor(if (register) R.color.white else R.color.textPrimary, theme)
        )
        tvError.visibility = View.GONE
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }

    private fun submit() {
        // Double-submit guard: rapid taps must not fire two requests.
        if (submitting) return
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()
        val name = etDisplayName.text.toString().trim()
        // The Server URL can be changed on this screen — without it the
        // phone would always try its own loopback address and never reach the server.
        val serverUrl = etServerUrl.text.toString().trim().trimEnd('/')
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            showError("Enter a valid Server URL (starting with http:// or https://)")
            return
        }
        Prefs.baseUrl = serverUrl
        if (username.length < 3) {
            showError("Username must be at least 3 characters")
            return
        }
        if (!username.matches(Regex("^[A-Za-z0-9_]{3,32}$"))) {
            showError("Username can only have letters, numbers and _")
            return
        }
        if (password.length < 4) {
            showError("Password must be at least 4 characters")
            return
        }
        if (isRegister && name.isEmpty()) {
            showError("Enter your name")
            return
        }
        submitting = true
        btnSubmit.isEnabled = false
        btnSubmit.text = "Please wait…"
        tvError.visibility = View.GONE
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
            if (isRegister) put("display_name", name)
        }
        val path = if (isRegister) "/api/auth/register" else "/api/auth/login"
        Api.call("POST", path, body) { ok, json, err ->
            runOnUiThread {
                submitting = false
                btnSubmit.isEnabled = true
                btnSubmit.text = if (isRegister) getString(R.string.register) else getString(R.string.login)
                if (!ok || json == null) {
                    showError(err ?: "Something went wrong, please try again")
                    return@runOnUiThread
                }
                try {
                    val token = json.getString("token")
                    val user = User.fromJson(json.getJSONObject("user"))
                    Prefs.saveAuth(token, user)
                    startActivity(Intent(this, HomeActivity::class.java))
                    finish()
                } catch (e: Exception) {
                    showError("Could not understand the server's reply")
                }
            }
        }
    }
}
