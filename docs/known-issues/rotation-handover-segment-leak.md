# Rotation handover leaks one segment of an in-flight connection

**Status: CONFIRMED ON DEVICE, not fixable from the app.** Do not re-investigate from scratch — this page is the investigation record. The app cannot close the window from user space. The only normal-install mitigation is a system setting the user has to turn on themselves, and it must be encouraged, not forced.

Known-issue record of a real packet leaving the underlying interface while auto-failover swaps tunnels. It is confined to that swap. It does not change the fail-closed design of the rotation bridge; it records the one case the bridge cannot see.

Investigated 2026-10-11 against the diagnostic build `b680821` ("chore(vpn): add temporary failover capture diagnostics") on branch `auto-failover-engine-migration-development`.

## Verdict (read this first)

The leak is real. During `VpnService.Builder.establish()` in `bringUpTunnel`, Android replaces the current VPN interface, and the kernel emits the last queued segment of a socket already bound to the tunnel address out the underlying interface. On the emulator that segment was a FIN of a connection that was already open. On a physical device the same window let a request complete and return an HTTP response.

No new connection was routed out during the captured rotation. The default route stayed covered for fresh traffic. The service already establishes the replacement tunnel before it closes the bridge, so there is no ordering change in `XrayVpnService` that reaches this packet.

## Answers

**Is the leak real?** Yes. A physical device got an HTTP response back from `1.0.0.1:80` for a request that escaped at the handover. The emulator leaked the segments themselves: a SYN from its own underlay address `10.0.2.15`, and later a FIN from the tunnel address `10.7.0.1` with the guest's own MAC `08:00:27:0e:38:b3` as the Ethernet source. The emulator gateway `10.0.2.2` ARP-resolved `10.7.0.1` and delivered the RST back. The guest transmitted the packet. Nothing on the host injected it.

**At what stage?** The `CANDIDATE_ESTABLISH` phase, inside `builder.establish()`. In the traced rotation the call ran from `1791680517.249` to `1791680517.268`, and the FIN is timestamped at `.267`. That is one millisecond before the call returns, which is inside the uncertainty of the timestamp and the packet's own queueing time, so it cannot be pinned finer than "during this call". It is not in the 575 ms bridge gap before the call (`OLD_FD_CLOSE_END` at `.674`, `CANDIDATE_ESTABLISH_BEGIN` at `.249`), and it is not after `CONNECTED_COMMIT` at `.304`.

**How wide is the window?** The `establish()` call itself, about 19 ms in this trace. One rotation spilled one segment, and that segment was the tail of a connection already in flight. A filter for a new connection (`SYN` set, `ACK` clear, source `10.7.0.1`, the whole handover from `1791680516.6` to `1791680517.4`) matched nothing. The earlier `10.0.2.15` SYN was a separate event and did not recur.

**At what level?** The kernel's, while Android replaces the VPN interface. `establish()` is what installs the new route and deletes the old interface. The socket was bound to `10.7.0.1` before the swap, and the kernel sent the segment it still had queued. `VpnService.protect()` only changes the route for a socket the app itself owns, and these sockets belong to other apps.

## What does not fix it

- Reordering `bringUpTunnel`. The code already calls `establish()`, publishes the new owner, and only then closes the bridge fd. The leaked segment is produced inside `establish()`, before either of those lines runs.
- Holding the bridge across the call. Android allows one VPN interface per service. `establish()` replaces it; an app cannot have the bridge and the new tunnel installed at the same time.
- `setBlocking(true)` on the TUN. That changes the read/write behavior of the fd, not what the kernel does with an existing connection when the interface disappears.
- An `iptables` redirect or mark inside the guest. `nat OUTPUT` runs before the VPN routing rule and steals the packet from the tunnel even while the VPN is healthy. `mangle OUTPUT --set-mark` replaces Android's whole fwmark. Both were tried and removed.

## What can be done

**A normal install.** Ask the user to enable Android's "Block connections without VPN" (Always-on VPN). There is no `VpnService` API to turn it on, and it should not be forced: it also drops traffic during the moments the app deliberately has no tunnel, which is the user's decision to make. While it is on, the kernel drops the segment this window emits.

**A rooted device.** The packet is only forwarded because `eth0` will route a source of `10.7.0.1`. Strict reverse-path filtering drops it on the way out:

```sh
sysctl -w net.ipv4.conf.eth0.rp_filter=1
sysctl -w net.ipv4.conf.all.rp_filter=1
ip route add 10.7.0.1/32 dev lo
```

`rp_filter` discards a packet whose source would not be routed back out the interface it arrived on, which is exactly a `10.7.0.1` segment leaving `eth0`. The route to `lo` covers the moment the TUN route is gone and `eth0` would otherwise be selected. Both are reversible. An `iptables` DROP in `mangle OUTPUT` does the same job but has to be wrapped around every rotation, so it is the worse of the two.

## Maintainer's note

This justifies a policy acknowledgement, not a policy change. Within the auto-failover handover specifically, the app is almost powerless: the packet leaves from inside a platform call the service cannot surround. The fail-closed bridge still does its job for every new connection, and that design stands. The one mitigation available to a normal user must be encouraged, not forced.

## Evidence

Two dead-to-dead emulator runs (`/tmp/android-qa/2026-10-06-tests`, test2 and test3) did **not** show this leak: every `1.0.0.1:80` packet stayed on the TUN and no HTTP body came back. The leak was caught later by watching `eth0` directly during a rotation, then confirmed in `underlay.pcap` frame 1436 (`10.7.0.1:60948 → 1.0.0.1:80` FIN, epoch `1791680517.267`) followed by the gateway's ARP query for `10.7.0.1` and its RST. The `FailoverTrace` lines for that rotation are quoted in the session that produced this record.
