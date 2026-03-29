package com.webitel.chat.sdk.demo_android.repo

import android.content.Context
import android.content.SharedPreferences


class AuthRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_HOST = "key_host"
        private const val KEY_TOKEN = "key_token"
        private const val KEY_USER_ID = "key_user_id"
        private const val KEY_ISSUER = "key_issuer"
        private const val KEY_USER_NAME = "key_user_name"
    }


    fun saveAuthInfo(info: AuthInfo) {
        prefs.edit()
            .putString(KEY_HOST, info.host)
            .putString(KEY_TOKEN, info.token)
            .putString(KEY_USER_ID, info.userId)
            .putString(KEY_ISSUER, info.issuer)
            .putString(KEY_USER_NAME, info.userName)
            .apply()
    }


    fun getAuthInfo(): AuthInfo? {
        val host = prefs.getString(KEY_HOST, null)
        val token = prefs.getString(KEY_TOKEN, null)
        val userId = prefs.getString(KEY_USER_ID, null)
        val issuer = prefs.getString(KEY_ISSUER, null)
        val userName = prefs.getString(KEY_USER_NAME, null)

        return if (host != null && token != null && issuer != null && userName != null && userId != null) {
            AuthInfo(host, token, userId, issuer, userName)
        } else null
    }
}


data class AuthInfo(
    val host: String,
    val token: String,
    val userId: String,
    val issuer: String,
    val userName: String
)