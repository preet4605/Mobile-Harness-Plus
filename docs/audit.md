# Mobile Harness — Full Codebase Audit

| | |
|---|---|
| Date | 2026-10-06 |
| Commit audited | `3e06a26` (`chore: checkpoint current work`) |
| Branch | `claude/code-audit-debug-5x7yn7` |
| Scope | `app/src/main` (~49k lines Kotlin/C/XML), `app/build.gradle.kts`, manifest, XML config, tracked files |
| Method | Static code reading and targeted `grep`. No build, no unit tests, no device run (see [Verification](#verification)). |

## Status legend

- **VERIFIED**: confirmed by reading the code path end to end.
- **PLAUSIBLE**: the code path exists, but whether it triggers depends on runtime or provider behaviour that was not observed.

Severity: **Critical**: core feature is unusable. **High**: data loss, a security boundary failure, or duplicate side effects. **Medium**: incorrect behaviour, resource or battery cost, or races. **Low**: hardening and hygiene.

---

## Executive summary

1. **The app cannot currently run any agent task.** The execution gate never authorizes a task (C1). Even if it did, the UI drops every runtime event (C2), and one press of Stop disables the runtime bridge for the rest of the process (C3).
2. **Undo is unsafe.** It deletes modified files larger than 25 MB (H3). It also loses track of edits made by runs that failed (H4).
3. **Retries re-run the whole agent with no idempotency guard.** Retries can multiply across three layers (H5).
4. **The trust boundary is weak.** Claude Code approves every tool call unconditionally (H2), and every stored OAuth credential sits inside the filesystem the agent can read (H6).
5. **Tests don't catch these failures.** Only the gate's own unit test calls `authorizeExecution`. No integration test covers the path from sending a prompt to starting a session (L15).

## Findings index

| ID | Severity | Status | Title |
|---|---|---|---|
| C1 | Critical | VERIFIED | Execution is never authorized, so every runtime refuses to start |
| C2 | Critical | VERIFIED | UI discards all runtime events and denies all approvals |
| C3 | Critical | VERIFIED | Stop flag is sticky, so every later session fails "Stopped by user" |
| H1 | High | VERIFIED | Boundary classifier routes ordinary coding requests to chat |
| H2 | High | VERIFIED | Claude Code tool approval is unconditional, and the approval pipeline is dead code |
| H3 | High | VERIFIED | Undo deletes modified files larger than 25 MB |
| H4 | High | VERIFIED | Edits from failed runs vanish from review/undo and confuse mutation detection |
| H5 | High | VERIFIED | Retries and multi-step plans re-run the full agent (duplicate side effects) |
| H6 | High | VERIFIED | All OAuth/CLI credentials are readable by the agent inside the runtime filesystem |
| H7 | High | PLAUSIBLE | Claude session is killed on any `end_turn` (including subagent messages) |
| H8 | High | VERIFIED | Revoked-provider cleanup overwrites every agent's provider profile |
| M1 | Medium | VERIFIED | Conversational replies use the wrong protocol and credentials |
| M2 | Medium | VERIFIED | Conversational prompt sent twice; reply appended to whatever chat is active |
| M3 | Medium | VERIFIED | `refreshActiveApiKey` reads the wrong vault id for multi-profile custom providers |
| M4 | Medium | VERIFIED | ViewModel treats `WORKSPACE_MUTATED_FAILURE` as retryable, but the supervisor does not |
| M5 | Medium | VERIFIED | `revokeExecutionAuthority` clears the wrong task's pending tool requests |
| M6 | Medium | VERIFIED | `/model` misroutes for DeepSeek Harness and reports a false success |
| M7 | Medium | VERIFIED | Wake lock silently expires after 15 minutes |
| M8 | Medium | VERIFIED | Runtime output logs are never deleted and are uncapped |
| M9 | Medium | VERIFIED | Substring heuristics kill sessions and decide retry policy |
| M10 | Medium | VERIFIED | Claude Code hard-capped at `--max-turns 25` |
| M11 | Medium | VERIFIED | Antigravity gateway reports interrupted streams as successful completion |
| M12 | Medium | VERIFIED | Local format gateway ignores protocol, forces non-streaming, has no socket timeout |
| M13 | Medium | PLAUSIBLE | Gateways leak when sessions overlap |
| M14 | Medium | PLAUSIBLE | Gating UI events on live authority races with task finalization |
| M15 | Medium | VERIFIED | `sendPrompt` performs database and file I/O on the main thread |
| M16 | Medium | VERIFIED | Checkpointing copies and hashes the full workspace two to three times per step |
| M17 | Medium | PLAUSIBLE | Asynchronous startup reconciliation can kill a freshly started task |
| M18 | Medium | VERIFIED | Android SQLite driver binds NULL as `""` (production and tests diverge) |
| M19 | Medium | VERIFIED | OpenAI Responses request and response shape is wrong |
| L1–L16 | Low | VERIFIED | Hardening, hygiene, and policy items (see [Low](#low)) |

---

## Critical

### C1: Execution is never authorized, so every runtime refuses to start
- **Location:** `runtime/ClaudeRuntimeBridge.kt:185`, `runtime/DshRuntimeBridge.kt:79`, `runtime/AntigravityRuntimeBridge.kt:491`; gate at `runtime/boundary/ExecutionBoundaryGate.kt`.
- **Problem:** `startSession` requires `ExecutionBoundaryGate.isExecutionAuthorized(taskId)`, but nothing in `app/src/main` ever calls `authorizeExecution`. The only caller is `ExecutionBoundaryGate`'s own unit test. Gate state is also memory-only, so it is lost on process death.
- **Failure scenario:** A user sends "fix the bug in MainActivity", and the gate classifies it as EXECUTION. `TaskSupervisor.executeTask` then calls `startSession`, which emits "Execution not authorized for task …" and throws `SecurityException`. No agent run can start on any runtime, and recovery after process death fails the same way.
- **Fix direction:** Call `authorizeExecution(taskId)` in `MainViewModel.sendPrompt` right after `createTask` for EXECUTION decisions. Re-authorize on recovery or resume. Add an integration test covering send, then `executeTask`, then `startSession`.

### C2: UI discards all runtime events and denies all approvals
- **Location:** `ui/MainViewModel.kt:4608` (`onRuntimeEvent`), `ui/MainViewModel.kt:~4386` (`answerApproval`).
- **Problem:** Events are dropped unless `isEventPermitted(sessionId)` is true. That requires `activeExecutionContext`, which only `authorizeExecution` sets, and per C1 nothing calls it.
- **Failure scenario:** `SessionStarted`, `AssistantDelta`, `ToolRequested`, `SessionCompleted`, and `SessionFailed` never reach `_state`. The "not authorized" failure event is dropped too, so `isRunning`, the transcript, and approvals never update. Every approval is turned into a deny.
- **Fix direction:** Fix C1, and filter UI events by `sessionId == activeSessionId` instead of live authority (see M14).

### C3: Stop flag is sticky, so every later session fails "Stopped by user"
- **Location:** `runtime/ClaudeRuntimeBridge.kt:197` (reset at :216), `runtime/DshRuntimeBridge.kt:101` (reset at :120), `runtime/AntigravityRuntimeBridge.kt:502` (reset at :521). Introduced in `3e06a26`.
- **Problem:** `if (isTaskCancelled || userStopRequested) { userStopRequested = true; …; throw }` runs before `userStopRequested = false`. Once the flag is true, every subsequent `startSession` on that bridge instance re-sets it and throws. Bridges are singletons per `MainViewModel` (`MainViewModel.kt:345-348`).
- **Failure scenario:** The user presses Stop, either in the app (`stopSession` / `stopActiveSession`) or via the notification's "Stop task" button (`RuntimeTaskController.stopAction`). Every following prompt fails immediately with "Stopped by user" until the app process or the ViewModel is recreated. `stopActiveSession` with no active session also sets the flag. C1 currently masks this bug.
- **Fix direction:** Scope stop state to the session or task (`stoppedTaskIds` / a per-session flag), and reset it at the start of each new session before the cancellation check.

---

## High

### H1: Boundary classifier routes ordinary coding requests to chat
- **Location:** `runtime/boundary/ExecutionBoundaryGate.kt:126` (fail-safe default) and `EXECUTION_ACTION_PATTERNS`; caller `ui/MainViewModel.kt:4155`.
- **Problem:** Anything that doesn't match a narrow regex list becomes CONVERSATION. For example, `build` must be followed directly by `(the)? (apk|binary|app|project|code)`. Skill invocations (`parsedSkill`) aren't treated as execution intent.
- **Failure scenario:** Prompts such as "Build a todo app", "add a dark mode toggle", "make the button blue", "write a README", and "refactor LoginViewModel" go to `handleConversationalTurn`. No files change.
- **Fix direction:** Invert the default: run EXECUTION unless the message is clearly conversational. Alternatively, add an explicit execute/chat toggle in the UI, and count skills as execution.

### H2: Claude Code tool approval is unconditional, and the approval pipeline is dead code
- **Location:** `runtime/RuntimeInstaller.kt:1636` (hook), settings at :1640-1663; `runtime/ClaudeRuntimeBridge.kt:584` (`watchPermissionRequests`), `respondToApproval` (~:460).
- **Problem:**
  - The `PermissionRequest` hook always prints `{"behavior":"allow"}`.
  - Settings use `defaultMode: acceptEdits` and allow `Bash`, `Edit`, `Write`, `NotebookEdit`, `Read`, `Glob`, and `Grep`. As a result, no `.request` file is ever written.
  - `pending` is never populated, so `respondToApproval` returns immediately.
  - The gate's `isToolExecutionAllowed` is never consulted for Claude Code. The watcher also never deletes processed `.request` files, so it would re-emit events every 250 ms if it were ever used.
- **Failure scenario:** The documented guarantees "tool requests are rejected in conversation mode / after terminal transition" don't hold for Claude Code. Every Bash command runs immediately.
- **Fix direction:** Pick one approach.
  - (a) Declare auto-approve as intentional, document it, and delete the dead approval path.
  - (b) Make the hook write `<id>.request` to `/pocket-bridge` and block on `<id>.response`, then delete both files after reading.

### H3: Undo deletes modified files larger than 25 MB
- **Location:** `runtime/ClaudeRuntimeBridge.kt:532` (`undoLastChanges`), `:561` (`undoFileChange`); `runtime/WorkspaceCheckpoints.kt` `restoreCheckpoint` step 2; `MAX_CHECKPOINT_COPY_BYTES = 25 MB` (`WorkspaceCheckpoints.kt:648`).
- **Problem:** `createCheckpoint` skips files larger than 25 MB. However, `snapshot()` and `changedFiles()` still report them as changed. On undo, `original.isFile` is false, so the code calls `target.delete()`.
- **Failure scenario:** The agent edits an existing 30 MB SQLite database, dataset, or media asset. When the user taps Undo, the file is deleted entirely.
- **Fix direction:** Record which paths were not backed up. Refuse to undo those paths (and tell the user), or back them up.

### H4: Edits from failed runs vanish from review/undo and confuse mutation detection
- **Location:** `saveChangedPaths` is only reached on success: `ClaudeRuntimeBridge.kt:397`, `DshRuntimeBridge.kt:231`, `AntigravityRuntimeBridge.kt:693`. The checkpoint baseline rule is at `WorkspaceCheckpoints.kt:86`. Mutation checks are at `MainViewModel.kt:4315` and the `TaskSupervisor` outer catch (`readChangedPaths`).
- **Problem:** When a run throws (provider error, crash, or detector kill) after editing files, `changes.json` is never written. The next `createCheckpoint` keeps the old baseline only if `changes.json` exists. Otherwise it re-snapshots the already-mutated workspace as the new baseline.
- **Failure scenario:**
  - (1) A run fails mid-task after writing three files. Those edits never appear in "pending changes" and can't be undone.
  - (2) The ViewModel and outer supervisor see `readChangedPaths` as empty and auto-retry on a dirty workspace.
  - (3) Conversely, stale unaccepted changes from an older run make an unrelated, clean failure look mutated, which blocks a legitimate retry.
- **Fix direction:** Compute `changedFiles(workspace, before)` and persist it in a `finally` block on every exit path. For mutation detection, compare against the current attempt's own snapshot, not the shared `changes.json`.

### H5: Retries and multi-step plans re-run the full agent (duplicate side effects)
- **Location:** `runtime/AntigravityRuntimeBridge.kt:540`, `:711-745`; `runtime/task/TaskSupervisor.kt:1534`; the execution block in `ui/MainViewModel.kt` (`supervisor.executeTask { … startSession(request.prompt …) }`).
- **Problem:**
  - The Antigravity bridge re-runs the entire turn on account failover (quota or auth) and on network errors (up to 2 times). It doesn't check whether the failed turn already modified the workspace, and doesn't restore it.
  - This multiplies with `TaskSupervisor` step retries (`maxAttempts`) and outer attempts (`maxRetries`).
  - Separately, `executeTaskInternal` calls `executionBlock(current)` once per plan step, while the ViewModel's block always sends the full original prompt. `DefaultTaskDecomposer` creates N steps when the prompt contains acceptance criteria and a numbered list.
- **Failure scenario:** `git push`, package installs, migrations, or file writes run several times. A numbered-list prompt with criteria executes the whole task N times, at N times the token cost.
- **Fix direction:**
  - Before any re-run, check for workspace mutation and either restore the checkpoint or stop.
  - Pass the step objective to the agent (use the `stepExecutionBlock` overload), or don't decompose prompts that the ViewModel executes as a single unit.
  - Cap the total number of attempts across all layers.

### H6: All OAuth/CLI credentials are readable by the agent inside the runtime filesystem
- **Location:** `runtime/AntigravityAccountManager.kt:97` (`/root/.antigravity-accounts/<id>/.gemini/antigravity-cli/antigravity-oauth-token` inside the rootfs). The GitHub CLI credential and Claude credentials also live under `/root`. Rules are injected via `SkillManager.discoverRules` (project `AGENTS.md`/`CLAUDE.md`/`.claude/rules`).
- **Problem:**
  - Refresh tokens for every Antigravity account are stored in plaintext inside the filesystem that agents run in.
  - Agent Bash is auto-approved (H2), and repo-supplied rule files are injected into the prompt automatically.
- **Failure scenario:** A cloned malicious repo's `AGENTS.md` instructs the agent to `cat /root/.antigravity-accounts/*/…/antigravity-oauth-token` and exfiltrate the tokens. This works even when the user is running a non-Antigravity provider. The loopback gateway's bearer secret adds nothing, because the agent can read the upstream credentials directly.
- **Fix direction:** Keep non-active account homes outside the PRoot bind (store them in app-private storage, or bind-mount only the active account for an Antigravity session). Treat injected repository rules as untrusted (show them to the user and require opt-in).

### H7: Claude session is killed on any `end_turn` (including subagent messages) (PLAUSIBLE)
- **Location:** `runtime/ClaudeRuntimeBridge.kt:747`.
- **Problem:** Any `assistant` message with `stop_reason == "end_turn"` triggers `emitCompletedOnce` and `terminateActiveProcessGracefully()`. Messages carrying `parent_tool_use_id` (subagent/Task output) are not filtered out. AGENTS.md explicitly forbids treating `end_turn` as session termination.
- **Failure scenario:** A subagent's final message carries `end_turn`, so the main Claude Code process is killed mid-task and the run is reported as completed.
- **Fix direction:** Treat `result` as authoritative. Ignore messages with `parent_tool_use_id`. Keep the `end_turn` fallback only for providers known to omit `result`, and only after the output has been idle.

### H8: Revoked-provider cleanup overwrites every agent's provider profile
- **Location:** `ui/MainViewModel.kt:572` (init), `ui/MainViewModel.kt:1209` (`refreshActiveApiKey`).
- **Problem:** The cleaned profile of the current agent, including its `kind`, is saved into every `AgentKind` slot (`AgentKind.entries.forEach { saveProvider(cleaned, agent) }`).
- **Failure scenario:** The active agent is Claude Code (`ProviderKind.CLAUDE`). Any pref contains a revoked marker and there is no CUSTOM key. The DeepSeek Harness and Antigravity profiles are then overwritten as CLAUDE with default URL/model and `hasSecret = false`.
- **Fix direction:** Only reset profiles that actually reference the revoked provider, each in its own agent slot.

---

## Medium

### M1: Conversational replies use the wrong protocol and credentials
- **Location:** `runtime/boundary/ConversationalResponder.kt:29-33`.
- **Problem:** Always resolves the protocol for `AgentKind.CLAUDE_CODE` and uses `provider.baseUrl`, `provider.model`, and the vault key.
- **Failure scenario:** The default Claude native-subscription provider has an empty `baseUrl`, so the call is skipped and every chat turn returns the canned text "I can answer questions and explain results in conversation mode." DeepSeek Harness providers resolve to the wrong endpoint shape.
- **Fix direction:** Resolve the protocol for the active agent, and handle native subscription explicitly (route via the CLI, or disable conversation mode with a message).

### M2: Conversational prompt sent twice; reply appended to whatever chat is active
- **Location:** `runtime/boundary/ConversationalResponder.kt:40`; `ui/MainViewModel.kt:4350-4366`.
- **Problem:** `currentHistory` already contains the user message, and `respond()` appends `ChatMessage(prompt)` again. The reply is added to `current.messages` with no project or chat check.
- **Failure scenario:** The provider receives `[…, user:"why did it fail?", user:"why did it fail?"]`. If the user switches project while the reply is pending, the reply is persisted into the other project's transcript.
- **Fix direction:** Pass the history without the last message, or don't append. Capture `projectId`/`chatId` before the call and drop or redirect the reply if they changed.

### M3: `refreshActiveApiKey` reads the wrong vault id for multi-profile custom providers
- **Location:** `ui/MainViewModel.kt:1185` (also `:1156`).
- **Problem:** Uses `vault.list(kind.name)` (`"CUSTOM"`), while multi-profile custom providers store keys under `profile.secretId` (`custom:<id>`).
- **Failure scenario:** The selected profile `custom:abc` has keys, and the user deletes the last legacy `CUSTOM` key. `shouldReset` fires: baseUrl and model are reset, `profileId` is cleared, and the change is saved for every agent.
- **Fix direction:** Use `currentProvider.secretId` consistently.

### M4: ViewModel treats `WORKSPACE_MUTATED_FAILURE` as retryable, but the supervisor does not
- **Location:** `ui/MainViewModel.kt:4323` vs `TaskSupervisor` outer catch (`WORKSPACE_MUTATED_FAILURE`, which leads to FAILED with `recoveryRequired`).
- **Failure scenario:** The agent edits files, then crashes. The ViewModel computes `willRetry = true` and skips its failure UI update, while the supervisor marks the task FAILED. The chat stays "running" with no failure message.
- **Fix direction:** Derive `willRetry` from the supervisor's decision (a single source of truth) and don't duplicate the classification.

### M5: `revokeExecutionAuthority` clears the wrong task's pending tool requests
- **Location:** `runtime/boundary/ExecutionBoundaryGate.kt:173`.
- **Problem:** Filters pending requests by `activeExecutionContext.sessionId`, not by the revoked task's own session.
- **Failure scenario:** Task A is revoked while B is active, so B's pending requests are removed and A's survive. With no active context, nothing is cleared.
- **Fix direction:** Look up the revoked task's session from `authorizedExecutionTasks[taskId]` before removing it.

### M6: `/model` misroutes for DeepSeek Harness and reports a false success
- **Location:** `ui/MainViewModel.kt:3798`, `:3806`; `setClaudeModel` at `:1839-1840`.
- **Problem:** Any non-Claude agent calls `setAntigravityModel`, including DeepSeek Harness. `setClaudeModel` silently returns for unknown ids, but "Switched Claude model to …" is still posted.
- **Failure scenario:** On DeepSeek Harness, `/model deepseek-v4-pro` changes the Antigravity model pref instead. On Claude, `/model claude-sonnet-4-6` is rejected but reported as switched.
- **Fix direction:** Dispatch by `agentKind`, and have the setters return a success value that the message depends on.

### M7: Wake lock silently expires after 15 minutes
- **Location:** `runtime/task/WakeLockManager.kt:39`, `acquire` at `:42`.
- **Problem:** The wake lock is acquired with a 15-minute timeout. `acquire()` is a no-op while the lock is held, and nothing re-acquires it. The KDoc claims it is "refreshed while active events flow".
- **Failure scenario:** A Gradle build longer than 15 minutes with the screen off loses the CPU wake lock, and the build stalls or is throttled.
- **Fix direction:** Refresh on output or heartbeat (release and re-acquire with a new timeout), throttled.

### M8: Runtime output logs are never deleted and are uncapped
- **Location:** `runtime/RuntimeInstaller.kt:1547` (`cacheDir/runtime-output-<nanos>.log`); Claude and DSH bridges never delete them. The 5 MB cap in `NativeSpawnProcess.start` only applies to PTY mode, because non-PTY output is written by the child directly to the fd.
- **Failure scenario:** `cacheDir` grows with every session. Each file holds the full transcript, including tool output, which may contain secrets.
- **Fix direction:** Delete the file in the bridge's `finally`, cap its size in non-PTY mode, and sweep stale logs at startup.

### M9: Substring heuristics kill sessions and decide retry policy
- **Location:** `runtime/ClaudeRuntimeBridge.kt:92` (`ProviderRuntimeErrorDetector` applied to every non-JSON line; stderr shares the file); `runtime/task/TaskSupervisor.kt:822+` (`classifyError`).
- **Failure scenario:** Any stderr warning containing "isn't available", "model not found", "does not have access", or "http 401 … error" calls `destroyForcibly()` and fails the run. In `classifyError`, errors containing "unreachable", "timed out", "system fault", or "api key" are routed to retry, permanent failure, or drift purely by substring.
- **Fix direction:** Classify only structured events (JSON `result`/`api_retry`/HTTP status). Treat plain text as diagnostic only.

### M10: Claude Code hard-capped at `--max-turns 25`
- **Location:** `runtime/ClaudeRuntimeBridge.kt:1171`.
- **Failure scenario:** Long tasks stop at 25 turns. The outcome surfaces either as a generic "Claude Code reported an error" or as completion with partial work.
- **Fix direction:** Make the cap configurable or remove it, and surface `error_max_turns` explicitly.

### M11: Antigravity gateway reports interrupted streams as successful completion
- **Location:** `runtime/AntigravityGatewayServer.kt:459` (catch) then `:467`.
- **Problem:** When the upstream stream fails, the server logs a warning and still emits `message_delta(end_turn)` + `message_stop` (or `finish_reason: stop`).
- **Failure scenario:** A truncated answer is treated as complete by Claude Code or DSH.
- **Fix direction:** Emit an Anthropic `error` event (or an OpenAI error chunk) and close the stream without a stop event.

### M12: Local format gateway ignores protocol, forces non-streaming, has no socket timeout
- **Location:** `runtime/LocalFormatGateway.kt:198` (always `/chat/completions`), `:112` (`stream: false`), `:203` (`readTimeout = 180_000`); accept loop spawns a thread per connection with no `soTimeout`.
- **Failure scenario:** `OPENCODE_ZEN` is declared `OPENAI_RESPONSES` but is called on chat completions. Long generations time out after 180 s. An idle client pins a thread forever.
- **Fix direction:** Honour the protocol (Responses vs Chat), stream through, set `soTimeout`, and use a bounded executor.

### M13: Gateways leak when sessions overlap (PLAUSIBLE)
- **Location:** `runtime/ClaudeRuntimeBridge.kt:438`, `:449`; `runtime/DshRuntimeBridge.kt:263`, `:273`.
- **Problem:** `formatGateway` and `antigravityGateway` are closed only when `activeSessionId == sessionId`.
- **Failure scenario:** A new session (for example a retry) starts before the previous one's cleanup. The old `ServerSocket`, its accept thread, and the 16-thread request pool stay alive.
- **Fix direction:** Close gateways in a `finally` owned by the session that created them.

### M14: Gating UI events on live authority races with task finalization (PLAUSIBLE)
- **Location:** `ui/MainViewModel.kt:4608`; revocation in `TaskSupervisor.finalizeTask` (`:994`).
- **Problem:** `finalizeTask` revokes authority while `SessionCompleted` / `FilesChanged` may still be buffered in the `SharedFlow` (64-slot buffer, collected asynchronously).
- **Failure scenario:** The final events are dropped and the UI stays "running". This holds even after C1 is fixed.
- **Fix direction:** Filter by session identity (`activeSessionId`), not by current authority.

### M15: `sendPrompt` performs database and file I/O on the main thread
- **Location:** `ui/MainViewModel.kt:4144-4250`.
- **Problem:** `TaskSupervisor.getInstance` (lazy DB open), `getTasksForProject`, and `createTask` (SQLite writes, task decomposition, `.git/HEAD` and `packed-refs` reads) all run on the UI thread.
- **Failure scenario:** Jank or ANR on slow storage or large repositories.
- **Fix direction:** Move these calls into `viewModelScope.launch(Dispatchers.IO)` and update state from there.

### M16: Checkpointing copies and hashes the full workspace two to three times per step
- **Location:** `TaskSupervisor` task-baseline and per-step `createCheckpoint`; the bridges' default `createCheckpoint` (`ClaudeRuntimeBridge.kt:278`, `DshRuntimeBridge.kt:174`, `AntigravityRuntimeBridge.kt:585`); two full SHA-256 `snapshot()` passes per session.
- **Failure scenario:** For a mid-size repository, every step copies the workspace (files up to 25 MB each) several times. This costs significant I/O, storage, and battery.
- **Fix direction:** Take one snapshot per step and share it across the supervisor and bridge. Use hard links or copy-on-write where possible, and hash lazily (size + mtime first).

### M17: Asynchronous startup reconciliation can kill a freshly started task (PLAUSIBLE)
- **Location:** `runtime/task/TaskSupervisor.kt:145` (`supervisorScope.launch { reconcileOnStartup() }`).
- **Failure scenario:** A task created in the reconciliation window is read as "active". Its live PRoot PID passes `isVerifiedExpectedProcess`, so it is killed with SIGKILL and marked ABANDONED.
- **Fix direction:** Complete reconciliation before `executeTask` is allowed, or only reconcile tasks whose `updatedAt` predates the process start.

### M18: Android SQLite driver binds NULL as `""` (production and tests diverge)
- **Location:** `data/BrainDatabaseDriver.kt:29`.
- **Problem:** `query()` maps `null` to `""` and stringifies every argument. `JdbcSqliteDriver` (used in tests) binds real NULLs and typed values.
- **Failure scenario:** `WHERE col = ?` with a null argument behaves differently on device than in tests, so tests can pass while production misbehaves.
- **Fix direction:** Use `SQLiteDatabase.rawQueryWithFactory`/`SQLiteStatement` with typed binds, or reject null query arguments explicitly.

### M19: OpenAI Responses request and response shape is wrong
- **Location:** `network/ProviderApiClient.kt:204`, `:235`.
- **Problem:** Assistant history items are sent as `input_text`, but the API requires `output_text` for assistant content. The reply parser reads only `output[0].content[0]`, which is empty when the first output item is a reasoning item.
- **Failure scenario:** Responses-protocol providers reject multi-turn history, or return replies that parse as empty.
- **Fix direction:** Map roles to the correct content type, and scan `output[]` for the first `message` item.

---

## Low

| ID | Location | Issue | Fix direction |
|---|---|---|---|
| L1 | `data/AppPreferences.kt:297-380` (`loadProvider`) | Forces any non-alias CLAUDE model to `"default"` (`:377`). Marks native Claude/Antigravity `hasSecret = false` and resets URL/model whenever any revoked value appears anywhere in prefs. Scans and purges all prefs on every call. | Run the migration once (versioned), and scope it to affected profiles. |
| L2 | `model/Models.kt:152` | `defaultDshApiForProvider(CUSTOM)` silently changed to `openai-completions`. Existing Anthropic-compatible custom endpoints may break. | Migrate only new profiles, or keep the previous default. |
| L3 | `res/xml/file_paths.xml:5` | FileProvider exposes all of `filesDir` (`path="."`) and `cacheDir`, which contain the rootfs and credentials. Grants are explicit, but the scope is excessive. | Limit to `updates/` and the APK output directory. |
| L4 | `runtime/RuntimeInstaller.kt:1715-1735` (and :1758, :1800) | Tar extraction: `safeChild` checks only the parent's canonical path. A symlink entry followed by a file entry with the same name writes outside the root. Bundles are SHA-pinned, so this requires a compromised build. | Delete the existing target before writing, and refuse to write through symlinks. |
| L5 | `runtime/AntigravityAccountManager.kt:558`, gateway `User-Agent` | Hard-coded Google OAuth client secret, with requests impersonating `AntigravityCLI/1.1.27` against `daily-cloudcode-pa.googleapis.com`. Installed-app secrets aren't confidential, but this is a ToS, account-ban, and silent-breakage risk. | Document the risk; prefer an official client flow. |
| L6 | `runtime/AntigravityAccountManager.kt:222` | `getAccountAccessToken` is `@Synchronized` and performs a blocking HTTP refresh (up to 25 s) under the lock. All gateway request threads serialize behind it. | Refresh outside the lock (single-flight per account). |
| L7 | `app/build.gradle.kts:173` | `externalNativeBuild` is commented out and prebuilt `jniLibs/arm64-v8a/*.so` files are checked in. Sources (`app/src/main/cpp`) and binaries can drift, and the build isn't reproducible (F-Droid will object). | Build from source in CI, or record source hashes next to the binaries. |
| L8 | `app/build.gradle.kts:145` | Debug builds embed `openrouter.apiKey` from `test-secrets.properties` into `BuildConfig`. AGENTS.md copies debug APKs to the repo root (they're gitignored), so anyone with a shared debug APK can extract the key. | Inject at test runtime only. |
| L9 | `runtime/task/ProcessSupervisor.kt:107` | `isVerifiedExpectedProcess` matches the cmdline against substrings (`node`, `python`, …) before a process-group SIGKILL. Same UID only. | Persist and compare process start time (`/proc/<pid>/stat` field 22). |
| L10 | `runtime/NativeSpawnProcess.kt` | `waitFor()` and `exitValue()` (used by `isAlive()`) are both `@Synchronized`. A thread blocked in `waitFor` stalls all `isAlive` callers until the process exits. Latent today. | Use a non-blocking `WNOHANG` check outside the monitor. |
| L11 | `data/AppPreferences.kt:632` | `index.json` is written with `writeText` (non-atomic); message files already use temp-file + rename. | Use the same atomic write. |
| L12 | `data/ApiKeyVault.kt:114`, `:149` | `purgeRevokedProviders` and `purgeTokenHarbor` are near-identical copies. | Collapse into one function that takes a predicate. |
| L13 | `runtime/ClaudeRuntimeBridge.kt:177` | `bindSession` (which persists `sessionId`) runs before the authorization check, so denied sessions are still bound to the task record. | Check authorization first. |
| L14 | `runtime/ClaudeRuntimeBridge.kt:584` | `watchPermissionRequests` polls the directory every 250 ms and never deletes processed `.request` files (see H2). | Delete or rename after responding, or remove the watcher. |
| L15 | `app/src/test` | No integration test covers send → `executeTask` → `startSession` → UI events. Only `ExecutionBoundaryGateTest` calls `authorizeExecution`, which is why C1/C2 shipped. | Add an end-to-end test with a fake runtime bridge. |
| L16 | `runtime/AntigravityGatewayServer.kt` (401/403 branch) | After a forced token refresh, a retry that fails with 429 or 5xx is discarded, and the account is marked as an auth error anyway. | Classify the retry result the same way as the first attempt. |

---

## Recommended fix order

1. **C1 + C2 + C3:** wire up `authorizeExecution`, filter UI events by session, and scope the stop flag per session. Until these are fixed, no task runs.
2. **H3 + H4:** undo data loss and tracking of failed-run edits.
3. **H5:** retry idempotency (mutation check or restore before any re-run; no full-prompt re-run per step).
4. **H1:** invert the boundary classifier default (or add an explicit execute/chat toggle).
5. **H2 + H6:** decide the Claude approval model, and move non-active credentials out of the agent-visible filesystem.
6. **H8, M3, L1:** provider-profile cleanup that corrupts settings.
7. **M7 + M8 + M16:** wake lock refresh, log cleanup, checkpoint cost.
8. Remaining Medium and Low items.

## Verification

| Check | Result |
|---|---|
| Static review of runtime bridges, gateways, supervisor/locks/wake lock/foreground service, checkpoints, vault, account manager, installer download/extract, updater, DB driver/migrations/state store, boundary gate, ViewModel send/event/approval paths, manifest/XML/Gradle | VERIFIED (code read; line references gathered with `grep`) |
| Secret scan of tracked files (`git grep` for key patterns) | VERIFIED: only the public OAuth client secret (L5) and test fixtures |
| `./gradlew test` / `assembleOnlineDebug` | BLOCKED: no Android SDK in the audit container (`ANDROID_HOME` unset, no `local.properties`) |
| Device / runtime behaviour | NOT RUN |

## Not covered or only partially reviewed

- **Compose UI:** `PocketDevApp.kt` (6.3k lines), `AgentScreen.kt`, `CliParityComponents.kt`, the settings screens, and the `theme/glass/*` code were skimmed only, for threading hazards.
- **Brain/memory subsystem:** `BrainContextAssembler`, `BrainLearningService`, `BrainKnowledgeRepository`, and the `Memory*` classes were not reviewed in depth.
- **Recovery and verification:** `RecoveryEngine` and `StepVerifier` were partially reviewed (command runner and confinement only).
- **Native and third-party code:** native C launchers beyond `pocket_spawn.c`, `third_party/proot`, and the shell scripts under `scripts/` were not reviewed.
