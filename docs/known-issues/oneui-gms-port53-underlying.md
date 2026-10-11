# One UI / GMS port-53 queries leave on the underlying network

**Status: OBSERVED ON DEVICE, not reproduced on a clean emulator, not fixable from the app on current evidence.** Do not re-investigate from scratch. This is not the rotation-handover segment leak and it is not a Termux DNS leak.

Recorded 2026-10-11 from maintainer packet captures on SM-S918B / Android 15 / One UI, compared with a clean emulator that did not show the same traffic.

## Verdict (read this first)

While the VPN was up, plaintext DNS to the LAN gateway (`192.168.137.1:53`) kept appearing on the underlying interface. The queries were only Google-related names: `google.com`, `connectivitycheck.gstatic.com`, `googleapis`, and the same family. User-space port 53 from Termux **was** intercepted and sent to the configured resolver (AdGuard DoH while Android Private DNS was on). The Google queries did not move.

That split is the evidence. The app already hijacks port 53 that enters the TUN. These packets never entered it. On a clean emulator the same queries were absent, so this is attributed to One UI or Google Play services issuing their own connectivity checks outside the VPN, not to `makeSecureDns` failing to catch app DNS.

## What was observed

- Device: SM-S918B, Android 15, One UI. Bypass LAN off.
- Underlying DNS: `192.168.137.1:53`, names limited to the Google connectivity set above.
- Private DNS on, AdGuard as the resolver: Termux port-53 was captured and forwarded. The Google port-53 queries still left on the underlying interface.
- Clean emulator: this port-53 pattern was **not** observed.

## What this is not

- Not [the rotation handover segment leak](rotation-handover-segment-leak.md). That one is a single in-flight segment emitted from inside `VpnService.Builder.establish()`. These are DNS queries, and they are not confined to the handover.
- Not a failure of the port-53 → `dns-out` rule for traffic that actually reaches Xray. Termux showed that path works.
- Not a proven socket trace of a specific UID. The process was not captured. The attribution is from the name set plus the emulator contrast, not from `dumpsys` or a UID filter.

## What does not fix it

- Tightening `makeSecureDns` or the port-53 routing rule. Those act on packets that enter the TUN. These did not.
- Pointing Private DNS at AdGuard. That changed where intercepted user-space DNS went. It did not move these queries.
- Treating them as the Test 23 plain-HTTP destination. They are a different leak class.

## What can be done

Nothing in the app, on this evidence. A VPN app cannot stop a privileged Google or OEM component from resolving connectivity-check names outside the tunnel. Re-open only with a UID or process capture that shows an ordinary app, or with the same queries on a clean emulator.
