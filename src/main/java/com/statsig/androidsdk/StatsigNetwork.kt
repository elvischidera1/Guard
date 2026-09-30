package com.statsig.androidsdk

import android.util.Log
import com.statsig.androidsdk.HttpUtils.Companion.RETRY_CODES
import com.statsig.androidsdk.HttpUtils.Companion.STATSIG_EVENT_COUNT
import com.statsig.androidsdk.HttpUtils.Companion.STATSIG_STABLE_ID_HEADER_KEY
import com.statsig.androidsdk.sql.StatsigDb
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

private const val LOG_EVENT_ATTEMPTS = 2
private const val INITIALIZE_RETRY_BACKOFF = 100L
private const val INITIALIZE_RETRY_BACKOFF_MULTIPLIER = 5
private const val MAX_LOG_RETRIES = 3

/**
 * HTTP for the SDK. Request bodies, which host to use, whether to compress and which batches to
 * keep for later all come from SQL; this class moves bytes and applies the retry schedule.
 */
internal class StatsigNetwork(
    private val db: StatsigDb,
    private val sdkKey: String,
    private val options: StatsigOptions,
    private val connectivity: StatsigNetworkConnectivityListener,
    private val scope: CoroutineScope,
    private val diagnostics: Diagnostics
) {
    private companion object {
        private const val TAG = "statsig::StatsigNetwork"
    }

    private val dispatchers = CoroutineDispatcherProvider()
    private val gzip = GZipRequestInterceptor()

    /** An initialize response for [user]/[scopedKey], or why there is none. */
    class InitializeResult(
        val payload: String?,
        val failure: InitializeResponse.FailedInitializeResponse?,
        val user: String,
        val scopedKey: String
    )

    private class HttpResult(val code: Int, val body: String?)

    suspend fun initialize(
        context: ContextType?,
        metadata: StatsigMetadata,
        hash: HashAlgorithm,
        poll: Boolean = false
    ): InitializeResult {
        val request = db.one(
            "initialize_request",
            mapOf("metadata" to toJson(metadata), "hash" to hash.value, "poll" to poll)
        )!!
        val body = request.string("body")!!
        val user = request.string("user")!!
        val scopedKey = request.string("scoped_key")!!
        if (poll || options.initTimeoutMs == 0L) {
            return initializeWithRetry(
                body,
                user,
                scopedKey,
                context,
                metadata,
                null,
                if (poll) 0 else options.initRetryLimit
            )
        }
        return withTimeout(options.initTimeoutMs) {
            scope.async(dispatchers.io) {
                initializeWithRetry(
                    body,
                    user,
                    scopedKey,
                    context,
                    metadata,
                    options.initTimeoutMs.toInt(),
                    options.initRetryLimit
                )
            }.await()
        }
    }

    private suspend fun initializeWithRetry(
        body: String,
        user: String,
        scopedKey: String,
        context: ContextType?,
        metadata: StatsigMetadata,
        timeoutMs: Int?,
        retryLimit: Int
    ): InitializeResult {
        var attempt = 0
        var backoff = INITIALIZE_RETRY_BACKOFF
        while (true) {
            val result = try {
                val response = post(
                    UrlConfig(Endpoint.Initialize, options.api, options.initializeFallbackUrls),
                    body,
                    attempt + 1,
                    context,
                    timeoutMs,
                    stableID = metadata.stableID
                )
                when (response.code) {
                    204 -> InitializeResult("""{"has_updates": false}""", null, user, scopedKey)
                    in 200..299 -> InitializeResult(response.body, null, user, scopedKey)
                    else -> InitializeResult(
                        null,
                        InitializeResponse.FailedInitializeResponse(
                            InitializeFailReason.NetworkError,
                            null,
                            response.code
                        ),
                        user,
                        scopedKey
                    )
                }
            } catch (e: Exception) {
                // cancellation (shutdown, withTimeout) must propagate, not become a failed response
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (context != null) {
                    runCatching {
                        diagnostics.markEnd(
                            KeyType.INITIALIZE,
                            context,
                            false,
                            StepType.NETWORK_REQUEST,
                            attempt = 1,
                            error = Marker.ErrorMessage(
                                e.message.toString(),
                                e.javaClass.name,
                                e.javaClass.name
                            ),
                            hasNetwork = connectivity.isNetworkAvailable()
                        )
                    }
                }
                val reason = when (e) {
                    is SocketTimeoutException,
                    is ConnectException -> InitializeFailReason.NetworkTimeout
                    is TimeoutCancellationException -> InitializeFailReason.CoroutineTimeout
                    is IOException -> InitializeFailReason.NetworkError
                    else -> InitializeFailReason.InternalError
                }
                InitializeResult(
                    null,
                    InitializeResponse.FailedInitializeResponse(reason, e),
                    user,
                    scopedKey
                )
            }
            val code = result.failure?.statusCode ?: 0
            if (result.failure == null || !(code == 0 || RETRY_CODES.contains(code)) ||
                attempt >= retryLimit
            ) {
                return result
            }
            attempt++
            delay(backoff)
            backoff *= INITIALIZE_RETRY_BACKOFF_MULTIPLIER
        }
    }

    /** Sends one log_event batch; undeliverable batches are stored for a later retry. */
    suspend fun postLogs(
        api: String,
        body: String,
        eventCount: String?,
        metadata: StatsigMetadata,
        retryCount: Int = 0,
        createdAt: Long = System.currentTimeMillis()
    ) {
        var backoff = 100L
        var statusCode: Int? = null
        fun save() = db.run(
            "failed_log_save",
            mapOf(
                "sdk_key" to sdkKey,
                "created_at" to createdAt,
                "body" to body,
                "retry_count" to retryCount + 1,
                "event_count" to eventCount
            )
        )
        try {
            for (attempt in 1..LOG_EVENT_ATTEMPTS) {
                val response = post(
                    UrlConfig(Endpoint.Rgstr, api, options.logEventFallbackUrls),
                    body,
                    attempt,
                    null,
                    null,
                    eventCount = eventCount
                )
                statusCode = response.code
                if (response.code in 200..299) return
                if (!RETRY_CODES.contains(response.code)) {
                    if (retryCount < MAX_LOG_RETRIES) save()
                    return
                }
                delay(backoff)
                backoff *= 5
            }
        } catch (e: Exception) {
            if (retryCount < MAX_LOG_RETRIES - 1) {
                Log.e(TAG, "Error posting logs, saving for retry...", e)
                save()
            } else {
                Log.e(TAG, "Logging attempt exceeded retries")
                reportLogEventFailure(statusCode, eventCount, retryCount, createdAt, e, metadata)
            }
        }
    }

    suspend fun retryFailedLogs(api: String, metadata: StatsigMetadata) {
        if (options.disableLogEventRetries) return
        for (row in db.run("failed_logs_take", mapOf("sdk_key" to sdkKey))) {
            postLogs(
                api,
                row.string("body")!!,
                row.string("event_count"),
                metadata,
                row.long("retry_count")!!.toInt(),
                row.long("created_at")!!
            )
        }
    }

    private suspend fun post(
        urlConfig: UrlConfig,
        body: String,
        attempt: Int,
        context: ContextType?,
        timeoutMs: Int?,
        eventCount: String? = null,
        stableID: String? = null
    ): HttpResult = withContext(dispatchers.io) {
        val endpoint = urlConfig.endpoint.value
        val fallbackUrls = urlConfig.userFallbackUrls?.let { toJson(it) }
        val fallback = db.one(
            "fallback_url",
            mapOf(
                "endpoint" to endpoint,
                "fallback_urls" to fallbackUrls,
                "has_custom_url" to (urlConfig.customUrl != null)
            )
        )?.string("url")
        val url = fallback ?: urlConfig.getUrl()
        var error: String? = null
        val start = System.nanoTime()
        var call: Call? = null
        try {
            val builder = HttpUtils.getHttpClient().newBuilder()
            timeoutMs?.let { builder.callTimeout(it.milliseconds.toJavaDuration()) }
            val compress = urlConfig.endpoint == Endpoint.Rgstr && db.one(
                "should_compress",
                mapOf(
                    "disabled" to options.disableLoggingCompression,
                    "url" to url,
                    "default_api" to DEFAULT_EVENT_API,
                    "custom_url" to urlConfig.customUrl,
                    "fallback_urls" to fallbackUrls
                )
            )?.bool("compress") == true
            if (compress) builder.addInterceptor(gzip)
            val request = Request.Builder().url(
                url
            ).post(body.toJsonRequestBody()).addStatsigHeaders(sdkKey)
            eventCount?.let { request.addHeader(STATSIG_EVENT_COUNT, it) }
            stableID?.let { request.addHeader(STATSIG_STABLE_ID_HEADER_KEY, it) }
            context?.let {
                diagnostics.markStart(KeyType.INITIALIZE, it, StepType.NETWORK_REQUEST, attempt)
            }
            call = builder.build().newCall(request.build())
            call.execute().use { response ->
                val text = response.body?.string()
                if (response.code >= 400) error = text
                context?.let {
                    diagnostics.markEnd(
                        KeyType.INITIALIZE, it, response.code in 200..299, StepType.NETWORK_REQUEST,
                        attempt = attempt, sdkRegion = response.header("x-statsig-region"),
                        statusCode = response.code,
                        error = if (response.code >=
                            400
                        ) {
                            Marker.ErrorMessage(text, response.code.toString(), null)
                        } else {
                            null
                        },
                        hasNetwork = connectivity.isNetworkAvailable()
                    )
                }
                if (response.code in
                    200..299
                ) {
                    db.run("fallback_url_worked", mapOf("endpoint" to endpoint))
                }
                HttpResult(response.code, text)
            }
        } catch (e: Exception) {
            error = e.message
            throw e
        } finally {
            call?.cancel()
            val timedOut = timeoutMs != null && (System.nanoTime() - start) / 1_000_000 > timeoutMs
            scope.launch(dispatchers.io) { tryFallbackUrl(urlConfig, error, timedOut) }
        }
    }

    /** After a domain failure, look for another host (DNS TXT records or the app's list). */
    private suspend fun tryFallbackUrl(urlConfig: UrlConfig, error: String?, timedOut: Boolean) {
        try {
            if (!isDomainFailure(error, timedOut, connectivity.isNetworkAvailable())) return
            val endpoint = urlConfig.endpoint.value
            val candidates = if (urlConfig.customUrl == null &&
                urlConfig.userFallbackUrls == null
            ) {
                if (db.one("dns_query_allowed", mapOf("endpoint" to endpoint))?.bool("allowed") !=
                    true
                ) {
                    return
                }
                db.one(
                    "fallback_url_candidates",
                    mapOf(
                        "records" to toJson(fetchTxtRecords()),
                        "dns_key" to urlConfig.endpointDnsKey,
                        "path" to extractPathFromUrl(urlConfig.defaultUrl)
                    )
                )?.string("urls")
            } else {
                urlConfig.userFallbackUrls?.let { toJson(it) }
            }
            if (db.one(
                    "fallback_url_pick",
                    mapOf("endpoint" to endpoint, "candidates" to candidates)
                )?.bool("updated") ==
                true
            ) {
                Log.i(TAG, "Updated fallback URL")
            }
        } catch (_: Exception) {
            // best effort
        }
    }

    private fun reportLogEventFailure(
        statusCode: Int?,
        eventCount: String?,
        retryCount: Int,
        createdAt: Long,
        exception: Exception,
        metadata: StatsigMetadata
    ) {
        val body = mapOf(
            "exception" to (exception.javaClass.canonicalName ?: exception.javaClass.name),
            "statsigMetadata" to metadata,
            "tag" to "statsig::log_event_failed",
            "statusCode" to statusCode?.toString(),
            "eventCount" to eventCount,
            "offlineRetries" to retryCount,
            "eventTimestamp" to createdAt
        )
        val request = Request.Builder()
            .url(UrlConfig(Endpoint.SdkException, options.sdkErrorAPI).getUrl())
            .post(toJson(body)!!.toJsonRequestBody())
            .addStatsigHeaders(sdkKey)
        eventCount?.let { request.addHeader(STATSIG_EVENT_COUNT, it) }
        HttpUtils.getHttpClient().newCall(request.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) = response.close()
        })
    }
}

fun isDomainFailure(errorMsg: String?, timedOut: Boolean, hasNetwork: Boolean): Boolean =
    hasNetwork && (timedOut || errorMsg != null)

fun extractPathFromUrl(urlString: String): String? = try {
    java.net.URL(urlString).path
} catch (e: Exception) {
    null
}
