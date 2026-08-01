package com.justme.xtls_core_proxy.failover

import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.state.PingState

/**
 * Lowest-latency candidate with a successful probe, or null if none succeeded. Pure so the
 * selection rule is testable without a coordinator or a device.
 */
internal fun pickFastest(states: Map<Long, PingState>, candidates: List<Profile>): Profile? =
    candidates
        .mapNotNull { profile ->
            (states[profile.id] as? PingState.Success)?.let { profile to it.latencyMs }
        }
        .minByOrNull { it.second }
        ?.first

/**
 * Resets any of [ids] still on [PingState.Testing] back to [PingState.Idle]; every other entry
 * (resolved ids, and ids outside [ids] entirely) passes through unchanged.
 *
 * `PingCoordinator.runGroup` rethrows a caller-cancellation `CancellationException` from inside its
 * per-id `try/finally` BEFORE calling `onUpdate` for that id, so cancelling a connect-fastest run
 * mid-flight leaves the still-in-flight ids with no terminal `PingState` of their own — without this
 * cleanup the row would spin on `Testing` forever. Scoped to [ids] so it never clobbers an unrelated,
 * concurrently-running group ping test's `Testing` rows.
 */
internal fun clearStaleTesting(states: Map<Long, PingState>, ids: Set<Long>): Map<Long, PingState> =
    states.mapValues { (id, state) ->
        if (id in ids && state == PingState.Testing) PingState.Idle else state
    }
