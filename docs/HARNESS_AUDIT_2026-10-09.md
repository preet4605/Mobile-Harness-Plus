# Mobile Harness Plus: harness and application audit

Audited revision: `5a519dce3be75c4ca2266223615c5dd88c248a9e`, branch `test-ui`.
Date: 2026-10-09. Task: `eb2b9b63-548b-436e-9d22-4f8feec61c41`.

Diagnosis only. No application code, dependencies, Git history, or provider credentials changed. The repository was clean at the start. This report, the work state, and an isolated executable probe are new documentation artifacts. The online debug APK was rebuilt and copied to the project root.

## Scope and evidence

The repository was mapped across its four harnesses and shared UI, runtime, persistence, networking, Android services, installation, and build/distribution paths. There are 125 application Kotlin files and 93 test files matching `*Test.kt`. Review followed the relevant launch, parsing, completion, retry, cancellation, checkpoint, provider, and UI call paths; an inventory is not a claim that every line of third-party PRoot/talloc/libshmem code received an independent security review.

`VERIFIED — reproduced` means an isolated local check exercised the compiled implementation. `VERIFIED — source` means the cited implementation and its call sites establish the behavior or missing integration. It does not mean a live provider or device workflow was exercised. Conditional effects and timing risks are labeled separately. Prior audit documents were treated as historical context and checked against this revision.

The audit ran in the phone's PRoot Ubuntu environment with Java 17 and installed Gradle. `rg` was unavailable; searches used Git and Python. The supplied reviewer/Android skill paths were unavailable. Copied rules named a nonexistent `/workspace/curious-curie`; documentation uses the actual project checkout `/workspace/bright-kalam`.

## What each harness currently has and lacks

“Missing” below describes the app integration, not a claim that the underlying vendor CLI cannot implement it. A fresh process with replayed chat history is different from native session resume.

| Feature | Claude Code | DeepSeek Harness (DSH) | Antigravity | Codex |
| --- | --- | --- | --- | --- |
| Authentication | Native subscription login, legacy setup token, API key | API-key routes; Claude/ChatGPT subscriptions explicitly rejected | Google accounts, account routing and quota tracking | ChatGPT login and custom Responses API keys |
| Provider protocols | Native Anthropic plus format gateways; Responses gateway is incorrect, see A07 | Anthropic, Chat Completions, Responses through route profiles | Native account-backed CLI; local compatibility gateway for other harnesses | Responses only; Chat Completions URLs rejected. Bare custom URLs force Responses regardless of stored protocol |
| Model selection | Subscription slots and provider models | Provider model selection | Discovered/account models | Native model catalog and custom provider models |
| Reasoning effort | Claude effort controls | No app effort control | Low/medium/high, model validation | Model-specific catalog effort choices and launch override |
| Conversation continuity | New CLI invocation with app history replay; no native resume flag | New SDK session with app history replay | Conversation ID and sticky account; failover can replay | `exec --ephemeral`; app history replay; thread ID parsed but not retained as resumable state |
| Visible output | Text/thinking/tool stream | SDK text/tool notifications | Text/thinking/tools and auxiliary events | Commands/files/MCP/web-search mapped; agent text and reasoning accepted only at `item.completed`, see G01 |
| Interactive approvals | Permission watcher automatically allows requests, including parse-error path | Full-access permission mode; approval callback is a no-op | Skip-permissions flag; approval callback is a no-op | `approval_policy=never`, `danger-full-access` |
| Usage | Session token events and cached last rate-limit report | Official DeepSeek balance; no `RuntimeEvent.TokenUsage` mapping | Primary account model quota report; auxiliary metadata | Turn token usage plus app-server account/rate-limit reporting |
| Subagents/background jobs | Auxiliary event display exists; controls incomplete, A03/A16 | No subagent/task/artifact/timer mapping in bridge | Auxiliary event display exists; controls incomplete, A03/A16 | No app subagent/task/artifact/timer mapping in parser |
| Attachments | Shared import and file-path prompt; native `Read` workflow | Shared import and file-path prompt | Shared import and file-path prompt | Shared import plus explicit CLI `-i` for supported image extensions |
| Workspace tools | Shared Terminal, Files, Preview, changes review, memory, skills/rules | Same shared tools | Same shared tools; browser slash workflow | Same shared tools |
| Undo/recovery | Shared checkpoint defect A01 | Same defect | Same defect, plus failed-attempt gaps A06 | Same defect |

