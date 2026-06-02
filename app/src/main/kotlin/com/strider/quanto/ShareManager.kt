package com.strider.quanto

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

object ShareManager {

    private const val WHATSAPP_PACKAGE = "com.whatsapp"
    private const val TELEGRAM_PACKAGE = "org.telegram.messenger"
    private const val GMAIL_PACKAGE    = "com.google.android.gm"

    fun share(context: Context, file: IndexedFile, target: String?) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", File(file.path))
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "*/*"

        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val launch = when (target) {
            "whatsapp" -> Intent(send).setPackage(WHATSAPP_PACKAGE)
            "telegram" -> Intent(send).setPackage(TELEGRAM_PACKAGE)
            "email"    -> Intent(send).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf<String>())
                setPackage(GMAIL_PACKAGE)
            }
            "drive"    -> Intent(send).setPackage("com.google.android.apps.docs")
            else       -> null
        }

        try {
            if (launch != null) {
                context.startActivity(launch)
            } else {
                context.startActivity(Intent.createChooser(send, context.getString(R.string.share_file)))
            }
        } catch (_: Exception) {
            context.startActivity(Intent.createChooser(send, context.getString(R.string.share_file)))
        }
    }
}
