package com.justme.xtls_core_proxy.subs

import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.config.Hysteria2ConfigCodec
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

data class ParsedConfig(
    val displayName: String,
    val config: String,
    val sanitizedDns: Boolean = false
)

data class ParseOutcome(val parsed: List<ParsedConfig>, val parseErrorCount: Int)

object SubscriptionBodyParser {

    private val BASE64_BODY_PATTERN = Regex("^[A-Za-z0-9+/=_\\-\\s]+$")
    private val BASE64_LINE_PATTERN = Regex("^[A-Za-z0-9+/=_\\-]+$")

    private fun containsSupportedShareLink(text: String): Boolean {
        return text.contains("vless://", ignoreCase = true) ||
            text.contains("hysteria2://", ignoreCase = true) ||
            text.contains("hy2://", ignoreCase = true)
    }

    fun parseBody(body: String): ParseOutcome {
        val effective = decodeOuterBase64IfApplicable(body.trim())
        val candidates = extractCandidates(effective)

        val parsed = mutableListOf<ParsedConfig>()
        var errors = 0
        val nameCounts = mutableMapOf<String, Int>()

        for ((index, candidate) in candidates.withIndex()) {
            val result = runCatching {
                val storage = ConfigBuilder.toProfileStorageConfig(candidate)
                val dirty = ConfigBuilder.dnsDiagnosis(storage) == ConfigBuilder.DnsStatus.DIRTY
                val finalConfig = ConfigBuilder.makeSecureDns(storage)
                ConfigBuilder.buildRuntimeConfig(finalConfig) // validate; throws if unusable
                finalConfig to dirty
            }.getOrNull()
            if (result == null) {
                errors++
                continue
            }
            val (finalConfig, dirty) = result
            val baseName = deriveDisplayName(candidate, index)
            val unique = uniquify(baseName, nameCounts)
            parsed += ParsedConfig(displayName = unique, config = finalConfig, sanitizedDns = dirty)
        }
        return ParseOutcome(parsed = parsed, parseErrorCount = errors)
    }

    /**
     * Splits a subscription body into individual candidate config strings.
     *
     * A whole-body JSON document — a single config object (`{…}`) or an array of them (`[…]`) — is
     * detected up front and kept intact. Without this, the newline split below shatters a
     * pretty-printed JSON config into per-line fragments that each fail to parse, so a valid
     * single-config subscription yields zero profiles. Everything else (vless:// lists, base64
     * blobs, one config per line) goes through the line-oriented path with per-line base64
     * unwrapping, exactly as before.
     */
    private fun extractCandidates(effective: String): List<String> {
        wholeJsonCandidates(effective)?.let { return it }
        return effective.split("\r\n", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .filter { it.contains("://") || it.contains("{") || looksLikeBase64Line(it) }
            .map { unwrapPerLineBase64(it) ?: it }
    }

    /**
     * Returns candidate config strings when [effective] is a single JSON document, or null to fall
     * back to line parsing. A malformed JSON document also returns null (fall back), preserving the
     * prior best-effort behavior rather than discarding the whole body.
     */
    private fun wholeJsonCandidates(effective: String): List<String>? {
        val trimmed = effective.trim()
        return when {
            trimmed.startsWith("[") -> runCatching {
                val arr = JSONArray(trimmed)
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.toString() }
            }.getOrNull()?.takeIf { it.isNotEmpty() }
            trimmed.startsWith("{") -> runCatching {
                JSONObject(trimmed) // validate; throws if malformed -> fall back to line parsing
                listOf(trimmed)
            }.getOrNull()
            else -> null
        }
    }

    private fun decodeOuterBase64IfApplicable(trimmed: String): String {
        if (trimmed.isEmpty()) return trimmed
        if (!BASE64_BODY_PATTERN.matches(trimmed)) return trimmed
        val decoded = decodeBase64Permissive(trimmed) ?: return trimmed
        return if (containsSupportedShareLink(decoded) || decoded.contains("{")) {
            decoded
        } else {
            trimmed
        }
    }

    private fun unwrapPerLineBase64(line: String): String? {
        if (line.length < 8) return null
        if (!BASE64_LINE_PATTERN.matches(line)) return null
        val decoded = decodeBase64Permissive(line) ?: return null
        if (!containsSupportedShareLink(decoded) && !decoded.contains("{")) return null
        return decoded.trim()
    }

    private fun looksLikeBase64Line(line: String): Boolean {
        if (line.length < 8) return false
        return BASE64_LINE_PATTERN.matches(line)
    }

