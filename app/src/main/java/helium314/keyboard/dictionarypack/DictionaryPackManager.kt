package helium314.keyboard.dictionarypack

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import helium314.keyboard.latin.common.Links
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Manages dictionary pack downloads from the remote repository.
 * Handles background downloads, progress reporting, and retry logic.
 */
class DictionaryPackManager(private val context: Context) {

    companion object {
        private const val TAG = "DictionaryPackManager"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
        private const val DOWNLOAD_TIMEOUT_MS = 60_000L
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(DOWNLOAD_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val progressListeners = mutableListOf<DownloadProgressListener>()

    interface DownloadProgressListener {
        fun onProgress(locale: String, dictType: String, progress: Float, status: DownloadStatus)
    }

    enum class DownloadStatus {
        PENDING, DOWNLOADING, COMPLETED, FAILED, CANCELLED
    }

    data class DownloadInfo(
        val locale: String,
        val dictType: String,
        val url: String,
        val experimental: Boolean,
        var progress: Float = 0f,
        var status: DownloadStatus = DownloadStatus.PENDING,
        var errorMessage: String? = null
    )

    private val activeDownloads = mutableMapOf<String, DownloadInfo>()

    fun addProgressListener(listener: DownloadProgressListener) {
        progressListeners.add(listener)
    }

    fun removeProgressListener(listener: DownloadProgressListener) {
        progressListeners.remove(listener)
    }

    private fun notifyProgress(info: DownloadInfo) {
        progressListeners.forEach { it.onProgress(info.locale, info.dictType, info.progress, info.status) }
    }

    /**
     * Downloads a dictionary for the given locale and type.
     * @param locale The locale string (e.g., "de", "fr", "ru")
     * @param dictType The dictionary type (e.g., "main", "emoji")
     * @param experimental Whether to use experimental dictionary
     */
    fun downloadDictionary(
        locale: String,
        dictType: String = "main",
        experimental: Boolean = false,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val key = "${dictType}_$locale"
        if (activeDownloads.containsKey(key)) {
            Log.d(TAG, "Download already in progress for $key")
            onComplete(false, "Already downloading")
            return
        }

        val suffix = if (experimental) Links.DICTIONARY_EXPERIMENTAL_SUFFIX else Links.DICTIONARY_NORMAL_SUFFIX
        val url = "${Links.DICTIONARY_URL}${Links.DICTIONARY_DOWNLOAD_SUFFIX}$suffix${dictType}_$locale.dict"

        val info = DownloadInfo(
            locale = locale,
            dictType = dictType,
            url = url,
            experimental = experimental,
            status = DownloadStatus.DOWNLOADING
        )
        activeDownloads[key] = info
        notifyProgress(info)

        downloadScope.launch {
            var success = false
            var errorMessage: String? = null
            var attempt = 0

            while (attempt < MAX_RETRIES && !success) {
                attempt++
                try {
                    success = downloadWithProgress(info).await()
                    if (success) {
                        // Broadcast that new dictionary is available
                        broadcastNewDictionary(context)
                        break
                    }
                } catch (e: Exception) {
                    errorMessage = e.message
                    Log.w(TAG, "Download attempt $attempt failed for $key: $errorMessage")
                    if (attempt < MAX_RETRIES) {
                        delay(RETRY_DELAY_MS * attempt)
                    }
                }
            }

            val finalInfo = info.copy(
                status = if (success) DownloadStatus.COMPLETED else DownloadStatus.FAILED,
                errorMessage = errorMessage
            )
            activeDownloads[key] = finalInfo
            notifyProgress(finalInfo)
            onComplete(success, errorMessage)
        }
    }

    private suspend fun downloadWithProgress(info: DownloadInfo): Deferred<Boolean> = CoroutineScope(Dispatchers.IO).async {
        val request = Request.Builder().url(info.url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            Log.e(TAG, "Download failed with code: ${response.code} for ${info.url}")
            return@async false
        }

        val body = response.body ?: return@async false
        val contentLength = body.contentLength()
        val inputStream = body.byteStream()

        val cacheDir = DictionaryInfoUtils.getCacheDirectoryForLocale(info.locale.constructLocale(), context)
            ?: return@async false
        val targetFile = File(cacheDir, "${info.dictType}.dict")
        targetFile.parentFile?.mkdirs()

        var downloaded = 0L
        val buffer = ByteArray(8192)
        FileOutputStream(targetFile).use { output ->
            while (true) {
                val read = inputStream.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                downloaded += read
                if (contentLength > 0) {
                    val progress = downloaded.toFloat() / contentLength
                    val updatedInfo = info.copy(progress = progress)
                    activeDownloads["${info.dictType}_${info.locale}"] = updatedInfo
                    notifyProgress(updatedInfo)
                }
            }
        }

        // Verify the downloaded dictionary
        val header = DictionaryInfoUtils.getDictionaryFileHeaderOrNull(targetFile)
        if (header == null) {
            targetFile.delete()
            Log.e(TAG, "Downloaded dictionary is invalid: ${targetFile.absolutePath}")
            return@async false
        }

        Log.d(TAG, "Successfully downloaded dictionary: ${targetFile.absolutePath}")
        true
    }

    private fun broadcastNewDictionary(context: Context) {
        val intent = android.content.Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION)
        context.sendBroadcast(intent)
    }

    fun cancelDownload(locale: String, dictType: String = "main") {
        val key = "${dictType}_$locale"
        activeDownloads[key]?.let { info ->
            activeDownloads[key] = info.copy(status = DownloadStatus.CANCELLED)
            notifyProgress(activeDownloads[key]!!)
        }
    }

    fun getDownloadInfo(locale: String, dictType: String = "main"): DownloadInfo? {
        return activeDownloads["${dictType}_$locale"]
    }

    fun isDownloading(locale: String, dictType: String = "main"): Boolean {
        val info = activeDownloads["${dictType}_$locale"]
        return info?.status == DownloadStatus.DOWNLOADING
    }

    fun shutdown() {
        downloadScope.cancel()
        client.dispatcher().executorService?.shutdown()
    }
}

// Extension to create Locale from string
fun String.constructLocale(): java.util.Locale {
    return if (contains("-")) {
        java.util.Locale.forLanguageTag(this)
    } else {
        val elements = split("_", limit = 3)
        val language = elements[0].lowercase()
        val region = elements.getOrNull(1)?.uppercase()
        if (elements.size == 1) {
            java.util.Locale(language)
        } else if (elements.size == 2) {
            if (region == "ZZ") java.util.Locale.forLanguageTag("$language-Latn")
            else java.util.Locale(language, region!!)
        } else {
            java.util.Locale(language, region!!, elements[2])
        }
    }
}