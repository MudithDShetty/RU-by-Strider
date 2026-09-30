package com.strider.ru

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/** Opens the hosted privacy policy in an in-app browser tab. */
object PrivacyPolicy {

    fun open(context: Context) {
        val uri = Uri.parse(context.getString(R.string.privacy_policy_url))
        try {
            CustomTabsIntent.Builder().build().launchUrl(context, uri)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