Source anchors: [driver registry](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/AgentDriver.kt:51), [Claude launch configuration](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/RuntimeBridge.kt:43), [Claude command](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/ClaudeRuntimeBridge.kt:1127), [DSH SDK session](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/DshRuntimeBridge.kt:180), [DSH routes](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/DshRuntimeBridge.kt:798), [Antigravity conversation/account loop](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/AntigravityRuntimeBridge.kt:523), [Codex routes](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/CodexLaunch.kt:34), [Codex launch and images](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/CodexLaunch.kt:124), [Codex events](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/CodexProtocol.kt:80), [usage reports](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/UsageReports.kt:12), [Codex rate limits](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/CodexModelCatalog.kt:197), [effort application](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt:3889), [slash commands](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/SlashCommandEngine.kt:11).

Common gaps: no selectable approval policy; no complete child-process control UI; no native continuation in three bridges; verification does not establish task completion; agent-launched servers have no `PreviewStarted` emitter. Manual Preview and Terminal URL detection do exist, so Preview is not wholly missing. DSH lacks session usage telemetry and effort control; Codex lacks progressive text updates and auxiliary activity integration. No external CLI compatibility claim was independently tested with a logged-in provider.

## Prioritized bugs and usability defects

Priority P1: possible data loss, false completion/control, broken advertised route, or unsafe retry. Priority P2: incorrect state, reliability, resource use, or significant usability. These are proposed fixes, not changes applied during the audit.

### A01 — P1 — Undo/recovery can delete an existing file omitted from its backup

**VERIFIED — reproduced. All harnesses.** Checkpoint creation skips files larger than 25 MiB and ignores copy failures. Snapshot/change detection still tracks those paths. Restore equates “no backup file” with “new file” and deletes the current file. The four bridge Undo paths make the same assumption.

Evidence: [backup creation](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/WorkspaceCheckpoints.kt:75), [restore deletion](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/WorkspaceCheckpoints.kt:180), [snapshot](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/WorkspaceCheckpoints.kt:584), [25 MiB limit](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/WorkspaceCheckpoints.kt:650), [Claude Undo](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/ClaudeRuntimeBridge.kt:503), [DSH Undo](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/DshRuntimeBridge.kt:471), [Antigravity restore](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/AntigravityRuntimeBridge.kt:858), [Codex Undo](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/CodexRuntimeBridge.kt:298), [recovery restore](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/task/RecoveryEngine.kt:436).

Probe: create a sparse pre-existing 26 MiB file, checkpoint, alter its length, save detected changes, restore. The backup is absent, restore returns `true`, and the original file is deleted.

Smallest fix: persist the original path inventory and backup success/omission status; only delete paths positively known to have been created later. Reject/report restoration of originals without a usable backup. Preserve metadata compatibility. Regression: oversized original, simulated copy failure, deleted original, and genuinely new file across both restore and per-file Undo.

### A02 — P1 — Generated task verification always succeeds

**VERIFIED — reproduced. All harnesses.** Automatically generated steps contain `verificationCommand = "true"`. The verifier considers any nonblank command deterministic criteria, while natural-language acceptance criteria are not evaluated. The supervisor then creates successful feedback with `MemorySource.TOOL_VERIFIED`.

Evidence: [generated commands](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/task/TaskDecomposer.kt:142), [fallback step](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/task/TaskSupervisor.kt:630), [criteria gate](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/task/StepVerifier.kt:292), [feedback source](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/task/TaskSupervisor.kt:1936).

Probe: objective/acceptance criterion “Create result.txt containing DONE”; the generated step passes in an empty directory with no result file.

Smallest fix: omit the unconditional command; represent absent verification explicitly and withhold verified-success feedback. Supply meaningful expected-file/content/test criteria where available. Regression: unchanged empty workspace must fail or require review, and only a satisfied concrete criterion may produce tool-verified success. Do not add a blanket file-change requirement: read-only tasks need their own criteria.

### A03 — P1 — Auxiliary Stop and Send controls do not perform their advertised actions

**VERIFIED — source. Shared inspector, especially Claude/Antigravity.** Terminate handlers only mark registry entries terminated. They do not call a bridge or signal a child. The dialog call sites omit the Send callbacks; default no-op callbacks clear the user's text.

Evidence: [terminate handlers](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt:3841), [dialog wiring](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/WorkspaceScreen.kt:749), [task input no-op](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/CliParityComponents.kt:1758), [subagent message no-op](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/CliParityComponents.kt:1839).

