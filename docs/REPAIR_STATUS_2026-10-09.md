# Harness repair status

Repair of the audit at revision 5a519dce3be75c4ca2266223615c5dd88c248a9e. All six phases share this uncommitted checkout. Existing UI design, schema storage and dependency/toolchain versions are preserved. Verification evidence and commands are in WORK_STATE.md. Supported repairs and remaining supported features are implemented; final verification and online/offline APK packaging passed. Provider/platform limits are listed below.

## Audit defects

| Audit | Result in code | Evidence boundary |
| --- | --- | --- |
| A01 / A11 | Validated shared Undo, original inventory, staged metadata, safe Keep and saved nested project roots | Checkpoint/recovery regression suites; actual process death not tested |
| A02 / A04 / A05 / A10 | Explicit UNVERIFIED, supervisor completion, ownership before side effects, guest verifier with bounded output and cancellation | Supervisor/verifier/routing regressions; device lifecycle not tested |
| A03 | Removed misleading child Stop/Send controls; inspectors explain how to cancel the full task | Source wiring guards; individual provider controls remain unsupported |
| A06 | Reuse healthy accounts on bounded network retry; publish failed-attempt file changes; refuse replay after tool execution | Account/routing/source guards and durable REPLAY_UNSAFE test; live network failover not tested |
| A07 | Claude gateway converts Responses messages, tool calls/results and usage and uses /responses | Local HTTP provider fixture and conversion tests; authenticated provider not tested |
| A08 | Interrupted/premature-EOF gateway stream emits error without normal completion | Injected InputStream interruption/EOF fixtures |
| A09 | Assistant end_turn cannot complete a Claude session; only top-level result permits wrapper cleanup; process outcome gates success | Source guard; live Claude child session not tested |
| A12 | FIFO/PTY drainage to bounded 5 MiB capture and bounded UTF-8 line assembly; overflow fails | Drainage/overflow and parser tests; Android JNI/PRoot timing not tested |
| A13 | Recheck attachment owner and remaining capacity at publication, discard abandoned imports | Source guard; rapid device navigation not tested |
| A14 | Preview applies explicit navigation requests and releases its WebView | Source guard; visual/navigation device test not run |
| A15 | Preserve explicit Anthropic proxy protocol in normalization/preferences/profile reload | Normalizer/profile tests; Codex remains Responses-only |
| A16 | Resolve current Inspector entries by ID; read bounded confined transcript tails on IO with FileObserver | Tail/containment and source guards; device observer behavior not tested |
| A17 | Installer probes always kill/reap and remove captures | Source guard; actual install cancellation not tested |
| S01 | Mask inactive engine/account credential homes from ordinary guest paths; opt-in Claude tool approvals | Mount-selection and approval parsing tests; PRoot is not an OS sandbox |
| S02 | Bound gateway concurrency, headers, bodies and socket reads; authorize before body reads; close client/upstream connections | Stalled unauthorized request and shutdown fixtures |
| R01 / R02 | Startup snapshot and execution barrier; nonblocking native wait monitor and bounded shutdown escalation | Startup snapshot, timeout/cancel and supervisor tests; native signal timing not tested |
| P01 | Seven pinned runtime inputs are present and verified; Gradle rejects missing, wrong-size or wrong-digest inputs. Offline Codex reuses verified extraction without network fallback | Fetch verification, online/offline APK assembly and all seven embedded archive checks passed |

## Supported feature work

- Claude and Codex retain successful native resume IDs scoped to chat, canonical workspace, provider route and credential identity. IDs are synchronously invalidated before launch; failures/process death cannot reuse uncertain partial runs. Chat clear removes saved references. CLI argv contract checks and scope tests cover the integration; authenticated resume is not tested.
- Claude supports opt-in per-tool approvals through its PermissionRequest hook and the existing approval sheet: `/approvals on` or `/approvals off` applies to the next run. Requests are session-scoped, input and wait are bounded, malformed input denies, and answered requests do not reopen. The default automatic workflow is preserved.
- `/turns [1–200]` displays/sets Claude's next-run budget; default remains 25.
- Codex renders cumulative agent-message updates once per item and keeps reasoning block identity.
- Capabilities describe implemented features. Missing session usage is explicitly unavailable; Claude rate limits are labeled cached with their report timestamp; Antigravity usage names the selected account.

