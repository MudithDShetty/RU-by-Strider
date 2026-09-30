package com.strider.ru

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
 * Play Asset Delivery (fast-follow INT8) is the production path; bundled assets are debug fallback.
 * FP32 high-quality model is delivered on-demand when backend escalation requires it.
 */
class ModelAssetDelivery(private val context: Context) {

    private val assetPackManager: AssetPackManager =
        AssetPackManagerFactory.getInstance(context)

    suspend fun ensureModelReady(onProgress: (fraction: Float, message: String) -> Unit) {
        reconcileModelVersion()
        if (isInt8ModelValid()) {
            markModelCopied()
            onProgress(1f, "Model ready")
            return
        }

        deleteInvalidInt8Model()
        onProgress(0.02f, "Checking search model…")

        if (awaitPlayAssetDelivery(PACK_NAME, onProgress, downloadingLabel = "search model")) {
            try {
                copyInt8FromAssetPack(onProgress)
            } catch (e: ModelDeliveryException) {
                Log.w(TAG, "INT8 asset pack copy failed", e)
            }
            if (isInt8ModelValid()) {
                copyFp32FallbackIfBundled()
                verifyInt8ModelOrThrow()
                markModelCopied()
                onProgress(1f, "Model ready")
                return
            }
            modelFile.takeIf { it.exists() }?.delete()
        }

        if (BuildConfig.DEBUG && copyInt8FromBundledAssets(onProgress)) {
            copyFp32FallbackIfBundled()
            verifyInt8ModelOrThrow()
            markModelCopied()
            onProgress(1f, "Model ready")
            RuLog.i(TAG) { "Using bundled asset fallback (debug / sideload)" }
            return
        }

        throw ModelDeliveryException(
            "Search model not available. Install from Google Play or retry on a network connection."
        )
    }