Impact: users can believe a child was stopped while it continues working; typed input is silently discarded. Smallest fix: wire a supported engine-specific control channel and await acknowledgment. Where no channel exists, disable the action with an honest explanation. Regression: an action must reach the identified child, or remain unavailable; a failed/no-op send must retain the draft. Live child execution was **NOT RUN**.

### A04 — P1 — Chat completion precedes supervisor verification

**VERIFIED — source. All harnesses.** `SessionCompleted` immediately clears chat running/session state and marks all registered subagents/background jobs complete. Bridges emit this before returning; the supervisor verifies steps afterward. Chat listens to the shared bridge events, not a final supervisor outcome at that point. Successful chat memory is also recorded on the bridge event.

Evidence: [chat completion reducer](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt:5153), [memory success](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt:5281), [post-execution verification](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/task/TaskSupervisor.kt:1720), [Codex completion before return](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/CodexRuntimeBridge.kt:208).

Smallest fix: distinguish provider-turn completion from verified task outcome. Keep the tracked task active through verification/recovery, and update child states only from authoritative child events. Regression: bridge success followed by verifier failure must show a verification failure/retry, must not unlock a second task prematurely, and must not record verified success.

### A05 — P1 — Rejected stale session events still have side effects

**VERIFIED — source. All harnesses.** Routing checks protect the `_state.update` reducer only. Authentication invalidation and API-key fallback run before the check; cleanup, memory success/error recording, refresh, and persistence run afterward even for rejected events. A late event from an old session can therefore affect the currently selected project/task despite leaving visible messages unchanged.

Evidence: [pre-gate actions](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt:4975), [routing gate](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt:4994), [post-gate actions](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt:5270), [session acceptance contract](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/RuntimeSessionRouting.kt:21).

Smallest fix: apply session/task/project validation to the whole handler before any effect; retain supervisor-owned retry handling explicitly. Regression: stale failure/completion after a new session starts must not invalidate current auth, clear current request/key state, or write memory to another project. Timing reproduction on-device is **NOT RUN**.

### A06 — P1 — Antigravity retry exhausts a healthy single account and replays failed work

**VERIFIED — source; live outage NOT RUN.** Every selected account is added to `attemptedAccountIds`. Network retry leaves it excluded, so the next selection cannot reuse it. With one account, a transient network failure reaches the “all unavailable” branch instead of the promised bounded reconnect. Changed-file tracking occurs only on success; quota/network failover replays without first recording/reconciling mutations from the failed attempt.

Evidence: [account exclusion](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/AntigravityRuntimeBridge.kt:523), [success-only changes and retry](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/AntigravityRuntimeBridge.kt:679), [network retry](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/AntigravityRuntimeBridge.kt:726), [selector honors exclusions](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/AntigravityAccountManager.kt:480).

Impact: reconnect can fail unnecessarily. If a failed run already wrote files or performed an external action, replay can duplicate work; actual duplicate external execution was **NOT RUN**. Smallest fix: separate transient-network retries from auth/quota account exclusions; track changes in a failure/finally path and route replay through existing recovery/idempotency policy. Regression: one healthy account plus one transient failure succeeds on retry; a partially modifying failure produces a reviewable diff and a defined safe retry decision.

### A07 — P1 — Claude's Responses provider route sends Chat Completions requests

**VERIFIED — source. Claude with an OpenAI Responses custom/compatible provider.** Both OpenAI protocols select `LocalFormatGateway`, which always sends `/chat/completions`, serializes `messages`, and parses `choices[0].message`. It never implements `/responses` input/output conversion. Validation in `ProviderApiClient` does support Responses, so a profile can validate but fail during execution.

Evidence: [both protocols require gateway](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/RuntimeBridge.kt:82), [Chat request conversion](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/LocalFormatGateway.kt:109), [Chat response conversion](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/LocalFormatGateway.kt:171), [fixed endpoint](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/LocalFormatGateway.kt:198), [Responses validation](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/network/ProviderApiClient.kt:237).

Smallest fix: add Responses conversion inside the existing gateway selected by the resolved protocol, or explicitly mark this route unsupported until implemented. Regression: a local mock provider accepting only `/responses` with Responses-shaped input must complete through Claude, including tools and errors. Provider request execution was **NOT RUN**.

### A08 — P1 — Interrupted Antigravity gateway streams are completed as success

**VERIFIED — source. Claude/DSH routed through the Antigravity gateway.** An upstream streaming exception is logged and swallowed; execution falls through to the same normal `end_turn`/message-stop or `stop`/`[DONE]` output as a completed response. Partial output can be presented as a successful result.

