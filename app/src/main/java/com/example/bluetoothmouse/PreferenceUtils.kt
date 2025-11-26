package com.example.bluetoothmouse

import android.content.Context
import android.util.Log

object PreferenceUtils {
    // 根据 Moonlight 官方源码，使用固定的 Unique ID
    fun getUniqueId(context: Context): String {
        val fixedId = "0123456789ABCDEF"
        Log.e("[Mouse]Pref", "Using official fixed Client ID: $fixedId")
        return fixedId
    }
}