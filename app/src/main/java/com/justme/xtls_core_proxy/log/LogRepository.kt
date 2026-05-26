package com.justme.xtls_core_proxy.log

import androidx.annotation.StringRes
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    PAUSED,
    ERROR
}

object LogRepository {
    private const val MAX_LINES = 500

    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

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
        val timestamp = timeFormatter.format(Date())
        val sanitized = sanitize(line)
        _logs.update { prev ->
            (prev + "[$timestamp] $sanitized").takeLast(MAX_LINES)
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
