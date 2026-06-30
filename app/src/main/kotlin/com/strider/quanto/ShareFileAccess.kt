package com.strider.quanto

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** Validates indexed file paths and stages copies under cache for FileProvider. */
object ShareFileAccess {

    private const val SHARE_CACHE_DIR = "share"

    fun isPathSafeForShare(path: String): Boolean {
        val file = File(path)
        if (!file.exists() || !file.isFile || !file.canRead()) return false
        val storageRoot = Environment.getExternalStorageDirectory().canonicalFile
        return try {
            file.canonicalFile.path.startsWith(storageRoot.path + File.separator) ||
                file.canonicalFile == storageRoot
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Copies [sourcePath] into app cache when safe. Returns null when validation or copy fails.
     */
    fun stageForProvider(context: Context, sourcePath: String): File? {
        if (!isPathSafeForShare(sourcePath)) return null
        val source = File(sourcePath)
        val shareDir = File(context.cacheDir, SHARE_CACHE_DIR).also { it.mkdirs() }
        val dest = File(shareDir, source.name)
        return try {
            source.inputStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            dest
        } catch (_: IOException) {
            dest.takeIf { it.exists() }?.delete()
            null
        }
    }

    fun providerUri(context: Context, stagedFile: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", stagedFile)
}
