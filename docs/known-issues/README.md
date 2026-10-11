# Known issues

Confirmed defects that are **not yet fixed**. Distinct from [`docs/features/`](../features/) (how a shipped feature works) and [`docs/qa/`](../qa/) (how to exercise one).

Check here before re-investigating a bug that already has a record. When an issue is fixed, move or delete its page and drop the row below — a fixed entry left in this folder is worse than no entry.

| Issue | Status |
|---|---|
| [Subscription refresh duplicates the active server](subscription-refresh-duplicate-profile.md) | Confirmed in code, unfixed |
| [Rotation handover leaks one segment of an in-flight connection](rotation-handover-segment-leak.md) | Confirmed on device, not fixable from the app |
| [One UI / GMS port-53 queries leave on the underlying network](oneui-gms-port53-underlying.md) | Observed on device, absent on a clean emulator, not fixable from the app |
