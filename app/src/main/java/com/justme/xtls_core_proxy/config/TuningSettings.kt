package com.justme.xtls_core_proxy.config

/**
 * Global connection-tuning overlays forced onto the runtime config by
 * [ConfigBuilder.buildRuntimeConfig]. Mirrors [LogSettings]: captured once per session in
 * XrayVpnService and threaded in, never re-read mid-session. The ping path passes [NONE].
 */
data class TuningSettings(
    val fragmentation: FragmentationSettings = FragmentationSettings.DISABLED,
    val mux: MuxSettings = MuxSettings.OFF,
) {
    companion object {
        val NONE = TuningSettings()
    }
}

/**
 * TLS-ClientHello / early-packet fragmentation → the proxy outbound's
 * `streamSettings.sockopt.fragment`. Only applied to TCP-based outbounds (see
 * [ConfigBuilder] `applyFragmentation`); silently skipped for QUIC/kcp/Hysteria2.
 * The three value fields are Xray's raw `fragment` strings (e.g. packets `tlshello`, length
 * `100-200`, interval `10-20`).
 */
data class FragmentationSettings(
    val enabled: Boolean,
    val packets: String,
    val length: String,
    val interval: String,
) {
    companion object {
        val DISABLED = FragmentationSettings(
            enabled = false,
            packets = "tlshello",
            length = "100-200",
            interval = "10-20",
        )
    }
}
