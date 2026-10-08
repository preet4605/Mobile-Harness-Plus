# Harness parity audit, 2026-10-08

Base: `harness-dev` at `2bcbc92` (Codex is the fourth harness). Scope: Claude Code, DeepSeek Harness (DSH), Antigravity (AG), Codex.
Legend: ✅ supported · ◐ partial · ✖ missing · N/A not applicable by design. Evidence is `file:line` on `2bcbc92` unless noted.

## 1. Feature matrix

| Feature | Claude | DeepSeek | Antigravity | Codex | Evidence / note |
|---|---|---|---|---|---|
| Streamed assistant text | ✅ | ✅ | ✅ | ◐ | Codex emits `agent_message` only on `item.completed` (`CodexProtocol.kt` `classifyItem`), so text arrives per item, not per token |
| Reasoning summary | ✅ | ✅ | ✖ | ✅ | AG event parser has no thinking event (`AntigravityRuntimeBridge.kt` event loop ~618–640); AG CLI stream not captured |
| Tool activity rows | ✅ | ✅ | ✅ | ✅ | `ToolStarted` / `ToolCompleted` emitters in each bridge |
| Interactive tool approvals | ✖ auto-allow | ✖ no channel | ✖ skip-permissions | ✖ `approval_policy=never` | `ClaudeRuntimeBridge.kt:569-596` writes `allow`; `DshRuntimeBridge.kt:423`; `AntigravityRuntimeBridge.kt:758`; `CodexLaunch.kt:136`. `RuntimeEvent.ToolRequested` is never constructed |
| Pending changes, undo and accept (all and per file) | ✅ | ✅ | ✅ | ✅ | `FilesChanged` emitters: `ClaudeRuntimeBridge.kt:390`, `DshRuntimeBridge.kt:223`, `AntigravityRuntimeBridge.kt:685`, `CodexRuntimeBridge.kt:197`; undo/accept in each bridge |
| Stop (graceful and force) | ✅ | ✅ | ✅ | ✅ | `stopSession` / `stopActiveSession` in each bridge |
| Token usage (`/cost`) | ✅ | ✖ | ✅ | ✅ | DSH parses no usage events (`DshRuntimeBridge.kt`); whether the DSH CLI emits usage is unverified |
| Subagents | ✅ | ✖ | ✅ | ✖ | Claude `:751`, `:892`; AG `:627`; Codex mapper ignores all item types except message, reasoning, command, file, MCP and web search |
| Background tasks | ✅ | ✖ | ✅ | ✖ | Claude `:738`, `:858`; AG `:628` |
| Artifacts | ✅ | ✖ | ✅ | ✖ | Claude `:876`; AG `:629` |
| Timers | ✖ | ✖ | ✅ | ✖ | AG `:630` only |
| Preview | ✖ | ✖ | ✖ | ✖ | `RuntimeEvent.PreviewStarted` has no emitter in any harness |
| Conversation continuity | ✅ replay | ✅ replay | ✅ native id, replay on failover | ✅ replay | `ClaudeRuntimeBridge.kt:289`, `DshRuntimeBridge.kt:180`, `CodexRuntimeBridge.kt:145`; AG saves conversation id at `:618` |
| Native session resume (`RESUME`) | ✖ | ✖ | ◐ reuses conversation id | ✖ | `AgentDriver.kt` declares `RESUME` for Claude, DSH and AG, but no bridge resumes a CLI session. Codex runs with `--ephemeral` |
| Model switch: `/model` and picker | ✅ fixed | ✅ fixed | ✅ | ✅ fixed | Before: the picker and `/model` on non-AG harnesses wrote the Antigravity model (`PocketDevApp.kt` `onSelectModel`; `MainViewModel` `/model`) and the picker listed Gemini models |
| Model choice in engine settings | ✅ | ✅ | ✅ | ◐ | `AgentScreen.kt` model rows. ChatGPT sign-in has no model list (`AgentScreen.kt:286`), so only free text works |
| Reasoning effort | ✅ `/thinking` | ✖ | ✅ Settings | ✅ fixed | Claude: `RuntimeBridge.kt:60-62` (`CLAUDE_CODE_EFFORT_LEVEL`). Codex: `-c model_reasoning_effort` (key and level strings low, medium, high, xhigh found in the codex 0.161.0 binary; not run on a device). DSH: no effort flag or env in `DshRuntimeBridge.kt`; CLI support unverified |
| `/thinking` command | ✅ | ✖ | ✖ | ✖ | `SlashCommandEngine.kt:59` (Claude only) |
| Prompt workflows `/plan` `/review` `/init` `/grill-me` `/boost` `/learn` `/compact` | ✅ | ✅ | ✅ | ✅ | Prompt-only; default `supportedAgents` is all |
| `/goal` | ✅ | ✅ | ✅ | ✅ fixed | Was AG and Claude only. Prompt-only, so gating was not technical |
| `/browser` | ✖ | ✖ | ✅ | ✖ | `SlashCommandEngine.kt:140`; `SlashCommandEngineTest` asserts DSH does not get it. Other CLIs' web tools not verified |
| Local commands `/help` `/clear` `/cost` `/status` `/doctor` `/checkpoint` `/rollback` `/memory` `/skills` `/rules` | ✅ | ✅ | ✅ | ✅ | Shared `executeLocalSlashCommand`. `/cost` shows nothing for DSH (no usage data) |
| Project rules (GEMINI.md, CLAUDE.md, AGENTS.md) | ✅ | ✅ | ✅ | ✅ | `MainViewModel.kt:4428` `buildRulesBlock`, shared prompt path |
| Skills and memory/brain context | ✅ | ✅ | ✅ | ✅ | `brainSnapshot` and `memory` parameters in every bridge |
| Attachments | ✅ | ✅ | ✅ | ✅ | `attached_files` block in the shared send path |
| Provider picker | ✅ | ✅ | N/A | ✅ | `providersForAgent` in `model/Models.kt` |
| Sign-in | Claude account or API key | API key | Google account | ChatGPT device code or API key | `ClaudeAuthController`, `AntigravityAuthController`, `CodexAuthController` |
| Multi-account pool and failover | ✖ | ✖ | ✅ | ✖ | `AntigravityAccountManager`. Product feature of AG only |
| Update from the app | ✅ | ✅ | ✅ | ✖ pinned 0.161.0 | `RuntimeInstaller.kt:404` ("Codex is updated together with the app") |