    /**
     * Fetches the on-demand FP32 pack when backend escalation or golden eval requires it.
     * Returns true when [fp32ModelFile] is present and passes integrity check.
     */
    suspend fun ensureFp32Ready(onProgress: (fraction: Float, message: String) -> Unit): Boolean {
        if (isFp32ModelValid()) {
            onProgress(1f, "High-quality model ready")
            return true
        }

        deleteInvalidFp32Model()
        onProgress(0.02f, "Checking high-quality model…")

        if (awaitPlayAssetDelivery(
                PACK_NAME_FP32,
                onProgress,
                downloadingLabel = "high-quality model",
                explicitFetch = true
            )
        ) {
            try {
                copyFp32FromAssetPack(onProgress)
            } catch (e: ModelDeliveryException) {
                Log.w(TAG, "FP32 asset pack copy failed", e)
            }
            copyFp32FallbackIfBundled()
            if (isFp32ModelValid()) {
                verifyFp32ModelOrThrow()
                onProgress(1f, "High-quality model ready")
                RuLog.i(TAG) { "FP32 model ready (${fp32ModelFile.length()} bytes)" }
                return true
            }
            fp32ModelFile.takeIf { it.exists() }?.delete()
        }

        if (BuildConfig.DEBUG && copyFp32FromBundledAssets(onProgress) && isFp32ModelValid()) {
            verifyFp32ModelOrThrow()
            onProgress(1f, "High-quality model ready")
            RuLog.i(TAG) { "Using bundled FP32 fallback (${fp32ModelFile.length()} bytes)" }
            return true
        }

        Log.w(TAG, "FP32 model not available")
        return false
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
            assetPackManager.cancel(listOf(PACK_NAME, PACK_NAME_FP32))
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel asset pack download", e)
        }
    }

    private fun reconcileModelVersion() {
        val prefs = context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE)
        val storedVersion = prefs.getInt(StriderApp.PREF_MODEL_VERSION, 0)
        if (storedVersion >= BuildConfig.MODEL_VERSION) return

        if (modelFile.exists()) modelFile.delete()
        fp32ModelFile.takeIf { it.exists() }?.delete()
        EmbeddingBackendSelector(context).invalidateOnModelVersionChange()
        prefs.edit()
            .putBoolean(StriderApp.PREF_MODEL_COPIED, false)
            .putBoolean(StriderApp.PREF_MODEL_SHA_VERIFIED, false)
            .putBoolean(StriderApp.PREF_MODEL_FP32_SHA_VERIFIED, false)
            .apply()
        Log.i(TAG, "Model version changed ($storedVersion → ${BuildConfig.MODEL_VERSION}); will re-fetch")
    }

    private fun isInt8ModelValid(): Boolean {
        if (!modelFile.exists()) return false
        val expectedBytes = BuildConfig.MODEL_BYTES
        if (expectedBytes <= 0L) {
            if (modelFile.length() <= 1_000_000L) return false
        } else if (modelFile.length() != expectedBytes) {
            return false
        }
        return passesShaVerification(
            file = modelFile,
            expectedSha = BuildConfig.MODEL_SHA256,
            verifiedPrefKey = StriderApp.PREF_MODEL_SHA_VERIFIED
        )
    }

    private fun isFp32ModelValid(): Boolean {
        if (!fp32ModelFile.exists()) return false
        val expectedBytes = BuildConfig.MODEL_FP32_BYTES
        if (expectedBytes <= 0L) {
            if (fp32ModelFile.length() < EmbeddingBackendSelector.FP32_MIN_BYTES) return false
        } else if (fp32ModelFile.length() != expectedBytes) {
            return false
        }
        return passesShaVerification(
            file = fp32ModelFile,
            expectedSha = BuildConfig.MODEL_FP32_SHA256,
            verifiedPrefKey = StriderApp.PREF_MODEL_FP32_SHA_VERIFIED
        )
    }

    private fun passesShaVerification(file: File, expectedSha: String, verifiedPrefKey: String): Boolean {
        if (expectedSha.isBlank()) return true
        val prefs = context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(verifiedPrefKey, false) &&
            prefs.getInt(StriderApp.PREF_MODEL_VERSION, 0) == BuildConfig.MODEL_VERSION
        ) {
            return true
        }
        if (!ModelIntegrity.verifySha256(file, expectedSha)) return false
        prefs.edit()
            .putBoolean(verifiedPrefKey, true)
            .putInt(StriderApp.PREF_MODEL_VERSION, BuildConfig.MODEL_VERSION)
            .apply()
        return true
    }

    private fun deleteInvalidInt8Model() {
        if (modelFile.exists() && !isInt8ModelValid()) {
            modelFile.delete()
            context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(StriderApp.PREF_MODEL_COPIED, false)
                .putBoolean(StriderApp.PREF_MODEL_SHA_VERIFIED, false)
                .apply()
        }
    }

    private fun deleteInvalidFp32Model() {
        if (fp32ModelFile.exists() && !isFp32ModelValid()) {
            fp32ModelFile.delete()
            context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(StriderApp.PREF_MODEL_FP32_SHA_VERIFIED, false)
                .apply()
        }
    }

    private suspend fun awaitPlayAssetDelivery(
        packName: String,
        onProgress: (Float, String) -> Unit,
        downloadingLabel: String,
        explicitFetch: Boolean = false
    ): Boolean {
        return try {
            waitForPackCompleted(packName, onProgress, downloadingLabel, explicitFetch)
            true
        } catch (e: ModelDeliveryException) {
            Log.w(TAG, "Play Asset Delivery unavailable for $packName: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Play Asset Delivery failed for $packName", e)
            false
        }
    }

    private suspend fun waitForPackCompleted(
        packName: String,
        onProgress: (Float, String) -> Unit,
        downloadingLabel: String,
        explicitFetch: Boolean
    ) {
        var state = currentPackState(packName)
        if (state.status() == AssetPackStatus.COMPLETED) return

        if (state.status() == AssetPackStatus.NOT_INSTALLED ||
            state.status() == AssetPackStatus.UNKNOWN ||
            (explicitFetch &&
                state.status() != AssetPackStatus.COMPLETED &&
                state.status() != AssetPackStatus.DOWNLOADING &&
                state.status() != AssetPackStatus.TRANSFERRING &&
                state.status() != AssetPackStatus.PENDING)
        ) {
            onProgress(0.05f, "Starting $downloadingLabel download…")
            assetPackManager.fetch(listOf(packName)).awaitTask()
            state = currentPackState(packName)
            if (state.status() == AssetPackStatus.COMPLETED) return
        }

        var lastPct = -1
        lateinit var listener: AssetPackStateUpdateListener
        listener = AssetPackStateUpdateListener { update ->
            if (update.name() != packName) return@AssetPackStateUpdateListener
            val pct = updateProgressPercent(update.bytesDownloaded(), update.totalBytesToDownload())
            if (pct != lastPct) {
                lastPct = pct
                reportDownloadProgress(update.status(), pct, onProgress, downloadingLabel)
            }
        }
        assetPackManager.registerListener(listener)
        try {
            while (true) {
                state = currentPackState(packName)
                val pct = updateProgressPercent(state.bytesDownloaded(), state.totalBytesToDownload())
                reportDownloadProgress(state.status(), pct, onProgress, downloadingLabel)

                when (state.status()) {
                    AssetPackStatus.COMPLETED -> return
                    AssetPackStatus.FAILED, AssetPackStatus.CANCELED ->
                        throw ModelDeliveryException("$downloadingLabel download failed (${state.errorCode()})")
                    AssetPackStatus.WAITING_FOR_WIFI ->
                        onProgress(
                            progressFraction(pct),
                            "Waiting for Wi‑Fi — connect to Wi‑Fi or tap Retry to use mobile data"
                        )
                    AssetPackStatus.NOT_INSTALLED -> {
                        assetPackManager.fetch(listOf(packName)).awaitTask()
                    }
                    else -> Unit
                }
                delay(POLL_INTERVAL_MS)
            }
        } finally {
            assetPackManager.unregisterListener(listener)
        }
    }

    private suspend fun currentPackState(packName: String): AssetPackState {
        val states = assetPackManager.getPackStates(listOf(packName)).awaitTask()
        return states.packStates()[packName]
            ?: throw ModelDeliveryException("Asset pack '$packName' not found")
    }

    private fun reportDownloadProgress(
        status: Int,
        pct: Int,
        onProgress: (Float, String) -> Unit,
        downloadingLabel: String
    ) {
        val capitalized = downloadingLabel.replaceFirstChar { it.uppercase() }
        val message = when (status) {
            AssetPackStatus.PENDING -> "Preparing $downloadingLabel download…"
            AssetPackStatus.DOWNLOADING -> "Downloading $downloadingLabel… $pct%"
            AssetPackStatus.TRANSFERRING -> "Installing $downloadingLabel… $pct%"
            AssetPackStatus.WAITING_FOR_WIFI -> "Waiting for Wi‑Fi…"
            else -> "Downloading $capitalized… $pct%"
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

    private suspend fun copyInt8FromAssetPack(onProgress: (Float, String) -> Unit) {
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

    private suspend fun copyFp32FromAssetPack(onProgress: (Float, String) -> Unit) {
        val location = assetPackManager.getPackLocation(PACK_NAME_FP32)
            ?: throw ModelDeliveryException("FP32 asset pack location unavailable")
        val source = File(location.assetsPath(), FP32_FILE_NAME)
        if (!source.exists()) {
            throw ModelDeliveryException("Downloaded FP32 model missing in asset pack")
        }
        onProgress(0.92f, "Copying high-quality model…")
        copyFile(source, fp32ModelFile) { copied, total ->
            if (total > 0) {
                val frac = 0.92f + 0.07f * (copied.toFloat() / total)
                onProgress(frac, "Copying high-quality model… ${(copied * 100 / total).toInt()}%")
            }
        }
    }

    private fun copyInt8FromBundledAssets(onProgress: (Float, String) -> Unit): Boolean {
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
            Log.w(TAG, "Bundled INT8 copy failed", e)
            if (modelFile.exists()) modelFile.delete()
            false
        }
    }

    private fun copyFp32FromBundledAssets(onProgress: (Float, String) -> Unit): Boolean {
        val totalBytes = try {
            context.assets.openFd(FP32_FILE_NAME).use { it.length }
        } catch (_: Exception) {
            return false
        }
        if (totalBytes < EmbeddingBackendSelector.FP32_MIN_BYTES) return false
        return try {
            onProgress(0.1f, "Copying bundled high-quality model…")
            context.assets.open(FP32_FILE_NAME).use { input ->
                fp32ModelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: IOException) {
            Log.w(TAG, "Bundled FP32 copy failed", e)
            if (fp32ModelFile.exists()) fp32ModelFile.delete()
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

    private fun copyFp32FallbackIfBundled() {
        if (!BuildConfig.DEBUG) return
        val assetName = FP32_FILE_NAME
        try {
            context.assets.openFd(assetName).close()
        } catch (_: Exception) {
            return
        }
        val dest = fp32ModelFile
        if (dest.exists() && dest.length() >= EmbeddingBackendSelector.FP32_MIN_BYTES) return
        try {
            context.assets.open(assetName).use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Copied FP32 fallback (${dest.length()} bytes)")
        } catch (e: IOException) {
            Log.w(TAG, "FP32 fallback copy failed", e)
        }
    }

    private fun verifyInt8ModelOrThrow() {
        if (!isInt8ModelValid()) {
            modelFile.delete()
            context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(StriderApp.PREF_MODEL_SHA_VERIFIED, false)
                .apply()
            throw ModelDeliveryException("Downloaded model failed integrity check")
        }
    }

    private fun verifyFp32ModelOrThrow() {
        if (!isFp32ModelValid()) {
            fp32ModelFile.delete()
            context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(StriderApp.PREF_MODEL_FP32_SHA_VERIFIED, false)
                .apply()
            throw ModelDeliveryException("High-quality model failed integrity check")
        }
    }

    private fun markModelCopied() {
        context.getSharedPreferences(StriderApp.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(StriderApp.PREF_MODEL_COPIED, true)
            .putInt(StriderApp.PREF_MODEL_VERSION, BuildConfig.MODEL_VERSION)
            .putBoolean(StriderApp.PREF_MODEL_SHA_VERIFIED, true)
            .commit()
    }

    /** True when INT8 ONNX in [Context.getFilesDir] matches [BuildConfig.MODEL_BYTES]. */
    fun isCachedInt8ModelValid(): Boolean = isInt8ModelValid()

    /** True when first-time setup must reach the network (no valid cache or debug bundle). */
    fun requiresNetworkDownload(): Boolean {
        if (isInt8ModelValid()) return false
        if (hasBundledInt8Fallback()) return false
        return true
    }

    fun hasBundledInt8Fallback(): Boolean {
        if (!BuildConfig.DEBUG) return false
        return try {
            context.assets.openFd(MODEL_FILE_NAME).use { it.length > 0L }
        } catch (_: Exception) {
            false
        }
    }

    private val modelFile: File
        get() = File(context.filesDir, MODEL_FILE_NAME)

    private val fp32ModelFile: File
        get() = File(context.filesDir, FP32_FILE_NAME)

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        cont.invokeOnCancellation { /* Play tasks cannot be cancelled */ }
    }

    companion object {
        private const val TAG = "ModelAssetDelivery"
        const val PACK_NAME = "embeddingmodel"
        const val PACK_NAME_FP32 = "embeddingmodel_fp32"
        const val MODEL_FILE_NAME = EmbeddingEngine.MODEL_ASSET
        const val FP32_FILE_NAME = EmbeddingBackendSelector.MODEL_FP32
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val POLL_INTERVAL_MS = 500L
    }
}
