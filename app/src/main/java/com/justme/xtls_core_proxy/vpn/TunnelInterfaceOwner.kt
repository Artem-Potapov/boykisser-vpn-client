package com.justme.xtls_core_proxy.vpn

/**
 * The single authoritative owner of the session's TUN interface, if any. Exactly one handle is
 * bound to exactly one ownership role, so "is a tunnel present" and "is it still proxying" can
 * never disagree the way two parallel fields plus a stored kind tag let them.
 *
 * Purely a type: no Android, no close behavior, no lock, no lifecycle state. Production specializes
 * the handle to `ParcelFileDescriptor`; JVM tests use token handles. The handle-bearing variants are
 * ordinary `class` declarations so that handle identity — not structural equality — is what a
 * transition can assert on; only [None] is a `data object` because it carries no handle.
 */
internal sealed interface TunnelInterfaceOwner<out Handle> {
    data object None : TunnelInterfaceOwner<Nothing>
    class LiveProxy<Handle>(val handle: Handle) : TunnelInterfaceOwner<Handle>
    class UnreadRotationBridge<Handle>(val handle: Handle) : TunnelInterfaceOwner<Handle>
    class UnreadBlackhole<Handle>(val handle: Handle) : TunnelInterfaceOwner<Handle>
}