Evidence: [exception and unconditional finish](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/AntigravityGatewayServer.kt:458).

Smallest fix: track authoritative upstream completion; propagate an interrupted/error result instead of synthesizing successful finish events. Regression: an injected input stream that throws after one chunk must yield failure and never normal terminal-success output. Network interruption reproduction is **NOT RUN**.

### A09 — P1 — Claude treats assistant `end_turn` as process/session completion

**VERIFIED — source; provider-origin contract remains unverified.** Any parsed assistant message with `stop_reason=end_turn` emits completion and gracefully terminates the active process. There is no parent/subagent-origin check in that branch. Later, membership in `finishedSessions` allows success even with a nonzero process exit.

Evidence: [assistant branch](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/ClaudeRuntimeBridge.kt:674), [early completion/termination](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/ClaudeRuntimeBridge.kt:721), [exit success condition](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/ClaudeRuntimeBridge.kt:397), [authoritative result parsing](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/ClaudeRuntimeBridge.kt:769).

Conditional impact: if a child assistant or intermediate assistant turn carries this value, the parent is cut short; even a normal turn can hide a later process failure. Live subagent JSONL was **NOT RUN**, so this report does not claim an observed child-triggered termination. Smallest fix: separate assistant-turn markers from authoritative top-level result/process outcome; preserve explicit cancellation and bounded linger cleanup. Regression: child/intermediate `end_turn` must not terminate the parent; terminal success followed by a process/protocol error must follow an explicit outcome contract.

### A10 — P2 — Verbose verification commands block; verification runs outside the guest

**VERIFIED — reproduced for pipe blockage; source for environment mismatch. All harnesses.** The controlled verifier waits for process exit before reading its merged output pipe. A command filling the pipe cannot exit and times out. It also launches `/system/bin/sh`/`sh` directly through Android `ProcessBuilder`, rather than through the PRoot bridge where the project's Node/Java/Git toolchain lives. JVM tests inside Ubuntu mask that environment difference.

Evidence: [runner](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/task/StepVerifier.kt:206).

Probe: the same shell-builtins command writes 1,400,000 bytes successfully to a file in 1,153 ms, but the verifier times out at two seconds. A small `printf` succeeds.

Smallest fix: concurrently drain output into the existing bounded buffer, execute through the existing guest launch path for Android, and on timeout cancel/reap the process group. Shell text validation is not an OS sandbox; setting HOME/TMPDIR alone does not establish filesystem confinement. Regression: verbose output, guest-only executable, timeout with descendant, and cancellation. Device environment/descendant checks are **NOT RUN**.

### A11 — P2 — Supervisor verification/checkpoints ignore a selected nested project root

**VERIFIED — source. All harnesses.** `configureBridgeRoots` configures all four bridges, but the supervisor creates a separate unconfigured checkpoint store and defaults its workspace to `workspaces/projectId`. Its resolver hooks have no production assignments. Verification and recovery can therefore refer to the enclosing workspace while the engine uses a nested root.

Evidence: [bridge root mapping](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt:1286), [supervisor checkpoint resolver](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/task/TaskSupervisor.kt:101), [workspace resolver](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/task/TaskSupervisor.kt:453).

Smallest fix: supply the same resolved root/store to supervisor and bridge while preserving path containment checks. Regression: choose `apps/demo`, create expected relative files there, then verify and recover using exactly that root. Device workflow is **NOT RUN**.

### A12 — P2 — Native capture bounds do not cover normal harness runs

**VERIFIED — source; packaged JNI implementation NOT RUN. All harnesses.** Kotlin's 5 MiB output cap applies to the PTY pump only. Non-PTY C launch redirects stdout/stderr straight to a file and returns no output FD to pump. Normal bridge capture files can grow without that limit; Claude/DSH/Antigravity line accumulators also have no oversized-line bound and decode arbitrary byte chunks independently. Codex already uses a bounded byte-line assembler and deletes its captures during cleanup.

Evidence: [conditional PTY pump](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/NativeSpawnProcess.kt:109), [non-PTY direct output](/workspace/bright-kalam/app/src/main/cpp/pocket_spawn.c:96), [Claude accumulator/decoding](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/ClaudeRuntimeBridge.kt:335), [Codex assembler](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/CodexProtocol.kt:191), [Codex cleanup](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/CodexRuntimeBridge.kt:234).

