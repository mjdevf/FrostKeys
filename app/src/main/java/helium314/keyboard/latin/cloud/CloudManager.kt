// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cloud

import android.content.Context
import helium314.keyboard.latin.utils.prefs
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

object CloudManager {
    const val PREF_ENABLE_CLOUD_FEATURES = "pref_enable_cloud_features"
    const val PREF_TEST_CONNECTION = "pref_test_connection"
    const val PREF_GEMINI_API_KEY = "pref_gemini_api_key"
    const val PREF_KLIPY_API_KEY = "pref_klipy_api_key"
    const val PREF_CACHED_GEMINI_MODELS = "pref_cached_gemini_models"
    const val PREF_GEMINI_MODELS_LAST_FETCH = "pref_gemini_models_last_fetch"

    private const val CACHE_SIZE = 10 * 1024 * 1024L // 10 MB
    private const val CONNECTION_POOL_MAX_IDLE = 5
    private const val CONNECTION_POOL_KEEP_ALIVE_MS = 5 * 60 * 1000L // 5 minutes
    private const val CALL_TIMEOUT_MS = 30 * 1000L // 30 seconds

    private var clientInstance: OkHttpClient? = null
    private var clientCacheDir: java.io.File? = null

    val client: OkHttpClient
        get() {
            if (clientInstance == null) {
                synchronized(this) {
                    if (clientInstance == null) {
                        val cacheDir = clientCacheDir ?: return OkHttpClient.Builder()
                            .connectionPool(ConnectionPool(CONNECTION_POOL_MAX_IDLE, CONNECTION_POOL_KEEP_ALIVE_MS, TimeUnit.MILLISECONDS))
                            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .build()
                        clientInstance = OkHttpClient.Builder()
                            .cache(Cache(cacheDir, CACHE_SIZE))
                            .connectionPool(ConnectionPool(CONNECTION_POOL_MAX_IDLE, CONNECTION_POOL_KEEP_ALIVE_MS, TimeUnit.MILLISECONDS))
                            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .build()
                    }
                }
            }
            return clientInstance!!
        }

    fun init(appContext: Context) {
        clientCacheDir = appContext.applicationContext.cacheDir.resolve("okhttp_cache")
    }

    enum class CloudFeature {
        TEST_CONNECTION,
        AI_WRITING_TOOLS,
        KLIPY_MEDIA
    }

    fun isFeatureAllowed(context: Context, feature: CloudFeature): Boolean {
        return context.prefs().getBoolean(PREF_ENABLE_CLOUD_FEATURES, false)
    }

    fun getKlipyApiKey(context: Context): String {
        return context.prefs().getString(PREF_KLIPY_API_KEY, "") ?: ""
    }

    fun executeRequest(context: Context, feature: CloudFeature, request: Request): Response? {
        if (!isFeatureAllowed(context, feature)) {
            throw SecurityException("Gatekeeper intercepted and blocked request for $feature")
        }
        return client.newCall(request).execute()
    }
}