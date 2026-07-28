package com.justme.xtls_core_proxy.log

import androidx.annotation.StringRes
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val logTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)

internal fun formatLogTimestamp(
    instant: Instant,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = instant.atZone(zoneId).toLocalTime().format(logTimestampFormatter)

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    PAUSED,

    /**
     * Auto-failover found no server it could reach, so the tunnel is deliberately holding traffic
     * instead of releasing it to the clear network. Like [PAUSED] — and unlike [ERROR] — the
     * service is still running and still owns a TUN, so it is stoppable and must be presented as a
     * live state rather than a dead one.
     *
     * The constant is technical because only developers read it; every user-facing string mapped
     * from it is deliberately plain and non-alarming.
     */
    BLACKHOLED,
    ERROR
}

object LogRepository {
    @Volatile
    var maxLines: Int = 5000
        private set

    /** Update the cap and immediately trim the current buffer. Live (pure UI concern). */
    fun setMaxLines(n: Int) {
        val capped = n.coerceIn(100, 50_000)
        maxLines = capped
        _logs.update { it.takeLast(capped) }
    }

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val _connectionState = MutableStateFlow(VpnConnectionState.DISCONNECTED)
    val connectionState: StateFlow<VpnConnectionState> = _connectionState

    private val _errorEvents = MutableSharedFlow<Int>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Hot flow of localized-error string-resource IDs emitted whenever the
     * VPN service hits a user-facing error path. Consumers (typically
     * VpnViewModel) should resolve the id against an Application context.
     *
     * replay = 1: a freshly-collected consumer sees the most recent error,
     * matching the StateFlow-like semantics of connectionState. The error
     * stays "current" until the VM clears it on the next CONNECTING
     * transition. Same error fired twice triggers two collector emissions
     * (DROP_OLDEST applies only when the consumer is slow, not to the
     * replay cache).
     *
     * Contract: any code path that calls setConnectionState(ERROR) and has
     * a user-facing reason should also call emitError(...) with the
     * matching @StringRes. Not enforced by the API shape (errors can fire
     * without ERROR state, and ERROR state can be entered during shutdown
     * teardown without a new emission) — enforced by audit.
     */
    val errorEvents: SharedFlow<Int> = _errorEvents.asSharedFlow()

    fun emitError(@StringRes resId: Int) {
        _errorEvents.tryEmit(resId)
    }

    fun append(line: String) {
        val timestamp = formatLogTimestamp(Instant.now())
        val sanitized = sanitize(line)
        _logs.update { prev ->
            (prev + "[$timestamp] $sanitized").takeLast(maxLines)
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun setConnectionState(newState: VpnConnectionState) {
        _connectionState.value = newState
    }

    private fun sanitize(raw: String): String {
        return raw
            .replace(Regex("""([0-9a-fA-F]{8}-[0-9a-fA-F-]{27})"""), "<redacted-uuid>")
            .replace(Regex("""("publicKey"\s*:\s*")[^"]+(")"""), "$1<redacted>$2")
            .replace(Regex("""("shortId"\s*:\s*")[^"]+(")"""), "$1<redacted>$2")
    }
}
