# Auto-Failover QA and review triage

Status after the maintainer's 2026-11-23 device run, the maintainer's later confirmation of Tests 6 and 7, and the scoped review of debug commit `e57953e`.
This file records dispositions; it does not turn agent observations into human verification.

Sources:
- Maintainer-run results recorded in [`auto-failover-qa.md`](auto-failover-qa.md).
- Dormant/superseded procedures in [`auto-failover-qa-dormant.md`](auto-failover-qa-dormant.md).
- Agent evidence in the structural-verification SDD workspace; agent logs are diagnostic evidence, not
  packet-capture or human-review substitutes.
- Scoped debug-dump review recorded in the debug-log-dumps SDD workspace.

## Decisions

| Finding | Evidence and severity | Decision |
|---|---|---|
| Debug dump status mixes stale path/chunk/failure lines | **Important, confirmed.** `DebugLogDumpActions.kt` stores path, chunk count, and failure in three independent process-global StateFlows and never clears the other outcomes. A failed second action can leave an old pullable path beside the new failure. | **Fix in one scoped debug-only wave.** Replace with a single atomic last-outcome state, add focused regression tests, and document last-action-wins semantics. |
| Dump marker can be `<none>` outside `CONNECTED`, or name the last successful profile during rotation | Reviewer called this Important. The approved interface explicitly used `ActiveProfileRepository` plus `connectionState`; `ActiveProfileRepository` advances only on successful connection. Widening to `CONNECTING`/`BLACKHOLED` would not identify the actual in-use rotation target and could make the evidence more misleading. | **Do not change VPN/session behavior. Reclassify as a documentation/evidence limitation.** The marker identifies the repository's active profile only while `CONNECTED`; it is not a rotation-target oracle. Clarify `docs/features/logs-screen.md` during the debug fix wave. A future authoritative session-profile exposure may improve this separately, but is not part of this debug utility. |
| Release no-op stub has no automated guard | **Important test-sustainability gap, not an observed release leak.** Source-set isolation and release APK absence were verified, but a future stub edit could regress without a repeatable CI check. | **Add a low-cost release-source-set guard test** in the debug fix wave. Declare the source file as a Gradle test input, as required for filesystem-reading tests. Keep release runtime inspection as the packaging oracle. |
| `writeDumpFile` ignores `mkdirs()` result | **Minor, confirmed.** Failure remains contained and visible, but diagnostics are less direct and the unwritable-directory branch lacks a focused test. | **Fix opportunistically in the same debug-only wave** with a clear error and focused JVM test. |
| Overlapping logcat dumps can interleave same-tag chunks | **Minor/theoretical in current QA use.** No session token or serialization exists; the file dump is the full-fidelity path. | **Defer code change.** Document non-overlapping logcat dumps and prefer file dump for canonical retrieval. Do not expand the wire protocol without a reproduced QA failure. |
| Debug status persists across Logs-screen navigation | Intentional requirement/trade-off, not a defect. | **No action.** Atomic replacement must preserve persistence until the next dump action/process death. |
| Logcat "byte-exact" wording | Documentation nuance: exactness is after ordered concatenation and one unescape, which the procedure already describes. | **Clarify wording only when touching the doc; no behavior change.** |
| Test 13f busy-path result | Observed FAIL against the old message expectation. The maintainer overruled it because the outcome matched the same-pool case and was judged equal or better. | **No product change.** Preserve the maintainer ruling in the matrix. The separately documented stale-success/cross-run heuristic remains a known limitation, not a new failure. |
| Tests 5a–5d old `UNPROTECTED` rotation lifecycle | Superseded by pre-teardown bridge establishment. A bridge failure now aborts into give-up while the prior live/dead TUN remains authoritative; fresh permission revocation reports `VPN permission was revoked`. | **No VPN code change and do not restore release-before-establish.** Archive the old procedures. The current behavior is the stronger containment policy. |
| Test 14 always-on watchdog setup | Reproduced as unreachable on two phones/Android versions; notification permission did not make the watchdog retry. No app-side failure oracle was reached. | **Dormant; no app fix without evidence.** Reactivate only with a deterministic helper that preserves pending-B/running-A and rollback checks. Do not build speculative lifecycle changes. |
| Test 16 `UNPROTECTED` recovery | Unreachable because historical 5a cannot be produced through the current rotation-abort path. | **Dormant; no product change.** Do not substitute contained Reconnect scenarios. |
| Notification Stop appeared to bounce back into VPN start | One agent observation: `VPN stopped` followed 27 ms later by `Starting VPN service`; not reproduced. No intent/startId/always-on evidence establishes the cause. | **Unconfirmed Important watch item, not a known defect.** Do not patch from one log pair. If Test 6 is rerun, capture notification Stop timing, full lifecycle logcat, start intent/action, and always-on state. Otherwise leave open without severity escalation. |
| Main Disconnect versus row Reconnect | Code intentionally renders state-row Disconnect for active/stoppable `BLACKHOLED`, while `connectAction(BLACKHOLED)` drives profile-row Reconnect. Old QA prose incorrectly expected the main/state-row button to read Reconnect. | **Documentation drift, fixed in the matrix; no UI change.** |
| `vpn_status_no_response` wording | QA expected "no other server to try"; actual English resource is "Server is not responding — couldn't switch to a working one". | **Documentation drift, fixed in the matrix; no string change.** |
| Test 23 known capture residuals | Maintainer passed Test 23 with the accepted limitations already recorded in `docs/known-issues/`. | **No new action and no stronger containment claim.** Keep both known issues open and distinct from this campaign. |

## Release disposition

1. **Debug-only follow-up:** the status holder rejects stale completions after a newer request is
   admitted. The release-stub guard and directory-error tests were added. Rapid clicks can still
   admit requests out of order, and the revised debug UI has yet to be rechecked on-device.
2. **Maintainer QA:** Test 6 (notification **Turn off** after a contained give-up) and Test 7 (tile
   rendering and Stop in the give-up state) passed. The earlier Test 6 Stop→start report remains
   unreproduced, and the archived `UNPROTECTED` variant remains dormant. The confirmations did not
   identify an exact APK or give-up owner.
3. **Release authorization:** the maintainer declared the release pipeline complete and authorized
   integration into `main` and the `3.0.0-Release` tag. This records that decision without adding
   device or human-review evidence to the QA record.
4. The `FailoverEngine`/`TunnelHost` migration and the documented packet-capture residuals remain
   follow-up work.
