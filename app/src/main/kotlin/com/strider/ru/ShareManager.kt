package com.strider.ru

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast

object ShareManager {

    private const val WHATSAPP_PACKAGE = "com.whatsapp"
    private const val TELEGRAM_PACKAGE = "org.telegram.messenger"
    private const val GMAIL_PACKAGE    = "com.google.android.gm"

    fun share(context: Context, file: IndexedFile, target: String?) {
        val staged = ShareFileAccess.stageForProvider(context, file.path)
        if (staged == null) {
            Toast.makeText(context, context.getString(R.string.toast_cannot_open, file.name), Toast.LENGTH_SHORT).show()
            return
        }
        val uri = ShareFileAccess.providerUri(context, staged)
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