Impact: disk growth, large allocations, and possible corruption of split UTF-8 characters. No resource profiling was performed. Smallest fix: bound non-PTY capture while continuing to drain it, reuse the byte-line assembler, and clean old captures deliberately. Regression: huge output without newlines, split multibyte text, output after cap, and cleanup after failure/cancellation. The C source is not rebuilt by the current Gradle native configuration; test the packaged JNI separately, see packaging notes.

### A13 — P2 — Attachment import can finish in the wrong chat/project

**VERIFIED — source. All harnesses.** Import captures the initial project/chat and remaining capacity, copies asynchronously, then adds attachments to whatever UI state is current. It does not recheck the project/chat, running state, or current attachment count. Switching chats, sending, or launching two imports during copying can attach stale files or exceed the limit.

Evidence: [asynchronous import](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt:3667), [chat switching](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt:3484), [existing guarded refresh pattern](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt:3535), [Codex path filter](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/CodexLaunch.kt:163).

Smallest fix: validate captured project/chat ownership on publication, cancel stale imports, and enforce capacity against current state. Regression: deferred copy across chat switch/send and overlapping selections. Device picker timing is **NOT RUN**.

### A14 — P2 — Preview can reload the initial URL after in-page navigation

**VERIFIED — source; WebView timing NOT RUN. Shared Preview.** Allowed navigation updates `address`, but not `activeUrl`. `AndroidView.update` reloads `activeUrl` whenever the WebView's URL differs. Recomposition following a link can return the user to the earlier URL.

Evidence: [state](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/WorkspaceScreen.kt:2495), [navigation callback](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/WorkspaceScreen.kt:2578), [unconditional mismatch reload](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/WorkspaceScreen.kt:2596).

Smallest fix: distinguish an explicit user navigation request from current WebView location; synchronize the displayed location without triggering another load. Regression: navigate `/` to `/details`, cause recomposition, and verify location/history remain correct. The WebView also has no explicit release/destroy hook in this call site; measure lifecycle retention before calling that a confirmed leak. External resources/navigation are deliberately blocked, so CDN-backed previews are an acknowledged usability limitation rather than an accidental unrestricted browser.

### A15 — P2 — Explicit Anthropic protocol is overridden by URL guessing

**VERIFIED — reproduced. Custom provider paths, notably DSH.** A URL ending `/v1` without the word `anthropic` changes an explicitly configured `anthropic-messages` protocol to inferred OpenAI Chat Completions. A valid private Anthropic proxy need not have that brand in its hostname. Persisted profile migration performs similar inference.

Evidence: [normalizer](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/provider/CustomProviders.kt:106), [stored profile inference](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/provider/CustomProviders.kt:54), [preferences normalization](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/data/AppPreferences.kt:460).

Probe: `normalize("https://proxy.example/v1", "anthropic-messages")` returns `openai-completions`.

Smallest fix: preserve explicit protocol selection; use inference only for unspecified/legacy values with an explicit migration policy. Regression: an explicit Anthropic proxy ending `/v1` stays Anthropic, while unspecified and operation-suffix URLs retain intended normalization.

### A16 — P2 — Inspector logs are stale and read files on the UI thread

**VERIFIED — source. Shared auxiliary UI.** The selected inspector item is a copied object; registry updates do not refresh that selection. Transcript content is read once under `remember(transcriptPath)`, using unbounded `File.readLines()` in composition. A changing transcript at the same path is not refreshed. Guest transcript paths also need explicit host translation if the provider supplies guest paths.

Evidence: [dialog selection](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/WorkspaceScreen.kt:749), [list-only event updates](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt:5239), [transcript read](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/CliParityComponents.kt:1852).

Smallest fix: select by ID and resolve the current item; load bounded transcript tails on IO with lifecycle-aware updates and existing path mapping. Regression: an open inspector reflects new output/status and handles a large transcript without a main-thread file read. Frame/memory profiling is **NOT RUN**.

### A17 — P2 — Installer verification timeout does not clean up the spawned process

**VERIFIED — source. Shared installation.** `verifyGuest` spawns a process and waits inside a 60-second coroutine timeout without a cleanup `finally`. Timeout skips process termination and output cleanup. Codex auth verification already provides an example of bounded launch cleanup.

Evidence: [guest verification](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt:1536), [Codex auth cleanup](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/CodexAuthController.kt:123).

Smallest fix: always cancel/reap the launched process/group and dispose the capture on timeout/cancellation. Regression: a hanging setup probe leaves no live child or stale active install after cancellation. Real install cancellation is **NOT RUN**.

