package com.webitel.chat.sdk.demo_android.repo

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed cache that maps SDK file IDs to local file paths.
 *
 * Stores the result of a completed download so the same file is never
 * fetched from the server twice — even after the app restarts.
 */
class FileCache(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("file_cache", Context.MODE_PRIVATE)

    fun put(fileId: String, localPath: String) =
        prefs.edit().putString(fileId, localPath).apply()

    fun get(fileId: String): String? =
        prefs.getString(fileId, null)

    companion object {
        lateinit var shared: FileCache
            private set

        fun init(context: Context) {
            shared = FileCache(context.applicationContext)
        }
    }
}
