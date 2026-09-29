package com.justme.xtls_core_proxy.vpn

import kotlinx.coroutines.CancellationException

/**
 * Every mutation of the session's [TunnelInterfaceOwner], expressed as a pure function of the owner
 * the caller currently holds. The service performs the I/O — building a TUN, calling `establish()`,
 * closing an fd — and this object only decides **which owner may be installed over which predecessor
 * and which handle the caller still owes a close to**.
 *
 * Deliberately free of lock, close, `Builder`, logging and Android types: the ordering constraints the
 * bridge depends on (release-then-`establish()`, adopt-instead-of-rebuild) are properties of the
 * *decision*, so they are testable here instead of only being visible in `XrayVpnService`.
 *
 * Every install rejects an owner it would silently overwrite. An occupied role means a leaked VPN
 * interface or a second interface the session never asked for, so the failure is a loud
 * `IllegalStateException` naming the state and the role rather than a best-effort replacement.
 */
internal object TunnelOwnerTransitions {

    /**
     * The outcome of clearing the way for a real `establish()`.
     *
     * [priorBridge] is the rotation bridge the session is **still holding** across the switch — not a
     * handle to close first. Rotation is the one path that keeps an interface for the whole gap; a
     * preparation that returned `None` here would drop the user onto the clear network between
     * tunnels, and one that reported the bridge for closing would recreate that same window.
     */
    data class PreparationResult<Handle>(
        val owner: TunnelInterfaceOwner<Handle>,
        val priorBridge: Handle?,
    )

    /**
     * The outcome of installing a live proxy.
     *
     * [priorBridgeToClose] is a bounded local cleanup handle, not a second owner: rotation has already
     * proven the replacement interface, so the proxy is installed first and the old bridge is closed
     * immediately afterwards. It is null when the session started from nothing (initial start,
     * revive).
     */
    data class LiveInstallResult<Handle>(
        val owner: TunnelInterfaceOwner.LiveProxy<Handle>,
        val priorBridgeToClose: Handle?,
    )

    /** The outcome of replacing a live proxy with an unread rotation bridge. */
    data class RotationBridgeInstallResult<Handle>(
        val owner: TunnelInterfaceOwner.UnreadRotationBridge<Handle>,
        val priorLiveProxyToClose: Handle,
    )

    /** The outcome of a terminal release: the owner is already [TunnelInterfaceOwner.None] and the
     * caller still has to close [handleToClose]. Nothing is closed here. */
    data class ReleaseResult<Handle>(
        val owner: TunnelInterfaceOwner<Handle>,
        val handleToClose: Handle?,
    )

    /**
     * Decides whether a real `establish()` may proceed, given the state that reserved the transition.
     *
     * Legal only from `STARTING`/`REVIVING` (owner `None` — no prior interface to bridge from) and
     * from `ROTATING` (owner is the rotation bridge, which stays authoritative for the whole switch).
     * `PAUSED`, `CONNECTED` and `STOPPED` are not establishment points at all: PAUSED means the
     * kill-switch requires no tunnel to exist, and a bridge still held under CONNECTED or STOPPED
     * means the caller reached the wrong branch of the lifecycle rather than that a tunnel is
     * missing.
     */
    fun <Handle> prepareRealEstablishment(
        owner: TunnelInterfaceOwner<Handle>,
        expectedState: SessionTunnelState,
    ): PreparationResult<Handle> = when (expectedState) {
        SessionTunnelState.STARTING, SessionTunnelState.REVIVING ->
            if (owner is TunnelInterfaceOwner.None) {
                PreparationResult(owner = TunnelInterfaceOwner.None, priorBridge = null)
            } else {
                rejectPreparation(owner, expectedState)
            }

        SessionTunnelState.ROTATING ->
            if (owner is TunnelInterfaceOwner.UnreadRotationBridge) {
                PreparationResult(owner = owner, priorBridge = owner.handle)
            } else {
                rejectPreparation(owner, expectedState)
            }

        SessionTunnelState.CONNECTED,
        SessionTunnelState.PAUSED,
        SessionTunnelState.STOPPED,
        -> rejectPreparation(owner, expectedState)
    }

    /**
     * Installs the established interface as the session's live proxy.
     *
     * Only `None` and the rotation bridge are acceptable predecessors: anything else means a live
     * tunnel or an unread blackhole is already installed, and replacing it would strand that fd.
     * Reached **after** a successful `establish()` only — a failed establish must release, never
     * install.
     */
    fun <Handle> installLiveProxy(
        owner: TunnelInterfaceOwner<Handle>,
        handle: Handle,
    ): LiveInstallResult<Handle> =
        if (owner is TunnelInterfaceOwner.UnreadRotationBridge) {
            LiveInstallResult(
                owner = TunnelInterfaceOwner.LiveProxy(handle),
                priorBridgeToClose = owner.handle,
            )
        } else if (owner is TunnelInterfaceOwner.None) {
            LiveInstallResult(
                owner = TunnelInterfaceOwner.LiveProxy(handle),
                priorBridgeToClose = null,
            )
        } else {
            rejectInstall(owner, "installLiveProxy", "an empty owner or a held rotation bridge")
        }

