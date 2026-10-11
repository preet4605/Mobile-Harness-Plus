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
| Engines | Separate Claude Code, DeepSeek Harness, Antigravity CLI and Codex bridges. Codex is pinned to 0.161.0; upgrades require supported-contract checks. |
| Data | Selected project roots, paged chats, preferences, checkpoints and Brain/task storage. Preserve migrations and legacy compatibility. |
| Context | Scoped rules/skills and bounded prompt/Brain assembly. Local repairs publish linked skill resources through guest-readable snapshots. |
| Native runtime | APKs currently use tracked ARM64 prebuilt libraries. C source changes alone do not reach normal Gradle assembly. PRoot is not a hardened sandbox. |

## Product boundaries to remember

- Root tabs are Projects, Agent and Settings; each active workspace has Chat, Files, Terminal and Preview. Quick Terminal and read-only project history are separate paths.
- Project entry points include named/quick workspaces, ZIP import, public HTTPS Git and GitHub device-code repository access; export produces a selected-workspace ZIP, not a complete app/account backup.
- Claude supports CLI-owned subscription login and API/gateway choices; DSH uses API/gateway providers and history replay; Antigravity CLI uses official Google login; Codex offers ChatGPT device login or a custom Responses endpoint. Preserve engine-specific eligibility rather than assuming every provider works with every engine.
- Native Antigravity CLI execution and Antigravity Server's provider gateway are distinct paths sharing the existing account manager. Official CLI login supplies account credentials; routing/quotas apply through that manager. Preserve those ownership boundaries.
- Included Web tools and optional Python/Android/C++/PHP stacks run in the guest. Offline APK inputs cover runtime installation, not offline model inference.
- File viewing is read-only; agents/Terminal edit files. Individual child/task stopping is unavailable. Claude is the current interactive-approval driver; the other headless bridges do not implement approval responses.
- The registered command set has 25 entries. Workflow commands, including goal execution and compaction, expand prompts; timer events do not imply a standalone scheduler. The full list and checkpoint limits are in [prd.md](prd.md#slash-commands).
- UI project memory facts differ from canonical Brain/task records. Saved state, quota reports, model menus and emitted activity do not prove a provider turn or process-death recovery succeeded.
- The three inspection operations are doctor, scoped Brain search and open-Preview DOM/console/viewport inspection. These are not remote browsing or arbitrary Android automation.

## Decisions to preserve

- Preserve local capability repairs, tests, assets and prior diagnostic evidence. Commit/push only when explicitly requested.
- Scope engine/provider protocols, credentials and resume identities. Task, session and chat identity have different responsibilities.
- Preserve completed Codex work when later cleanup exits unsuccessfully; prove outcome-precedence changes with pinned-contract fixtures.
- Keep optional inspection failures explicit and bounded. Preview needs an open, project-owned WebView; arbitrary device control and remote browsing are outside that bridge.
- Reuse existing components, tokens and data migrations; avoid speculative structure and unrelated changes.
- Maintain six canonical project docs. Root README/legal/agent entry points, skill-package references and distribution assets have separate purposes.

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
