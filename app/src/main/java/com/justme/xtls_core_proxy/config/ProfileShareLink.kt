package com.justme.xtls_core_proxy.config

// i18n: no user-visible strings

import org.json.JSONObject

/**
 * Reconstructs a shareable link from a stored (normalized-JSON) profile config.
 *
 * Profiles never retain the original share link — [ConfigBuilder.toProfileStorageConfig] converts
 * every vless:// / hy2:// link to JSON at add time — so a link must be rebuilt from the JSON. Only
 * a single-outbound vless or hysteria2 config has a share-link form; anything else yields null.
 */
object ProfileShareLink {
    fun fromStoredConfig(config: String): String? = runCatching {
        val outbounds = JSONObject(config.trim()).optJSONArray("outbounds")
        var link: String? = null
        if (outbounds != null) {
            for (i in 0 until outbounds.length()) {
                val protocol = outbounds.optJSONObject(i)?.optString("protocol")?.lowercase()
                if (protocol == "vless") {
                    link = ProfileConfigCodec.toVlessUri(
                        ProfileConfigCodec.parseVlessProfileFromJson(config)
                    )
                    break
                }
                if (protocol == "hysteria") {
                    link = Hysteria2ConfigCodec.toShareLink(
                        Hysteria2ConfigCodec.parseProfileFromJson(config)
                    )
                    break
                }
            }
        }
        link
    }.getOrNull()
}