    /** Installs an unread owner, closing the newly established handle if installation fails. */
    fun <Handle, Owner : TunnelInterfaceOwner<Handle>> installUnreadOrClose(
        owner: TunnelInterfaceOwner<Handle>,
        handle: Handle,
        install: (TunnelInterfaceOwner<Handle>, Handle) -> Owner,
        close: (Handle) -> Unit,
    ): Owner = try {
        install(owner, handle)
    } catch (error: Throwable) {
        try {
            close(handle)
        } catch (_: Throwable) {
            // Preserve the ownership failure; descriptor cleanup is best-effort.
        }
        throw error
    }

    fun runBestEffortDiagnostic(diagnostic: () -> Unit) {
        try {
            diagnostic()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Diagnostics are not lifecycle operations.
        }
    }

    fun <Handle> replaceLiveProxyWithRotationBridge(
        owner: TunnelInterfaceOwner<Handle>,
        handle: Handle,
    ): RotationBridgeInstallResult<Handle> =
        if (owner is TunnelInterfaceOwner.LiveProxy) {
            RotationBridgeInstallResult(
                owner = TunnelInterfaceOwner.UnreadRotationBridge(handle),
                priorLiveProxyToClose = owner.handle,
            )
        } else {
            rejectInstall(owner, "replaceLiveProxyWithRotationBridge", "a live proxy")
        }

    /** Opens the rotation bridge over a gap. Only legal from `None`: rotation is the one path that
     * may hold exactly one interface while it builds its replacement. */
    fun <Handle> installRotationBridge(
        owner: TunnelInterfaceOwner<Handle>,
        handle: Handle,
    ): TunnelInterfaceOwner.UnreadRotationBridge<Handle> =
        if (owner is TunnelInterfaceOwner.None) {
            TunnelInterfaceOwner.UnreadRotationBridge(handle)
        } else {
            rejectInstall(owner, "installRotationBridge", "an empty owner")
        }

    /** Builds the fail-closed drop-only interface. Only legal from `None`; a give-up that already
     * holds a bridge **adopts** it via [adoptBridgeAsBlackhole] rather than building a second one. */
    fun <Handle> installBlackhole(
        owner: TunnelInterfaceOwner<Handle>,
        handle: Handle,
    ): TunnelInterfaceOwner.UnreadBlackhole<Handle> =
        if (owner is TunnelInterfaceOwner.None) {
            TunnelInterfaceOwner.UnreadBlackhole(handle)
        } else {
            rejectInstall(owner, "installBlackhole", "an empty owner")
        }

    /**
     * Re-roles a held rotation bridge as the blackhole a give-up wanted, keeping the same handle.
     *
     * The bridge already *is* the interface the give-up asks for, so adopting it is the only way to
     * answer without leaking the bridge fd for the rest of the session.
     */
    fun <Handle> adoptBridgeAsBlackhole(
        owner: TunnelInterfaceOwner<Handle>,
    ): TunnelInterfaceOwner.UnreadBlackhole<Handle> =
        if (owner is TunnelInterfaceOwner.UnreadRotationBridge) {
            TunnelInterfaceOwner.UnreadBlackhole(owner.handle)
        } else {
            rejectInstall(owner, "adoptBridgeAsBlackhole", "a held rotation bridge")
        }

    /**
     * Ends tunnel ownership. The handle comes back for the caller to close, so no transition in this
     * object is ever the thing that closes an interface.
     */
    fun <Handle> release(
        owner: TunnelInterfaceOwner<Handle>,
    ): ReleaseResult<Handle> {
        val handleToClose: Handle? = when (owner) {
            is TunnelInterfaceOwner.None -> null
            is TunnelInterfaceOwner.LiveProxy -> owner.handle
            is TunnelInterfaceOwner.UnreadRotationBridge -> owner.handle
            is TunnelInterfaceOwner.UnreadBlackhole -> owner.handle
        }
        return ReleaseResult(owner = TunnelInterfaceOwner.None, handleToClose = handleToClose)
    }

    private fun rejectPreparation(
        owner: TunnelInterfaceOwner<*>,
        expectedState: SessionTunnelState,
    ): Nothing = throw IllegalStateException(
        "prepareRealEstablishment is not a transition out of tunnel state ${expectedState.name} " +
            "with tunnel owner ${owner.toRole().name}",
    )

    private fun rejectInstall(
        owner: TunnelInterfaceOwner<*>,
        action: String,
        required: String,
    ): Nothing = throw IllegalStateException(
        "$action requires $required, but the tunnel owner is ${owner.toRole().name}",
    )
}