- DSH's pinned SDK `initialize.reasoningEffort` is wired to the existing `/effort` picker: default preserves provider defaults; off/low/high/max are forwarded and validated by the selected adapter. The setting persists independently from other engines. Provider validation failures remain failures.
- DSH maps real usage chunks and assembled-message usage to cumulative session counters. Per-step replacement prevents duplicate accounting; malformed counters and foreign session IDs are ignored. SDK subagent.started/finished notifications retain child identity and parent ownership.
- Codex maps collab_tool_call agent snapshots and command executions into the existing Inspector. Distinct children remain distinct in the registry; child completion cannot complete the parent. Command output and retained entries are bounded.
- Agent output advertises local Preview candidates. Readiness uses short HTTP HEAD probes on IO, with no proxy or redirect following and no TLS relaxation. At most eight URLs per attempt and five attempts per URL; task/chat/project/session/start-time checks reject stale, canceled and failed publications. Successful probes can finish after a successful or unverified parent result. Terminal detection reuses the URL parser.
- Test-only ConscryptMode.OFF allows the two formerly excluded Robolectric suites to use their standard Java test provider. App TLS behavior and dependencies are unchanged.

## Remaining feature/platform limits

- DSH still uses history replay. The pinned SDK exposes initialize, session/prompt and shutdown, but no external native-resume, per-tool approval or individual-child-control method. Its agent library has a separate resume API that the SDK does not expose. Model-facing child-control tools are not an external control channel.
- Individual child/input controls and Codex/Antigravity headless approvals remain unavailable in the current process integrations; the UI explains these limits. Additional artifacts/timers/jobs are displayed only where an established provider event schema exists. Supporting more requires an appropriate acknowledged protocol, rather than fabricated UI state.
- Automatic Preview discovery requires a local URL in agent output and a responding HTTP server. It does not scan ports or infer unreported servers. Existing manual Preview remains available.
- Credential mounts mitigate ordinary cross-engine path access; hostile native tools can escape PRoot's apparent filesystem. Strong credential isolation requires an actual security boundary. Active engine credentials remain accessible to that engine.
- Online APK still downloads core and selected runtimes on fresh installation. Offline inputs now include all four engines, core, Python and Android tooling. Fresh/offline installation on Android remains untested; the offline APK is large. Upstream release ownership and native prebuilt-library equivalence were not changed.
- No claims of measured CPU/memory/battery improvements, live provider compatibility, Android process-death resilience or visual/accessibility compliance beyond the listed checks.

## Final verification and distribution

- VERIFIED: continuation targeted selection: 176 tests across 25 suites, no failures/errors/skips. Includes WakeLockManagerTest and CodexPreferencesTest with no suite exclusions. Log: docs/remaining-feature-green.log.
- VERIFIED: four Python archive-verification tests and all seven pinned archive inputs, including Codex SHA-512. Logs: docs/remaining-bundle-red.log and docs/remaining-bundle-verification.log.
- VERIFIED: wider selection: 963 tests across 103 suites, zero failures/errors/skips. lintOnlineDebug passed with 123 warnings and 2 hints, no errors; verifyOfflineRuntimeAssets passed. Final completion log: docs/remaining-feature-completion-verification.log.
- VERIFIED: assembleOnlineDebug and assembleOfflineDebug passed in docs/remaining-feature-final-apk.log. Root online APK copies are 113,831,840 bytes, MD5 badac013f85db974f15d9d5aee3be97c. Root offline APK is 1,071,272,395 bytes, MD5 cf2332f79854b6f8dee3ab80b4a3a373.
- VERIFIED: both variant signatures use certificate SHA-256 93a815c394ca54603371ee36f7617994bab304d4e5df8b37fe5a88c081ad154e, matching the previously verified saved keystore. All seven offline embedded runtime archives match their pinned sizes/digests; the online Antigravity archive also matches. Evidence: docs/remaining-feature-embedded-verification.log and variant signature logs.
- VERIFIED: final git status --short, git diff --stat and git diff --check. Prior full source diff review is retained in docs/remaining-full-diff-review.log; this finalization only updates status documentation.
- NOT RUN: authenticated live providers, actual Android process death/native signal timing, device UI/accessibility, fresh/offline installation and CPU/memory/battery profiling. Protocol/platform limits above are not claimed complete.
- No commits or pushes. Existing UI design, dependency versions and unrelated changes preserved.
