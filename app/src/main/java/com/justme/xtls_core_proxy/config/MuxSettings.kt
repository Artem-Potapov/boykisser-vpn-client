package com.justme.xtls_core_proxy.config

/**
 * Global Mux.Cool multiplexing overlay → the VLESS proxy `outbounds[].mux`. Applied only to a
 * VLESS outbound with a blank flow over a TCP-based non-xhttp transport (see [ConfigBuilder]
 * `applyMux`); skipped for XTLS Vision, xhttp (uses XMUX), kcp/quic, Hysteria2, and non-VLESS.
 */
data class MuxSettings(
    val enabled: Boolean,
    val concurrency: Int,
    val xudpConcurrency: Int,
    val quicHandling: QuicHandling,
) {
    companion object {
        val OFF = MuxSettings(
            enabled = false,
            concurrency = 8,
            xudpConcurrency = 16,
            quicHandling = QuicHandling.BLOCK,
        )
    }
}

/** UI-facing QUIC (UDP/443) policy → Mux.Cool `xudpProxyUDP443` wire value. */
enum class QuicHandling(val wire: String) {
    BLOCK("reject"),
    ALLOW("allow"),
    SKIP("skip"),
}
