package com.justme.xtls_core_proxy.state

import android.content.Context

/** Global ping-probe preferences, read at probe time (no session-capture rail). */
data class PingPreferences(
    val targetUrl: String,
    val timeoutMs: Long,
    val concurrency: Int,
    val autoOnOpen: Boolean,
) {
    companion object {
        const val TIMEOUT_MIN = 1_000L
        const val TIMEOUT_MAX = 30_000L
        const val CONCURRENCY_MIN = 1
        const val CONCURRENCY_MAX = 5

        val DEFAULT = PingPreferences(
            targetUrl = PingTester.PING_TEST_TARGET,
            timeoutMs = PingTester.PING_TIMEOUT_MS,
            concurrency = PingTester.DEFAULT_PING_CONCURRENCY,
            autoOnOpen = false,
        )

        private const val PREFS = "xray_prefs"
        private const val KEY_TARGET = "ping_target_url"
        private const val KEY_TIMEOUT = "ping_timeout_ms"
        private const val KEY_CONCURRENCY = "ping_concurrency"
        private const val KEY_AUTO = "ping_auto_on_open"

        fun isValidTarget(url: String): Boolean {
            val u = url.trim()
            return u.startsWith("http://", ignoreCase = true) && u.length > "http://".length
        }

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun load(context: Context): PingPreferences {
            val p = prefs(context)
            val target = p.getString(KEY_TARGET, null)?.takeIf { isValidTarget(it) } ?: DEFAULT.targetUrl
            return PingPreferences(
                targetUrl = target,
                timeoutMs = p.getLong(KEY_TIMEOUT, DEFAULT.timeoutMs).coerceIn(TIMEOUT_MIN, TIMEOUT_MAX),
                concurrency = p.getInt(KEY_CONCURRENCY, DEFAULT.concurrency)
                    .coerceIn(CONCURRENCY_MIN, CONCURRENCY_MAX),
                autoOnOpen = p.getBoolean(KEY_AUTO, DEFAULT.autoOnOpen),
            )
        }

        fun save(context: Context, s: PingPreferences) {
            prefs(context).edit().apply {
                putString(KEY_TARGET, s.targetUrl.trim())
                putLong(KEY_TIMEOUT, s.timeoutMs.coerceIn(TIMEOUT_MIN, TIMEOUT_MAX))
                putInt(KEY_CONCURRENCY, s.concurrency.coerceIn(CONCURRENCY_MIN, CONCURRENCY_MAX))
                putBoolean(KEY_AUTO, s.autoOnOpen)
                apply()
            }
        }
    }
}