## Security boundaries and packaging

### S01 — P1 boundary gap — All unrestricted harnesses share account credential files

**VERIFIED — source; no credentials accessed.** API-key vault encryption uses Android Keystore/AES-GCM, and application backup is disabled. However, native login credentials must exist inside the guest: Claude `.credentials.json`, Codex `auth.json`, and Antigravity account token files. All harnesses execute against the same rootfs with virtual root; distinct HOME values do not isolate the filesystem. Combined with automatic/full-access tools, one engine can address another engine/account's credential files.

Evidence: [encrypted API vault](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/data/ApiKeyVault.kt:16), [Claude credentials](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/ClaudeAuthController.kt:46), [Codex credentials](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/CodexAuthController.kt:119), [Antigravity token files](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/AntigravityAccountManager.kt:327), [shared PRoot rootfs](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt:1667), [auto approvals](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/ClaudeRuntimeBridge.kt:588), [DSH full access](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/DshRuntimeBridge.kt:707), [Codex approval policy](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/CodexLaunch.kt:168).

This is a boundary between untrusted agent tools and app-owned guest secrets. It is not evidence that an arbitrary Android app can access the files, or that virtual root is Android system root. Proposed improvement: isolate engine/account credential mounts and offer a least-privilege approval policy. Verify containment with harmless dummy credentials and adversarial cross-engine file access; actual secret access/exfiltration is **NOT RUN**.

### S02 — P2 — Local format gateway has unbounded stalled-request handling

**VERIFIED — source.** Each accepted socket receives a new thread, with no socket read timeout. Even unauthorized requests have their declared body drained before the 401 response. A localhost client can hold multiple connections open; gateway close does not track/cancel active request threads/provider connections.

Evidence: [thread per socket and body drain](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/LocalFormatGateway.kt:32), [close](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/LocalFormatGateway.kt:307).

Smallest fix: authorize early without draining an untrusted body, bound concurrency and reads, and close active requests with the gateway. Regression: stalled unauthorized body receives timely rejection, connection count stays bounded, and shutdown cancels provider requests. Load testing is **NOT RUN**.

### P01 — Packaging limitations verified in this checkout/build

- Shared Antigravity runtime asset preparation reports `NO-SOURCE`. The source bundle directory contains the manifest but lacks archives. ZIP inspection of the rebuilt APK found no `assets/runtime/` entries or `.tar.zst` archives. This online APK therefore depends on runtime downloads for a fresh install. It can reuse an existing installation.
- Offline asset synchronization does not fail when its input archives are absent. `obtainRuntimeBundle` falls back to downloading if an embedded asset is missing, even when the offline flag is set. Codex installation explicitly requires online downloads. An offline APK was **NOT BUILT**, so this is a source-verified offline distribution gap, not a claimed failing offline APK test.
- `externalNativeBuild` is commented out; packaged native libraries are prebuilt. Editing `pocket_spawn.c` alone would not rebuild JNI. Native source findings require packaged-library/device confirmation before a native fix is declared verified.
- Runtime release and app-update URLs still point to `techjarves/Mobile-Harness`, not the fork. The updater checks package/version/signing certificate, which is protective, but fork-signed APKs cannot take a differently signed upstream update. Remote manifest contents and certificate compatibility were **NOT RUN/NOT CHECKED**; release ownership is a configuration question, not a verified current update failure.

Evidence: [asset tasks and release URLs](/workspace/bright-kalam/app/build.gradle.kts:28), [native build disabled](/workspace/bright-kalam/app/build.gradle.kts:185), [missing-asset download fallback](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt:1117), [Codex online restriction](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt:596), [update certificate checks](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/update/AppUpdater.kt:109).

Proposed packaging regression: verify required embedded assets at build time for their variants, reject missing offline assets explicitly, and test fresh install with network disabled. Version/checksum validation already exists for downloaded runtime bundles; retain it.

STRIDE mapping of the inspected boundaries: stale session attribution (spoofing), unsafe restore/replay (tampering), false verified success and fake termination (repudiation), shared guest secrets (information disclosure), unbounded socket/output handling (denial of service), and unrestricted cross-engine tool access (privilege-boundary gap). No unrelated host compromise is asserted.

## Additional feature and lifecycle risks

