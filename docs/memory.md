# Project context

Keep durable facts here. Verify saved context against current code when it matters; use [tasks.md](tasks.md) for active work.

## Current facts

| Topic | Context |
| --- | --- |
| Product | Mobile Harness Plus, derived from Tech Jarves' Mobile Harness. Preserve attribution, LICENSE/PRIVACY and third-party notices. |
| Workspace | /workspace/bright-kalam; branch test-ui. Keep output in the active harness workspace. Historical curious-curie/clever-kalam paths refer to other workspaces. |
| Source | Primarily Kotlin/Compose plus native C/ASM; Gradle Kotlin DSL. Java is the JVM toolchain. |
| Build | Existing AGP 8.13.2/Kotlin 2.0.21, Java 17, compile SDK 36; online/offline flavors. Default debug ID com.jarves.mh.dev; release com.jarves.mh. |
| Phone | ARM64 Android/PRoot Ubuntu; installed Gradle and configured ARM64 tools. Process utilities may fail; command output may appear only on completion. |
| Guest Android builds | RuntimeInstaller refreshes managed Gradle defaults before command launch: daemon reuse disabled, in-process Kotlin, two workers and a 512 MB heap. This repository's clean compile exceeded that heap; verification used a command-line 2 GB override. |
| Engines | Separate Claude Code, DeepSeek Harness, Antigravity CLI and Codex bridges. Codex is pinned to 0.161.0; upgrades require supported-contract checks. |
| Codex accounts | Existing private homes and task bindings are retained. ChatGPT login/migration require local pinned-CLI login status; account/read only supplies optional metadata. Shared/legacy credential adoption was removed. Fresh masks hide other homes without copying credentials or rebinding the active collection parent. Failed reconnect preserves the old usable record; confirmed sign-out invalidates stale ready records. A new login replaces an unusable default while preserving a healthy default. Explicit removal deletes canonical and known obsolete cached credentials. Auth cleanup does not follow project/credential links. Live dual-account/browser and native bind acceptance remain unverified. |
| Codex balancing | Opt-in round-robin uses a persisted cursor and current known quota hints; task identity stays pinned through retries. Native context switching transfers only a bounded, validated completed Codex rollout after process close. Goals and chats with native child threads stay with their owner; Plan mode follows the chat. Project Brain/rules stay shared, while account-specific Codex memory remains isolated. Interrupted, invalid or oversized handoffs stop with explicit new-chat or `/clear` recovery; never describe the feature as unconditionally lossless. Final tests/review/package and device acceptance are tracked in [tasks.md](tasks.md#codex-account-balancing--2026-10-13). |
| Data | Selected project roots, paged chats, preferences, checkpoints and Brain/task storage. Preserve migrations and legacy compatibility. |
| Context | Scoped rules/skills and bounded prompt/Brain assembly. Skill imports/snapshots validate contained resources; linked IDs preserve earlier selections and manual-only policy is enforced in the prompt catalog. Codex receives bounded, launch-local native skill selections after owned metadata discovery; account config is unchanged. Codex compact, goals and plan/review operations use the pinned native app-server; other engines retain prompt templates. |
| Native runtime | APKs currently use tracked ARM64 prebuilt libraries. C source changes alone do not reach normal Gradle assembly. PRoot is not a hardened sandbox. |

## Product boundaries to remember

- Root tabs are Projects, Agent and Settings; each active workspace has Chat, Files, Terminal and Preview. Quick Terminal and read-only project history are separate paths.
- Project entry points include named/quick workspaces, ZIP import, public HTTPS Git and GitHub device-code repository access; export produces a selected-workspace ZIP, not a complete app/account backup.
- Claude supports CLI-owned subscription login and API/gateway choices; DSH uses API/gateway providers and history replay; Antigravity CLI uses official Google login; Codex offers ChatGPT device login or a custom Responses endpoint. Preserve engine-specific eligibility rather than assuming every provider works with every engine.
- Native Antigravity CLI execution and Antigravity Server's provider gateway are distinct paths sharing the existing account manager. Official CLI login supplies account credentials; routing/quotas apply through that manager. Preserve those ownership boundaries.
- Included Web tools and optional Python/Android/C++/PHP stacks run in the guest. Offline APK inputs cover runtime installation, not offline model inference.
- Online APKs download the pinned Antigravity archive during installation; offline APKs bundle it. Already-installed runtimes are reused. Preserve the checksum-verified delivery paths and local offline build input.
- Files supports text editing, entry management, uploads/sharing/copies, selective ZIP export and Android build/cancel controls. Queued follow-ups remain in memory; Steer reorders them rather than interrupting an active provider turn. Individual child/task stopping is unavailable. Claude is the current interactive-approval driver; the other headless bridges do not implement approval responses.
- The registered command set has 25 entries. Codex compact/goal/plan/review use typed native operations; other workflow commands expand prompts. Native goals persist scoped thread controls and require explicit resume after interruption; timer events do not imply a standalone scheduler. The full list and checkpoint limits are in [prd.md](prd.md#slash-commands).
- UI project memory facts differ from canonical Brain/task records. Saved state, quota reports, model menus and emitted activity do not prove a provider turn or process-death recovery succeeded.
- The three inspection operations are doctor, scoped Brain search and open-Preview DOM/console/viewport inspection. These are not remote browsing or arbitrary Android automation.

## Decisions to preserve

- For Codex/GPT agents only, have the main agent plan and coordinate, then delegate implementation. Prefer GPT-6.1 Sol for complex code; use a cheaper GPT model for routine coding when appropriate and GPT-6 Luna for routine review/testing. Choose models based on task complexity and risk, avoid redundant agents, and optimize for efficient execution and production quality. This preference does not set routing for Claude, Gemini, or other providers.
- Preserve local capability repairs, tests, assets and prior diagnostic evidence. Commit/push only when explicitly requested.
- Scope engine/provider protocols, credentials and resume identities. Task, session and chat identity have different responsibilities.
- Preserve completed Codex work when later cleanup exits unsuccessfully; prove outcome-precedence changes with pinned-contract fixtures.
- Keep optional inspection failures explicit and bounded. Preview needs an open, project-owned WebView; arbitrary device control and remote browsing are outside that bridge.
- Reuse existing components, tokens and data migrations; avoid speculative structure and unrelated changes.
- Maintain six canonical project docs. Root README/legal/agent entry points, skill-package references and distribution assets have separate purposes.
- Root APK delivery: only generate 2 APKs in the root—mobile-harness-stable.apk (com.jarves.mh) and mobile-harness-dev.apk (com.jarves.mh.dev). Avoid duplicate app-online-* root copies.

## Evidence and limits

Original docs, historical device reports and preservation metadata remain locally under .review/docs-before-consolidation-2026-10-11/. The archive is ignored and absent from fresh clones. Committed originals remain in Git history; retain the local archive for uncommitted evidence.

Earlier checks are summarized in [tasks.md](tasks.md). Updated-APK activation, authenticated provider/device behavior, battery/frame timing and native binary/source equivalence still need verification. Injected instructions and APK builds do not establish universally seamless operation.

## Document map

- [prd.md](prd.md): required product outcomes and supported limits.
- [architecture.md](architecture.md): modules, runtime, storage and delivery.
- [rules.md](rules.md): coding, safety, phone verification and APK rules.
- [design.md](design.md): visual system, interaction and accessibility direction.
- [tasks.md](tasks.md): progress, recorded checks and unresolved validation.
- This file: stable facts and decisions.
