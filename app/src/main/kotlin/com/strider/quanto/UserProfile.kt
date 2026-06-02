package com.strider.quanto

import android.content.Context
import android.content.SharedPreferences

object UserProfile {
    const val PREFS_NAME = "strider_quanto_prefs"
    private const val KEY_DISPLAY_NAME = "user_display_name"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDisplayName(context: Context): String? =
        prefs(context).getString(KEY_DISPLAY_NAME, null)?.trim()?.takeIf { it.isNotBlank() }

    fun getNameTokens(context: Context): List<String> =
        tokenizeName(getDisplayName(context) ?: "")

    fun isNameSet(context: Context): Boolean =
        getDisplayName(context) != null

    fun saveName(context: Context, fullName: String) {
        prefs(context).edit()
            .putString(KEY_DISPLAY_NAME, fullName.trim())
            .apply()
    }

    fun tokenizeName(name: String): List<String> =
        name.lowercase()
            .replace(Regex("[^a-z\\u0900-\\u097f\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
            .distinct()
}
