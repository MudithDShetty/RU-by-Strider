package com.strider.quanto

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.view.MenuCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Overflow actions for a search result row. Uses a lightweight [PopupMenu] so list
 * scrolling and binding stay fast.
 */
object ResultFileActions {

    interface Callbacks {
        fun onShareStarted(file: IndexedFile)
        fun onFileRenamed(oldPath: String, updated: IndexedFile)
        fun onFileRemoved(path: String)
        fun toast(message: String)
    }

    fun showMenu(context: Context, anchor: View, file: IndexedFile, callbacks: Callbacks) {
        val themed = ContextThemeWrapper(context, R.style.Theme_StriderQuanto)
        val popup = PopupMenu(themed, anchor, Gravity.END or Gravity.TOP)
        popup.menuInflater.inflate(R.menu.result_file_actions, popup.menu)
        MenuCompat.setGroupDividerEnabled(popup.menu, true)
        tintMenuIcons(context, popup.menu)
        forceShowIcons(popup)
        popup.setOnMenuItemClickListener { item ->
            RuUi.performTapHaptic(anchor)
            when (item.itemId) {
                R.id.action_share -> {
                    ShareManager.share(context, file, null)
                    callbacks.onShareStarted(file)
                    true
                }
                R.id.action_rename -> {
                    showRenameDialog(context, file, callbacks)
                    true
                }
                R.id.action_open_with -> {
                    openWith(context, file)
                    true
                }
                R.id.action_details -> {
                    showDetailsDialog(context, file)
                    true
                }
                R.id.action_remove -> {
                    showRemoveDialog(context, file, callbacks)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun tintMenuIcons(context: Context, menu: android.view.Menu) {
        val crimson = ContextCompat.getColor(context, R.color.ru_crimson)
        for (i in 0 until menu.size()) {
            menu.getItem(i).icon?.mutate()?.let { icon ->
                icon.setTint(crimson)
                menu.getItem(i).icon = icon
            }
        }
    }

    private fun forceShowIcons(popup: PopupMenu) {
        try {
            val method = popup.javaClass.getDeclaredMethod(
                "setForceShowIcon",
                Boolean::class.javaPrimitiveType
            )
            method.isAccessible = true
            method.invoke(popup, true)
        } catch (_: Exception) {
            // Icons are optional on older AppCompat builds.
        }
    }

    private fun openWith(context: Context, file: IndexedFile) {
        val staged = ShareFileAccess.stageForProvider(context, file.path)
        if (staged == null) {
            toast(context, context.getString(R.string.toast_cannot_open, file.name))
            return
        }
        val uri = ShareFileAccess.providerUri(context, staged)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.file_action_open_with))
            )
        } catch (_: Exception) {
            toast(context, context.getString(R.string.toast_cannot_open, file.name))
        }
    }

    private fun showDetailsDialog(context: Context, file: IndexedFile) {
        val extUpper = file.extension.uppercase().take(4).ifBlank { "FILE" }
        val source = RuUi.sourceLabelFromPath(file.path)
        val date = RuUi.formatResultDate(file.lastModified)
        val message = buildString {
            append(context.getString(R.string.file_details_type))
            append(": ")
            append(extUpper)
            append('\n')
            append(context.getString(R.string.file_details_size))
            append(": ")
            append(RuUi.formatDisplaySize(file.sizeBytes))
            if (date.isNotBlank()) {
                append('\n')
                append(context.getString(R.string.file_details_modified))
                append(": ")
                append(date)
            }
            append('\n')
            append(context.getString(R.string.file_details_source))
            append(": ")
            append(source)
            append('\n')
            append(context.getString(R.string.file_details_path))
            append(": ")
            append(file.path)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.file_details_title)
            .setMessage(message)
            .setPositiveButton(R.string.file_details_close, null)
            .show()
    }

    private fun showRenameDialog(context: Context, file: IndexedFile, callbacks: Callbacks) {
        val input = EditText(context).apply {
            setText(file.name)
            setSingleLine()
            val dot = file.name.lastIndexOf('.')
            setSelection(if (dot > 0) dot else file.name.length)
            setPadding(
                (16 * context.resources.displayMetrics.density).toInt(),
                (12 * context.resources.displayMetrics.density).toInt(),
                (16 * context.resources.displayMetrics.density).toInt(),
                (12 * context.resources.displayMetrics.density).toInt(),
            )
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.file_rename_title)
            .setView(input)
            .setNegativeButton(R.string.share_picker_cancel, null)
            .setPositiveButton(R.string.file_rename_save) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isEmpty() || newName.contains('/') || newName.contains('\\')) {
                    callbacks.toast(context.getString(R.string.file_rename_invalid))
                    return@setPositiveButton
                }
                if (newName == file.name) return@setPositiveButton
                renameFile(context, file, newName, callbacks)
            }
            .show()
    }

    private fun renameFile(
        context: Context,
        file: IndexedFile,
        newName: String,
        callbacks: Callbacks,
    ) {
        val oldFile = File(file.path)
        if (!oldFile.exists() || !ShareFileAccess.isPathSafeForShare(file.path)) {
            callbacks.toast(context.getString(R.string.file_rename_failed))
            return
        }
        val newFile = File(oldFile.parentFile, newName)
        if (newFile.exists()) {
            callbacks.toast(context.getString(R.string.file_rename_invalid))
            return
        }
        if (!oldFile.renameTo(newFile)) {
            callbacks.toast(context.getString(R.string.file_rename_failed))
            return
        }
        val newExt = newFile.extension.lowercase()
        val db = DatabaseHelper(context.applicationContext)
        db.renameIndexedPath(
            oldPath = file.path,
            newPath = newFile.absolutePath,
            newName = newName,
            newExt = newExt,
            lastModified = newFile.lastModified(),
        )
        val updated = file.copy(
            path = newFile.absolutePath,
            name = newName,
            extension = newExt,
            lastModified = newFile.lastModified(),
            sizeBytes = newFile.length(),
        )
        callbacks.onFileRenamed(file.path, updated)
    }

    private fun showRemoveDialog(context: Context, file: IndexedFile, callbacks: Callbacks) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.file_remove_title)
            .setMessage(context.getString(R.string.file_remove_message, file.name))
            .setNegativeButton(R.string.share_picker_cancel, null)
            .setPositiveButton(R.string.file_action_remove) { _, _ ->
                removeFile(context, file, callbacks)
            }
            .show()
    }

    private fun removeFile(context: Context, file: IndexedFile, callbacks: Callbacks) {
        val diskFile = File(file.path)
        if (!diskFile.exists() || !ShareFileAccess.isPathSafeForShare(file.path)) {
            callbacks.toast(context.getString(R.string.file_remove_failed))
            return
        }
        if (!diskFile.delete()) {
            callbacks.toast(context.getString(R.string.file_remove_failed))
            return
        }
        DatabaseHelper(context.applicationContext).deleteFiles(listOf(file.path))
        callbacks.onFileRemoved(file.path)
        callbacks.toast(context.getString(R.string.file_removed_ok))
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
