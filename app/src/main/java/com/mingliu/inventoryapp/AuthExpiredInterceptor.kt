package com.mingliu.inventoryapp

import android.content.Context
import android.content.Intent
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that reacts to an expired/invalid session.
 *
 * Any response with HTTP 401 is treated as "the server rejected our token":
 * stored credentials are cleared and the user is bounced back to the login
 * screen with a fresh task stack.
 */
class AuthExpiredInterceptor(
    private val context: Context
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        if (response.code == 401) {
            val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()

            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)
        }

        return response
    }
}