- **G01 — Codex progressive text missing, VERIFIED — reproduced.** The same `agent_message` item is `Ignored` for `item.updated` but becomes `AgentMessage(text=hello)` at `item.completed`; reasoning behaves similarly. This can make long answers appear silent. [Parser](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/CodexProtocol.kt:105). Smallest improvement: track per-item text and emit only new suffixes; regression: updates plus completion render exactly once. Real CLI emission cadence is **NOT RUN**.
- **G02 — Capability declarations stale, VERIFIED — source.** DSH advertises native resume/interactive approvals while its implementation uses fresh SDK sessions/full access. Codex does not advertise account login or effort despite implemented controllers. Claude's effort flag is absent. Search found no current consumers of these flags, so this is dormant metadata risk rather than proof of a visible broken screen. [Registry](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/AgentDriver.kt:51). Correct flags and test them against each bridge's supported contract.
- **G03 — Limits/telemetry incomplete.** DSH lacks per-session token events; Claude's usage report depends on a prior rate-limit event; Antigravity's usage panel reports the primary account rather than necessarily the account used for the last turn. Codex has native token/rate-limit mapping. Expose unavailable/stale/account-specific values explicitly rather than estimating usage as verified data.
- **G04 — Claude fixed turn budget.** `--max-turns 25` is hard-coded with no per-task budget UI in the command builder. [Builder](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/ClaudeRuntimeBridge.kt:1147). Long-task budget behavior is **NOT RUN**. Make limits visible/configurable only if product scope requires it.
- **G05 — Agent-server Preview discovery absent.** The event type/consumer exist without a bridge emitter. Terminal command/output URL discovery and a manual loopback address field are implemented. [Terminal discovery](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt:822), [preview event consumer](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt:5145). Add bounded readiness detection to existing agent tool events; do not mark ready solely from a guessed port.
- **R01 — Startup reconciliation race, source-level hypothesis.** Singleton initialization starts reconciliation asynchronously; it queries all currently active records rather than a startup snapshot and releases wake locks globally. No explicit task-start barrier is present in that path. A newly started task could race reconciliation. [Initialization](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/task/TaskSupervisor.kt:137), [reconciliation](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/task/TaskSupervisor.kt:165). **NOT RUN:** controlled startup interleaving/process-death device test. Establish a barrier/startup epoch before claiming a fix.
- **R02 — Graceful timeout can remain blocked, source-level risk.** Antigravity watchdog calls graceful destroy then `waitFor()`; DSH SDK shutdown repeatedly requests destroy without a clear force-kill deadline in that loop. Native `waitFor` and exit checks synchronize on the same object. A process ignoring the initial signal may prevent progress. [Antigravity wait](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/AntigravityRuntimeBridge.kt:679), [DSH shutdown](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/DshRuntimeBridge.kt:386), [native waiting](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/NativeSpawnProcess.kt:30). **NOT RUN:** signal-resistant process fixture on Android.

## Existing behavior that should be retained

- Codex installation now packages/stages both `codex` and `codex-code-mode-host`, verifies the archive, and checks executability. The earlier missing-helper incident is not an unfixed source finding at this revision. [Install layout](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/CodexInstall.kt:32), [installer](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt:590). Actual fresh repair/install was **NOT RUN**.
- Terminal/Preview backdrop sources and grouped glass fallback are present. The overlay uses live anchor bounds and IME/navigation insets. [Backdrop](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/WorkspaceScreen.kt:935), [glass fallback](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/theme/glass/GlassGroup.kt:72), [live anchor](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/kit/Overlay.kt:605), [IME insets](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/ui/kit/Overlay.kt:651). Source regression tests pass; appearance/keyboard interaction remain device checks.
- Shared durable task state, execution locks, recovery/checkpoint ownership, cancellation markers, bounded output buffers, and runtime-health snapshots exist. They should be repaired at the cited boundaries, not replaced wholesale. Task ID and session ID remain distinct.
- Wake locks renew every five minutes against a fifteen-minute timeout; the historical fixed-duration wake-lock criticism is outdated. [Renewal](/workspace/bright-kalam/app/src/main/java/com/jarves/mh/runtime/task/WakeLockManager.kt:46). Its six Robolectric tests could not initialize on this phone.
- `/usage` and `/effort` are implemented; model selection is engine-aware. OpenAI Responses validation is implemented. Model-list normalization prioritizes the existing `/v1/models` path without appending another `/v1`. Historical claims that these features are wholly absent are not retained.
- API-key vault encryption, manifest backup restrictions, loopback Preview restrictions, verified runtime downloads, and APK update certificate checks provide existing protections.

## Verification and artifacts