## 2. Changes in this pass (uncommitted, on `harness-dev`)

1. **`/model` and the picker act on the active harness's model.** New `modelSlotFor` (`model/Models.kt`) picks one of three slots: Antigravity model, Claude subscription model (Claude with its account login), or the stored provider model (everything else). `MainViewModel.applyModelChoice` writes that slot. The picker lists the matching models. Antigravity keeps its previous list. Claude's account login gets its fixed list. DSH and Codex get their provider's default list, and the picker says so when a provider has none. Tests: `ModelSlotTest` (4).
2. **`/goal` for every harness.** Removed the AG and Claude gate in `SlashCommandEngine.kt`. Test added to `SlashCommandEngineTest`.
3. **Codex reasoning effort.** Settings → Codex → Reasoning: Default, Low, Medium, High, XHigh. The value is stored in preference `codex_reasoning_effort`, not on `ProviderProfile`, because the provider-edit paths rebuild profiles and would reset it. `CodexLaunchBuilder.command` adds `-c model_reasoning_effort="…"` only when a level is chosen. Tests: `CodexReasoningEffortTest` (3).

## 3. Open gaps

Closing these needs a decision from Love or a captured CLI stream from the device.

1. **DeepSeek effort and usage.** No evidence the DSH SDK stream carries usage or an effort flag. Needs one raw `dsh --profile sdk` stream from a device run.
2. **Antigravity reasoning summaries.** Needs a raw `agy` stream that contains thinking.
3. **Subagents, background tasks, artifacts for DeepSeek and Codex.** No event source in those CLIs' parsed output. Mapping Codex `todo_list` to tasks would be a meaning choice, so it is not done.
4. **Tool approvals.** No harness asks the user. Claude's watcher answers every request file with `allow` (`ClaudeRuntimeBridge.kt:569-596`), and the capability flags say otherwise. Real approvals would block the CLI until the user answers, which has process-death and stuck-turn risk.
5. **Native resume.** No harness resumes a CLI session. Codex would need `--ephemeral` removed and `codex exec resume <thread>`. Decision needed.
6. **`/thinking` is Claude only by name; `/browser` is AG only and tested that way.** Decide whether `/thinking` should set the active harness's effort, and whether `/browser` should go to other harnesses once their web tools are verified.
7. **Preview.** Nothing emits `PreviewStarted`.
8. **Codex is pinned to 0.161.0** and cannot be updated from the app.
9. **Capability flags in `AgentDriver.kt` are stale.** Codex lacks `ACCOUNT_LOGIN` and `REASONING_EFFORT`. `RESUME` and `INTERACTIVE_APPROVALS` are declared where nothing implements them. Nothing reads the flags yet.

## 4. Verification

- Targeted unit tests, run on this pass: `ModelSlotTest` 4/4, `SlashCommandEngineTest` 8/8, `CodexReasoningEffortTest` 3/3.
- Full online and offline unit suites: see the status recorded with the report.
- Not verified on a device: the picker and Reasoning sheet layout, `/model` switching during a live run, and Codex actually changing reasoning depth with `model_reasoning_effort`.
