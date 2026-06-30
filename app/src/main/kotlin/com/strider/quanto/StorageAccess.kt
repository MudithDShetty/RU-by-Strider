package com.strider.quanto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/** Shared storage permission checks for indexing workers and UI. */
object StorageAccess {

    fun hasFullReadAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun externalRootReadable(): Boolean {
        val root = Environment.getExternalStorageDirectory()
        return root.exists() && root.canRead()
    }

    fun canIndexStorage(context: Context): Boolean =
        hasFullReadAccess(context) && externalRootReadable()
}