Executed commands and results:

1. **FAILED test task / environment-blocked cases:** `gradle :app:testOnlineDebugUnitTest --tests 'com.jarves.mh.runtime.*' --tests 'com.jarves.mh.model.*' --tests 'com.jarves.mh.network.*' --tests 'com.jarves.mh.provider.*' --console=plain` — 718 tests, 707 passed, 11 failed during setup. Failures: six `WakeLockManagerTest` and five `CodexPreferencesTest`, all `UnsatisfiedLinkError: no conscrypt_openjdk_jni-linux-aarch_64 in java.library.path`. They did not establish product failures.
2. **VERIFIED:** `gradle :app:testOnlineDebugUnitTest --tests 'com.jarves.mh.data.*' --tests 'com.jarves.mh.ui.ChatLiquidGlassRegressionTest' --tests 'com.jarves.mh.ui.RuntimeSessionRoutingTest' --console=plain` — 157 tests passed. Actual suites were the data tests and 11 liquid-glass checks; `RuntimeSessionRoutingTest` is not an existing class and did not add coverage. The actual routing suite is run separately below.
3. **VERIFIED:** `java --class-path <compiled app classes + cached Kotlin stdlib + org.json> docs/AuditProbes.java` through a Python subprocess — five probes reproduced A01, A02, A10 pipe blockage, A15, G01. The probe cleans its own disposable fixtures and never targets real workspace content. [Probe source](/workspace/bright-kalam/docs/AuditProbes.java).
4. **VERIFIED:** `gradle assembleOnlineDebug --console=plain` — `BUILD SUCCESSFUL in 54s`; no dependency/build-tool changes. Native symbol stripping emitted warnings for prebuilt libraries; APK packaging completed.
5. **VERIFIED:** requested APK copies, `stat -c '%s %n' *.apk`, `md5sum *.apk`, `apksigner verify --print-certs app-online-debug.apk`, ZIP asset inspection, and Git ignore checks. Signature verification succeeded; signing configuration uses the project debug keystore. The keystore and APKs remain Git-ignored.

6. **VERIFIED:** `gradle :app:testOnlineDebugUnitTest --tests 'com.jarves.mh.ui.RuntimeRetrySessionRoutingTest' --console=plain` — 11 tests passed. These exercise the routing helper, not all side effects in the ViewModel; they do not disprove A05.

Across the three distinct test selections: **886 tests, 875 passed, 11 failed during environment setup, zero skipped**. The failing task is not reported as a passing suite. All five isolated probes completed with exit code zero, confirming the undesirable behaviors rather than fixing them. Raw logs are local `docs/harness-audit-*.log` files and are ignored by Git.

| APK | Bytes | MD5 |
| --- | ---: | --- |
| `app-online-debug.apk` | 71,831,610 | `18c5e8cee408ac64bd9cf4fc2ab181a1` |
| `mobile-harness-dev.apk` | 71,831,610 | `18c5e8cee408ac64bd9cf4fc2ab181a1` |

APK certificate SHA-256: `93a815c394ca54603371ee36f7617994bab304d4e5df8b37fe5a88c081ad154e`. This is a public certificate fingerprint, not a credential.

**NOT RUN:** live authenticated engine sessions; upstream CLI feature/protocol comparison; fresh install/offline variant; Android process-death/reconciliation/retry timing; child cancellation; provider interruption injection; Preview/keyboard/glass visual checks; accessibility screen-reader/font-scale testing; CPU/memory/battery profiling; complete UI screenshot suite; lint/full style checks; formal third-party native-library security review. No severity implies those checks ran.

## Recommended implementation order

1. Prevent destructive restore of omitted originals (A01); fix unconditional verified-success classification (A02).
2. Make child controls honest (A03), move completion to authoritative task outcome (A04), and gate all event effects by task/session/project (A05).
3. Repair Antigravity retry/change reconciliation (A06), protocol routing (A07/A15), and interrupted-stream outcome (A08); test Claude terminal-event semantics (A09).
4. Repair verifier execution/drain/cleanup and root mapping (A10/A11), bound capture and gateway resources (A12/S02), then address attachment/Preview/inspector/setup usability (A13/A14/A16/A17).
5. Add missing parity features against explicit supported CLI contracts; enforce packaging requirements and test credential boundaries.

No application fixes have been applied. Existing tests can pass while A01/A02 remain reproducible because those suites do not cover the omitted-original and generated-no-op verification cases. Add the specific regression guards above before each scoped fix.
