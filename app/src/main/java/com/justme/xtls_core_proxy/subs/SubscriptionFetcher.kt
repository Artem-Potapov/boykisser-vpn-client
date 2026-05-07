package com.justme.xtls_core_proxy.subs

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

    suspend fun fetch(sub: Subscription, defaultUserAgent: String): FetchResult =
        withContext(Dispatchers.IO) {
            try {
                fetchBlocking(sub, defaultUserAgent)
            } catch (e: IOException) {
                FetchResult.Failure(e.message ?: "Network error")
            } catch (e: Exception) {
                FetchResult.Failure(e.message ?: e.javaClass.simpleName)
            }
        }

    private fun fetchBlocking(sub: Subscription, defaultUserAgent: String): FetchResult {
        val url = URL(sub.url)
        val scheme = url.protocol.lowercase()
        if (scheme != "http" && scheme != "https") {
            return FetchResult.Failure("Only http(s) URLs are supported")
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
                return FetchResult.Failure("HTTP $status ${connection.responseMessage.orEmpty()}".trim())
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
                        return FetchResult.Failure("Subscription body exceeds 2 MiB limit")
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
                val parsed = raw?.trim()?.toIntOrNull() ?: continue
                if (parsed >= 1) return parsed
            }
        }
        return null
    }

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
