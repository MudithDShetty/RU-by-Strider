package com.strider.quanto.eval

import android.content.Context
import android.os.Build
import com.strider.quanto.BuildConfig
import java.util.UUID

internal object EvalSession {
    val sessionId: String = UUID.randomUUID().toString().replace("-", "").take(12)

    fun buildManifest(context: Context): Map<String, Any> = mapOf(
        "schema_version" to SCHEMA_VERSION,
        "session_id" to sessionId,
        "app_version" to BuildConfig.VERSION_NAME,
        "app_version_code" to BuildConfig.VERSION_CODE,
        "eval_logging" to BuildConfig.EVAL_LOGGING,
        "device_model" to Build.MODEL,
        "device_manufacturer" to Build.MANUFACTURER,
        "android_sdk" to Build.VERSION.SDK_INT,
        "android_release" to Build.VERSION.RELEASE,
        "package" to context.packageName
    )

    const val SCHEMA_VERSION = 1
}
