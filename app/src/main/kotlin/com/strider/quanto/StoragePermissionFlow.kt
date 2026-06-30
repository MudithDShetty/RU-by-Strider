package com.strider.quanto

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat

/** Shared storage permission requests for onboarding and Index tab. */
object StoragePermissionFlow {

    const val REQUEST_READ_STORAGE = 100
    const val REQUEST_MANAGE_STORAGE = 101

    fun hasAccess(context: android.content.Context): Boolean =
        StorageAccess.hasFullReadAccess(context)

    fun requestAccess(activity: Activity) {
        if (hasAccess(activity)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                },
                REQUEST_MANAGE_STORAGE
            )
        } else {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_READ_STORAGE
            )
        }
    }

    fun openAppSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                },
                REQUEST_MANAGE_STORAGE
            )
        } else {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                }
            )
        }
    }

    fun isReadStorageRequest(requestCode: Int): Boolean =
        requestCode == REQUEST_READ_STORAGE

    fun isManageStorageRequest(requestCode: Int): Boolean =
        requestCode == REQUEST_MANAGE_STORAGE

    fun wasReadStorageGranted(grantResults: IntArray): Boolean =
        grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
}
