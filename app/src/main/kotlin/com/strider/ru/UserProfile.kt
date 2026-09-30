package com.strider.ru

import android.content.Context
import android.content.SharedPreferences

object UserProfile {
    const val PREFS_NAME = "strider_quanto_prefs"
    private const val KEY_DISPLAY_NAME = "user_display_name"
    private const val KEY_ONBOARDING_DISMISSED = "onboarding_dismissed"
    private const val KEY_FIRST_RUN_SETUP_COMPLETE = "first_run_setup_complete"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDisplayName(context: Context): String? =
        prefs(context).getString(KEY_DISPLAY_NAME, null)?.trim()?.takeIf { it.isNotBlank() }

    fun getNameTokens(context: Context): List<String> =
        tokenizeName(getDisplayName(context) ?: "")

    fun isNameSet(context: Context): Boolean =
        getDisplayName(context) != null

    fun isOnboardingDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_DISMISSED, false)

    fun isFirstRunSetupComplete(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FIRST_RUN_SETUP_COMPLETE, false)

    /** First-run overlay: name + mandatory index start. Legacy dismissed users are skipped. */
    fun shouldShowFirstRunOnboarding(context: Context): Boolean =
        !isFirstRunSetupComplete(context) && !isOnboardingDismissed(context)

    /** Settings name edit when user skipped name during first run. */
    fun shouldShowNameOnboarding(context: Context): Boolean =
        !isNameSet(context) && !isOnboardingDismissed(context)

    fun markFirstRunSetupComplete(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_FIRST_RUN_SETUP_COMPLETE, true)
            .apply()
    }

    fun markOnboardingDismissed(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ONBOARDING_DISMISSED, true)
            .apply()
    }

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

    /** Given tokenized profile name — first token for primary "my X" retrieval. */
    fun firstNameToken(allTokens: List<String>): String? =
        allTokens.firstOrNull()?.takeIf { it.isNotBlank() }

    /**
     * First + last when 3+ parts (middle excluded from required AND).
     * Two-part names return both tokens.
     */
    fun primaryIdentityTokens(allTokens: List<String>): List<String> {
        if (allTokens.isEmpty()) return emptyList()
        if (allTokens.size <= 2) return allTokens.distinct()
        return listOf(allTokens.first(), allTokens.last()).distinct()
    }

    /** Middle and other tokens between first and last — optional OR retrieval pass. */
    fun alternateIdentityTokens(allTokens: List<String>): List<String> {
        if (allTokens.size <= 2) return emptyList()
        val primary = primaryIdentityTokens(allTokens).toSet()
        return allTokens.filter { it !in primary }.distinct()
    }
}
