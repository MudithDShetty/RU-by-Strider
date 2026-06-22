package com.strider.quanto

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

object RuTheme {

    const val PREF_DARK_MODE = "dark_mode"

    fun applyStored(context: Context) {
        applyDarkMode(isDarkMode(context))
    }

    fun isDarkMode(context: Context): Boolean =
        context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_DARK_MODE, false)

    fun setDarkMode(context: Context, dark: Boolean) {
        context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(PREF_DARK_MODE, dark)
        }
        applyDarkMode(dark)
    }

    private fun applyDarkMode(dark: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
