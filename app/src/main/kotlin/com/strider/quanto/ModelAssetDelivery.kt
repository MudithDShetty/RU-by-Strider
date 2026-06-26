package com.strider.quanto

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ModelDeliveryException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Ensures [EmbeddingEngine.MODEL_ASSET] exists in [Context.getFilesDir] before ONNX load.
 * Play Asset Delivery (fast-follow) is the production path; bundled assets are debug fallback.
 */
class ModelAssetDelivery(private val context: Context) {

    private val assetPackManager: AssetPackManager =
        AssetPackManagerFactory.getInstance(context)

    suspend fun ensureModelReady(onProgress: (fraction: Float, message: String) -> Unit) {
        reconcileModelVersion()
        if (isModelValid()) {
            markModelCopied()
            onProgress(1f, "Model ready")
            return
        }

        deleteInvalidModel()
        onProgress(0.02f, "Checking search model…")

        if (awaitPlayAssetDelivery(onProgress)) {
            copyFromAssetPack(onProgress)
            verifyModelOrThrow()
            markModelCopied()
            onProgress(1f, "Model ready")
            return
        }

        if (copyFromBundledAssets(onProgress)) {
            verifyModelOrThrow()
            markModelCopied()
            onProgress(1f, "Model ready")
            Log.i(TAG, "Using bundled asset fallback (debug / sideload)")
            return
        }

        throw ModelDeliveryException(
            "Search model not available. Install from Google Play or retry on a network connection."
        )
    }

    /** Prompt Play's mobile-data consent UI — call from an [Activity] when state is WAITING_FOR_WIFI. */
    fun showCellularConfirmation(activity: Activity) {
        try {
            assetPackManager.showConfirmationDialog(activity)
        } catch (e: Exception) {
            Log.w(TAG, "Could not show cellular confirmation", e)
        }
    }

    fun cancelActiveDownload() {
        try {
            assetPackManager.cancel(listOf(PACK_NAME))
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel asset pack download", e)
        }
    }

    private fun reconcileModelVersion() {
        val prefs = context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE)
        val storedVersion = prefs.getInt(StriderApp.PREF_MODEL_VERSION, 0)
        if (storedVersion >= BuildConfig.MODEL_VERSION) return

