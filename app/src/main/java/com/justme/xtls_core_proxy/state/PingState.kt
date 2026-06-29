package com.justme.xtls_core_proxy.state

/** Per-server ping-test state. Ephemeral; held in the ViewModel, keyed by profile id. */
sealed interface PingState {
    /** No test has run (or no result to show). */
    data object Idle : PingState

    /** A probe is in flight. */
    data object Testing : PingState

    /** Handshake + 204 completed in [latencyMs] milliseconds. */
    data class Success(val latencyMs: Long) : PingState

    /** The probe failed (dial error, non-204, timeout, or unbuildable config). */
    data object Unavailable : PingState

    companion object {
        fun fromResult(result: Result<Long>): PingState =
            result.fold(onSuccess = { Success(it) }, onFailure = { Unavailable })
    }
}
