package com.mingliu.inventoryapp

import android.content.Context
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that attaches the stored JWT (if any) as a Bearer
 * token on every outgoing request.
 */
class TokenInterceptor(
    private val context: Context
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val token = prefs.getString("auth_token", "") ?: ""

        val requestBuilder = chain.request().newBuilder()
        if (token.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        return chain.proceed(requestBuilder.build())
    }
}