        if (modelFile.exists()) {
            modelFile.delete()
        }
        prefs.edit()
            .putBoolean(StriderApp.PREF_MODEL_COPIED, false)
            .apply()
        Log.i(TAG, "Model version changed ($storedVersion → ${BuildConfig.MODEL_VERSION}); will re-fetch")
    }

    private fun isModelValid(): Boolean {
        if (!modelFile.exists()) return false
        val expected = BuildConfig.MODEL_BYTES
        if (expected <= 0L) return modelFile.length() > 1_000_000L
        return modelFile.length() == expected
    }

    private fun deleteInvalidModel() {
        if (modelFile.exists() && !isModelValid()) {
            modelFile.delete()
            context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(StriderApp.PREF_MODEL_COPIED, false)
                .apply()
        }
    }

    private suspend fun awaitPlayAssetDelivery(
        onProgress: (Float, String) -> Unit
    ): Boolean {
        return try {
            waitForPackCompleted(onProgress)
            true
        } catch (e: ModelDeliveryException) {
            Log.w(TAG, "Play Asset Delivery unavailable: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Play Asset Delivery failed", e)
            false
        }
    }

    private suspend fun waitForPackCompleted(
        onProgress: (Float, String) -> Unit
    ) {
        var state = currentPackState()
        if (state.status() == AssetPackStatus.COMPLETED) return

        if (state.status() == AssetPackStatus.NOT_INSTALLED ||
            state.status() == AssetPackStatus.UNKNOWN
        ) {
            onProgress(0.05f, "Starting model download…")
            assetPackManager.fetch(listOf(PACK_NAME)).awaitTask()
            state = currentPackState()
            if (state.status() == AssetPackStatus.COMPLETED) return
        }

        var lastPct = -1
        lateinit var listener: AssetPackStateUpdateListener
        listener = AssetPackStateUpdateListener { update ->
            if (update.name() != PACK_NAME) return@AssetPackStateUpdateListener
            val pct = updateProgressPercent(update.bytesDownloaded(), update.totalBytesToDownload())
            if (pct != lastPct) {
                lastPct = pct
                reportDownloadProgress(update.status(), pct, onProgress)
            }
        }
        assetPackManager.registerListener(listener)
        try {
            while (true) {
                state = currentPackState()
                val pct = updateProgressPercent(state.bytesDownloaded(), state.totalBytesToDownload())
                reportDownloadProgress(state.status(), pct, onProgress)

                when (state.status()) {
                    AssetPackStatus.COMPLETED -> return
                    AssetPackStatus.FAILED, AssetPackStatus.CANCELED ->
                        throw ModelDeliveryException("Model download failed (${state.errorCode()})")
                    AssetPackStatus.WAITING_FOR_WIFI ->
                        onProgress(
                            progressFraction(pct),
                            "Waiting for Wi‑Fi — connect to Wi‑Fi or tap Retry to use mobile data"
                        )
                    AssetPackStatus.NOT_INSTALLED -> {
                        assetPackManager.fetch(listOf(PACK_NAME)).awaitTask()
                    }
                    else -> Unit
                }
                delay(POLL_INTERVAL_MS)
            }
        } finally {
            assetPackManager.unregisterListener(listener)
        }
    }

    private suspend fun currentPackState(): AssetPackState {
        val states = assetPackManager.getPackStates(listOf(PACK_NAME)).awaitTask()
        return states.packStates()[PACK_NAME]
            ?: throw ModelDeliveryException("Asset pack '$PACK_NAME' not found")
    }

    private fun reportDownloadProgress(
        status: Int,
        pct: Int,
        onProgress: (Float, String) -> Unit
    ) {
        val message = when (status) {
            AssetPackStatus.PENDING -> "Preparing model download…"
            AssetPackStatus.DOWNLOADING -> "Downloading search model… $pct%"
            AssetPackStatus.TRANSFERRING -> "Installing search model… $pct%"
            AssetPackStatus.WAITING_FOR_WIFI -> "Waiting for Wi‑Fi…"
            else -> "Downloading search model… $pct%"
        }
        onProgress(progressFraction(pct), message)
    }

    private fun progressFraction(pct: Int): Float = 0.05f + (pct / 100f) * 0.85f

    private fun updateProgressPercent(
        bytesDownloaded: Long,
        totalBytes: Long
    ): Int {
        if (totalBytes > 0L) {
            return ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
        }
        return 0
    }

    private suspend fun copyFromAssetPack(onProgress: (Float, String) -> Unit) {
        val location = assetPackManager.getPackLocation(PACK_NAME)
            ?: throw ModelDeliveryException("Asset pack location unavailable")
        val source = File(location.assetsPath(), MODEL_FILE_NAME)
        if (!source.exists()) {
            throw ModelDeliveryException("Downloaded model file missing in asset pack")
        }
        onProgress(0.92f, "Copying model to app storage…")
        copyFile(source, modelFile) { copied, total ->
            if (total > 0) {
                val frac = 0.92f + 0.07f * (copied.toFloat() / total)
                onProgress(frac, "Copying model… ${(copied * 100 / total).toInt()}%")
            }
        }
    }

    private fun copyFromBundledAssets(onProgress: (Float, String) -> Unit): Boolean {
        val totalBytes = try {
            context.assets.openFd(MODEL_FILE_NAME).use { it.length }
        } catch (_: Exception) {
            return false
        }
        if (totalBytes <= 0) return false
        return try {
            onProgress(0.1f, "Copying bundled model…")
            context.assets.open(MODEL_FILE_NAME).use { input ->
                modelFile.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var copied = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        copied += read
                        val frac = 0.1f + 0.85f * (copied.toFloat() / totalBytes)
                        onProgress(frac, "Copying model… ${(copied * 100 / totalBytes).toInt()}%")
                    }
                }
            }
            true
        } catch (e: IOException) {
            Log.w(TAG, "Bundled asset copy failed", e)
            if (modelFile.exists()) modelFile.delete()
            false
        }
    }

    private fun copyFile(
        source: File,
        dest: File,
        onBytesCopied: (copied: Long, total: Long) -> Unit
    ) {
        val total = source.length()
        var copied = 0L
        source.inputStream().use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    copied += read
                    onBytesCopied(copied, total)
                }
            }
        }
    }

    private fun verifyModelOrThrow() {
        if (!isModelValid()) {
            modelFile.delete()
            throw ModelDeliveryException("Downloaded model failed integrity check")
        }
    }

    private fun markModelCopied() {
        context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(StriderApp.PREF_MODEL_COPIED, true)
            .putInt(StriderApp.PREF_MODEL_VERSION, BuildConfig.MODEL_VERSION)
            .apply()
    }

    private val modelFile: File
        get() = File(context.filesDir, MODEL_FILE_NAME)

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        cont.invokeOnCancellation { /* Play tasks cannot be cancelled */ }
    }

    companion object {
        private const val TAG = "ModelAssetDelivery"
        const val PACK_NAME = "embeddingmodel"
        const val MODEL_FILE_NAME = EmbeddingEngine.MODEL_ASSET
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val POLL_INTERVAL_MS = 500L
    }
}
