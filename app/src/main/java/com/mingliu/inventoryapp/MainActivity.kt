package com.mingliu.inventoryapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import java.util.concurrent.TimeUnit

private const val TAG = "MainActivity"
private const val KEEP_ALIVE_INTERVAL_MINUTES = 10L

/**
 * Login screen. On success, stores the JWT/session info in SharedPreferences
 * and navigates to [HomeActivity]. Also schedules a periodic background
 * ping (see [PingWorker]) to keep a free-tier backend host from idling.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetrofitClient.initialize(applicationContext)
        setContentView(R.layout.activity_main)

        val edtUser = findViewById<EditText>(findResourceId("etUsername", "username_edt", "username"))
        val edtPass = findViewById<EditText>(findResourceId("etPassword", "password_edt", "password"))
        val btnLogin = findViewById<MaterialButton>(findResourceId("btnLogin", "login_btn", "button_login"))

        if (edtUser == null || edtPass == null || btnLogin == null) {
            Log.e(TAG, "Login view lookup failed: one or more expected view IDs are missing from the layout")
            Toast.makeText(this, "介面初始化失敗，請檢查 XML ID", Toast.LENGTH_LONG).show()
            return
        }
        // Text color intentionally left to the theme (TextInputEditText follows
        // colorOnSurface automatically) -- hardcoding black here would make text
        // unreadable in dark mode, which is exactly the bug class this pass fixes.

        btnLogin.setOnClickListener {
            val username = edtUser.text.toString().trim()
            val password = edtPass.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "請輸入帳號密碼", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            performLogin(username, password)
        }

        scheduleKeepAlivePing()
    }

    /** Runs the login network call off the main thread and handles the result. */
    private fun performLogin(username: String, password: String) {
        Thread {
            try {
                val response = RetrofitClient.instance.login(username, password).execute()

                if (response.isSuccessful) {
                    val loginResponse = response.body()
                    val token = loginResponse?.access_token ?: ""
                    val isAdminFromServer = loginResponse?.is_admin ?: false

                    if (token.isNotEmpty()) {
                        saveSession(token, isAdminFromServer, username, loginResponse?.operator_id ?: -1)
                        runOnUiThread {
                            startActivity(Intent(this@MainActivity, HomeActivity::class.java))
                            finish()
                        }
                    } else {
                        runOnUiThread { Toast.makeText(this@MainActivity, "後端回傳 Token 為空", Toast.LENGTH_SHORT).show() }
                    }
                } else {
                    Log.e(TAG, "Login failed with HTTP ${response.code()}")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "帳密錯誤或伺服器異常 (代碼: ${response.code()})", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Login request failed: ${t.localizedMessage}")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "連線失敗，請確認後端 Python 是否開啟", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** Persists the authenticated session so subsequent requests carry the token. */
    private fun saveSession(token: String, isAdmin: Boolean, username: String, operatorId: Int) {
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
            .putString("auth_token", token)
            .putBoolean("is_admin", isAdmin)
            .putString("operator_name", username)
            .putInt("my_operator_id", operatorId)
            .apply()
    }

    /** Schedules a periodic ping (see [PingWorker]) so a free-tier backend host stays warm. */
    private fun scheduleKeepAlivePing() {
        val pingRequest = PeriodicWorkRequestBuilder<PingWorker>(
            KEEP_ALIVE_INTERVAL_MINUTES, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "RenderKeepAliveWork",
            ExistingPeriodicWorkPolicy.KEEP, // don't reschedule if one is already pending
            pingRequest
        )
    }

    /**
     * Looks up a view ID by trying several possible resource names in order.
     * Tolerates minor XML ID naming drift between layout revisions.
     */
    private fun findResourceId(vararg names: String): Int {
        for (name in names) {
            val resId = resources.getIdentifier(name, "id", packageName)
            if (resId != 0) return resId
        }
        return 0
    }
}