    private fun decodeBase64Permissive(value: String): String? {
        val cleaned = value.replace("\\s".toRegex(), "")
        val padded = padBase64(cleaned)
        val candidates = listOf(
            { Base64.getDecoder().decode(padded) },
            { Base64.getUrlDecoder().decode(padded) },
            { Base64.getMimeDecoder().decode(padded) }
        )
        for (decoder in candidates) {
            val bytes = runCatching { decoder() }.getOrNull() ?: continue
            val text = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull() ?: continue
            if (text.isNotBlank()) return text
        }
        return null
    }

    private fun padBase64(value: String): String {
        val remainder = value.length % 4
        if (remainder == 0) return value
        return value + "=".repeat(4 - remainder)
    }

    private fun deriveDisplayName(candidate: String, index: Int): String {
        val trimmed = candidate.trim()
        return when {
            trimmed.startsWith("vless://", ignoreCase = true) -> deriveVlessDisplayName(trimmed, index)
            Hysteria2ConfigCodec.isHysteria2Uri(trimmed) -> deriveHysteria2DisplayName(trimmed, index)
            else -> deriveJsonDisplayName(trimmed, index)
        }
    }

    internal fun deriveVlessDisplayName(uri: String, index: Int): String {
        val fragmentRaw = uri.substringAfter('#', "").substringBefore('?').takeIf { it.isNotBlank() }
        val fragment = fragmentRaw?.let {
            runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        if (fragment != null) return fragment

        val parsed = runCatching { URI(uri.substringBefore('#')) }.getOrNull()
        val host = parsed?.host?.takeIf { it.isNotBlank() }
        val port = parsed?.port?.takeIf { it > 0 }
        return when {
            host != null && port != null -> "$host:$port"
            host != null -> host
            else -> "Config ${index + 1}"
        }
    }

    internal fun deriveHysteria2DisplayName(uri: String, index: Int): String {
        val fragmentRaw = uri.substringAfter('#', "").substringBefore('?').takeIf { it.isNotBlank() }
        val fragment = fragmentRaw?.let {
            runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        if (fragment != null) return fragment

        val profile = runCatching { Hysteria2ConfigCodec.parseUri(uri) }.getOrNull()
        return when {
            profile != null -> "${profile.host}:${profile.port}"
            else -> "Config ${index + 1}"
        }
    }

    internal fun deriveJsonDisplayName(rawJson: String, index: Int): String {
        return runCatching {
            val root = JSONObject(rawJson)

            // Prefer the human-facing label many providers ship at the top level (e.g.
            // "remarks": "🇵🇱Польша PL-W1"); it beats the generic outbound tag ("proxy").
            root.optString("remarks").takeIf { it.isNotBlank() }?.let { return@runCatching it }

            val outbounds = root.optJSONArray("outbounds")
            val first = outbounds?.optJSONObject(0)
            first?.optString("tag")?.takeIf { it.isNotBlank() }?.let { return@runCatching it }

            val ss = first?.optJSONObject("streamSettings")
            ss?.optJSONObject("tlsSettings")?.optString("serverName")?.takeIf { it.isNotBlank() }
                ?.let { return@runCatching it }
            ss?.optJSONObject("realitySettings")?.optString("serverName")?.takeIf { it.isNotBlank() }
                ?.let { return@runCatching it }

            val hysteriaSettings = first?.optJSONObject("settings")
            if (first?.optString("protocol").equals("hysteria", ignoreCase = true) &&
                hysteriaSettings?.optInt("version", -1) == 2
            ) {
                val address = hysteriaSettings.optString("address").takeIf { it.isNotBlank() }
                val port = hysteriaSettings.optInt("port", -1).takeIf { it > 0 }
                when {
                    address != null && port != null -> return@runCatching "$address:$port"
                    address != null -> return@runCatching address
                }
            }

            val vnext = first?.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0)
            val address = vnext?.optString("address")?.takeIf { it.isNotBlank() }
            val port = vnext?.optInt("port", -1)?.takeIf { it > 0 }
            when {
                address != null && port != null -> "$address:$port"
                address != null -> address
                else -> "Config ${index + 1}"
            }
        }.getOrElse { "Config ${index + 1}" }
    }

    private fun uniquify(base: String, counts: MutableMap<String, Int>): String {
        val key = base.lowercase()
        val seen = counts.getOrDefault(key, 0)
        counts[key] = seen + 1
        return if (seen == 0) base else "$base (${seen + 1})"
    }
}
