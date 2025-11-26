package com.example.bluetoothmouse

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object PreferenceUtils {
    private const val PREF_NAME = "moonlight_prefs"
    private const val KEY_CLIENT_ID = "client_id"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getUniqueId(context: Context): String {
        val prefs = getPrefs(context)
        var uuid = prefs.getString(KEY_CLIENT_ID, null)
        if (uuid == null) {
            uuid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_CLIENT_ID, uuid).apply()
        }
        return uuid!!
    }
}