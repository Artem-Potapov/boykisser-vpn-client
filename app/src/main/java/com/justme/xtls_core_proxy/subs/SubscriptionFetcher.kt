package com.justme.xtls_core_proxy.subs

import android.annotation.SuppressLint
import android.content.Context
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.db.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

sealed class FetchResult {
    data class Success(val body: String, val intervalHoursFromHeader: Int?) : FetchResult()
    data class Failure(val message: String) : FetchResult()
}

object SubscriptionFetcher {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_BODY_BYTES = 2L * 1024L * 1024L
    private const val READ_CHUNK_BYTES = 16 * 1024

    suspend fun fetch(context: Context, sub: Subscription, defaultUserAgent: String): FetchResult =
        withContext(Dispatchers.IO) {
            try {
                fetchBlocking(context, sub, defaultUserAgent)
            } catch (e: IOException) {
                FetchResult.Failure(
                    context.getString(
                        R.string.subs_error_network_prefix,
                        e.message ?: e.javaClass.simpleName,
                    )
                )
            } catch (e: Exception) {
                FetchResult.Failure(
                    context.getString(
                        R.string.subs_error_fetch_failed_prefix,
                        e.message ?: e.javaClass.simpleName,
                    )
                )
            }
        }

    private fun fetchBlocking(context: Context, sub: Subscription, defaultUserAgent: String): FetchResult {
        val url = URL(sub.url)
        val scheme = url.protocol.lowercase()
        if (scheme != "http" && scheme != "https") {
            return FetchResult.Failure(context.getString(R.string.subs_error_url_scheme))
        }

        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                sub.userAgentOverride?.takeIf { it.isNotBlank() } ?: defaultUserAgent
            )
            connection.setRequestProperty("Accept", "*/*")

            if (connection is HttpsURLConnection && sub.allowInsecureTls) {
                applyInsecureTls(connection)
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                return FetchResult.Failure(
                    context.getString(
                        R.string.subs_error_http_prefix,
                        status,
                        connection.responseMessage.orEmpty(),
                    )
                )
            }

            val intervalHours = parseIntervalHeader(connection.headerFields)

            val bodyBytes = connection.inputStream.use { stream ->
                val buffer = ByteArray(READ_CHUNK_BYTES)
                val sink = ByteArrayOutputStream()
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > MAX_BODY_BYTES) {
                        return FetchResult.Failure(context.getString(R.string.subs_error_body_too_large))
                    }
                    sink.write(buffer, 0, read)
                }
                sink.toByteArray()
            }

            val body = String(bodyBytes, StandardCharsets.UTF_8)
            return FetchResult.Success(body = body, intervalHoursFromHeader = intervalHours)
        } finally {
            connection.disconnect()
        }
    }

    @Suppress("SENSELESS_COMPARISON")
    fun parseIntervalHeader(headers: Map<String, List<String>>): Int? {
        for ((rawKey, values) in headers) {
            // HttpURLConnection.getHeaderFields() can put the status line under a null key.
            if (rawKey == null) continue
            if (!rawKey.equals("profile-update-interval", ignoreCase = true)) continue
            for (raw in values) {
                val parsed = raw.trim().toIntOrNull() ?: continue
                if (parsed >= 1) return parsed
            }
        }
        return null
    }

    /**
     * Installs a trust-all [X509TrustManager] and a permissive hostname verifier on a single
     * connection instance. This intentionally disables TLS validation and is gated entirely by the
     * per-subscription [Subscription.allowInsecureTls] flag (default `false`, explicit user opt-in in
     * the subscription editor's Advanced tab). The sole caller checks that flag before invoking this;
     * there is no global/default code path. Crucially, the factory and verifier are set on the passed
     * [connection] only — we never touch [HttpsURLConnection.setDefaultSSLSocketFactory] / the default
     * hostname verifier, so other connections are unaffected.
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun applyInsecureTls(connection: HttpsURLConnection) {
        val trustAll = arrayOf<X509TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val context = SSLContext.getInstance("TLS")
        context.init(null, trustAll, SecureRandom())
        connection.sslSocketFactory = context.socketFactory as SSLSocketFactory
        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
    }
}
