# Project architecture

This describes the current checkout, including local capability repairs. Requirements are in [prd.md](prd.md); verification status is in [tasks.md](tasks.md).

## Platform and modules

The application is primarily Kotlin with Jetpack Compose and native C/ASM integration. Existing scripts use AGP 8.13.2, Kotlin 2.0.21, Java 17 and compile SDK 36. ARM64 and minimum SDK 28 are configured. Direct builds target SDK 28; the playBuild switch targets SDK 36. Preserve these versions unless a task requires changing them.

| Location | Responsibility |
| --- | --- |
| app/ | Android application, resources, runtime integration and tests. |
| benchmark/ | Separate device Macrobenchmark module; target app ID com.jarves.mh.bench. |
| data/ | Preferences, paged chat storage, credential vault, project memory and SQLite-backed Brain/task repositories. |
| model/ | Engine/provider models, task/context entities and subagent state. |
| runtime/ and runtime/task/ | Installation, guest launch, engine bridges, process supervision, verification, recovery and checkpoints. |
| network/, provider/, update/ | Provider HTTP access, routing and APK updates. |
| ui/, ui/kit/, ui/theme/ | Compose screens, ViewModel state, controls, semantic tokens and glass rendering. |
| app/src/main/cpp/, third_party/ | Native C/ASM source and vendored PRoot, libandroid-shmem and talloc. |
| scripts/, dist/runtime-bundles/manifest.json | Build/install/update tooling and pinned runtime artifact metadata. |
| fastlane/, fdroid/, assets/readme/ | Distribution metadata, artwork and README assets. |

Package rows refer to app/src/main/java/com/jarves/mh/.

## Feature wiring

[PocketDevApp](../app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt) selects startup/setup, the Projects/Agent/Settings root tabs, an active workspace or read-only project history. Screen callbacks route to MainViewModel; source presence alone does not prove a user-facing control is wired.

| Path | Owners and boundary |
| --- | --- |
| Projects and import/export | [ProjectsScreen](../app/src/main/java/com/jarves/mh/ui/ProjectsScreen.kt), MainViewModel and [GitHubClient](../app/src/main/java/com/jarves/mh/network/GitHubClient.kt). Android URI pickers handle ZIP import/export; public Git cloning and authenticated GitHub CLI cloning are distinct paths. Selected nested roots affect guest working directories and workspace operations. |
| Chats, files and review | [WorkspaceScreen](../app/src/main/java/com/jarves/mh/ui/WorkspaceScreen.kt), AppPreferences, [ChangesReview](../app/src/main/java/com/jarves/mh/ui/ChangesReview.kt) and checkpoints. Read-only history is a separate destination; the file viewer has view/copy controls, while editing runs through agents/Terminal. |
| Terminals and Android installation | [TerminalScreen](../app/src/main/java/com/jarves/mh/ui/TerminalScreen.kt) uses separate quick/project state and existing guest launch/cleanup paths. [AndroidAppInstaller](../app/src/main/java/com/jarves/mh/runtime/AndroidAppInstaller.kt) hands APKs to the Android installer, with platform permission/user interaction. |
| Agent/provider configuration | [AgentScreen](../app/src/main/java/com/jarves/mh/ui/AgentScreen.kt), [Models.kt](../app/src/main/java/com/jarves/mh/model/Models.kt), [ProviderApiClient](../app/src/main/java/com/jarves/mh/network/ProviderApiClient.kt), model cache and auth controllers. Provider eligibility, effective protocol and model/effort slots are engine-specific. |
| Google gateway | [AntigravityAccountManager](../app/src/main/java/com/jarves/mh/runtime/AntigravityAccountManager.kt) provides account routing/quotas to both the native CLI and [AntigravityGatewayServer](../app/src/main/java/com/jarves/mh/runtime/AntigravityGatewayServer.kt). [AntigravityAuthController](../app/src/main/java/com/jarves/mh/runtime/AntigravityAuthController.kt) drives official CLI login; routed account homes and the local provider gateway remain distinct execution paths. |
| Commands and customizations | [SlashCommandEngine](../app/src/main/java/com/jarves/mh/runtime/SlashCommandEngine.kt) parses registered commands and enabled skill invocations; MainViewModel handles local commands or expands workflow prompts. [SkillManager](../app/src/main/java/com/jarves/mh/runtime/SkillManager.kt) resolves rules/skills; [CliParityComponents](../app/src/main/java/com/jarves/mh/ui/CliParityComponents.kt) supplies scope, editing, prompt-preview and activity surfaces. |
| Web Preview | WorkspaceScreen owns the loopback WebView lifecycle. [LocalPreviewDiscovery](../app/src/main/java/com/jarves/mh/runtime/LocalPreviewDiscovery.kt) detects local URLs; [PreviewInspectionHost](../app/src/main/java/com/jarves/mh/runtime/PreviewInspectionHost.kt) captures DOM/console/viewport for its owner. External network requests in the WebView are blocked. |
| Setup, settings and app updates | [OnboardingScreens](../app/src/main/java/com/jarves/mh/ui/OnboardingScreens.kt), RuntimeInstaller and [RuntimeSetupService](../app/src/main/java/com/jarves/mh/runtime/RuntimeSetupService.kt); [SettingsScreenModern](../app/src/main/java/com/jarves/mh/ui/SettingsScreenModern.kt) manages appearance/toolchains; [AppUpdater](../app/src/main/java/com/jarves/mh/update/AppUpdater.kt) handles APK manifests, downloads and validation. App updates and engine/toolchain updates are separate. |

The complete user feature/command/provider inventory is in [prd.md](prd.md#current-feature-inventory). Internal named-provider CRUD and build-and-install helpers currently lack dedicated screen call sites; keep that separate from delivered controls.

## Execution and ownership

Compose screens publish user intent through [MainViewModel](../app/src/main/java/com/jarves/mh/ui/MainViewModel.kt). [TaskSupervisor](../app/src/main/java/com/jarves/mh/runtime/task/TaskSupervisor.kt) coordinates task state, verification, recovery and the foreground task lifecycle. Engine bridges keep provider-specific launch/protocol behavior separate; native launch and PRoot run commands in the selected workspace.

A taskId identifies a supervised task; a sessionId identifies a process/provider session. They are distinct. Async publication also needs the applicable project, chat, root, engine, attempt and request ownership checks. Provider results, process outcomes and meaningful verification have distinct roles; a child or stream end_turn cannot terminate the parent by itself. Preserve completed Codex turns when later cleanup produces a nonzero exit unless a proven protocol failure requires a change.

| Engine bridge | Current contract |
| --- | --- |
| [ClaudeRuntimeBridge](../app/src/main/java/com/jarves/mh/runtime/ClaudeRuntimeBridge.kt) | Claude CLI streaming and scoped conversation state; optional interactive tool approvals through the existing control protocol. [ClaudeAuthController](../app/src/main/java/com/jarves/mh/runtime/ClaudeAuthController.kt) drives CLI-owned login/status/logout; advanced setup-token mode is separate. |
| [DshRuntimeBridge](../app/src/main/java/com/jarves/mh/runtime/DshRuntimeBridge.kt) | One-shot SDK/headless requests, history replay and emitted tool/usage/child events. No interactive approval channel in this mode. |
| [AntigravityRuntimeBridge](../app/src/main/java/com/jarves/mh/runtime/AntigravityRuntimeBridge.kt) | Headless CLI streaming with supported subagent/task/artifact/timer events. Its driver auto-approves; it does not accept interactive control responses. |
| [CodexRuntimeBridge](../app/src/main/java/com/jarves/mh/runtime/CodexRuntimeBridge.kt) | Pinned CLI exec/Responses protocol, scoped thread continuity and tool/events mapping. [CodexAuthController](../app/src/main/java/com/jarves/mh/runtime/CodexAuthController.kt) drives CLI-owned device login. Exec uses approval_policy=never; the UI cannot grant per-tool approvals for this driver. |

Task/step persistence, [StepVerifier](../app/src/main/java/com/jarves/mh/runtime/task/StepVerifier.kt), [RecoveryEngine](../app/src/main/java/com/jarves/mh/runtime/task/RecoveryEngine.kt), process supervision and bounded output work within TaskSupervisor. Startup reconciliation marks interrupted work and checks owned processes; stored state is not a guarantee of automatic continuation after process death. Foreground execution notifications and [WakeLockManager](../app/src/main/java/com/jarves/mh/runtime/task/WakeLockManager.kt) belong to the task lifetime.

The inspector consumes RuntimeEvent updates, not an independent scheduler. MainViewModel's individual child/task termination methods currently report unsupported operation; full-task Stop uses the supervisor/bridge cleanup path. Usage combines reported token metrics with provider-specific reports/estimates; missing data must remain explicit.

## Storage and context

- [AppPreferences](../app/src/main/java/com/jarves/mh/data/AppPreferences.kt) stores preferences/project metadata and a project chat index. Messages use pages; legacy whole-chat files remain readable and migrate through existing code.
- [BrainContextAssembler](../app/src/main/java/com/jarves/mh/data/BrainContextAssembler.kt) creates bounded context from canonical tasks, progress, constraints and saved knowledge. Memory is contextual data, not higher-priority instructions.
- [WorkspaceCheckpoints](../app/src/main/java/com/jarves/mh/runtime/WorkspaceCheckpoints.kt) retains original-path inventory, backup provenance, fingerprints and canonical roots. Preserve schema compatibility and migrations.
- Credentials use the existing vault/Android Keystore integration and engine-specific homes. PRoot mount visibility does not isolate credentials against hostile native tools.
- [ContextMemoryStore](../app/src/main/java/com/jarves/mh/data/ContextMemoryStore.kt) and the memory sheet manage project user/automatic facts. [MemoryExtractor](../app/src/main/java/com/jarves/mh/data/MemoryExtractor.kt), retrieval/ranking/conflict handling and [BrainLearningService](../app/src/main/java/com/jarves/mh/data/BrainLearningService.kt) support the existing memory pipeline; canonical task/knowledge records are distinct from UI facts.
- Rule discovery includes AGENTS.md, CLAUDE.md and GEMINI.md, project .agents/.gemini/.claude rule directories and existing global/bundled locations. Project skill roots include .agents/skills, .claude/skills, .antigravity/skills, .gemini/skills and skills/; discovery also covers global, linked and bundled SKILL.md trees. Merge/project/global/custom scope and enabled selections are persisted; imported skills are copies, links reference other projects, and promotion copies into the global library.
- Attachment files are copied into the project with bounded size/count and chat ownership; Android FileProvider grants read access when opening them. ZIP import/export have their own containment/exclusion checks and are not whole-app data backups.

## Managed capabilities

[RuntimeInstaller](../app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt) manages verified runtime inputs. Codex is pinned in [CodexInstall](../app/src/main/java/com/jarves/mh/runtime/CodexInstall.kt). Local repairs add certificate defaults, managed ARM64 search tools and guest-readable immutable skill snapshots with bounded traversal.

The dependency-free [adapter](../app/src/main/assets/harness/harness-tools.cjs) under tools/ and app/src/main/assets/harness/ exposes `harness_doctor`, `harness_preview` and `harness_brain_search` through CLI/MCP. Native inspection uses launch-private directories, ownership, file events, deadlines and cancellation. Preview capture reuses the project's open WebView; this bridge does not provide arbitrary page-script execution, remote browsing or device control. CLI doctor reports runtime capabilities; the chat `/doctor` handler uses [DiagnosticsHelper](../app/src/main/java/com/jarves/mh/runtime/DiagnosticsHelper.kt). Keep protocol changes in existing bridges and preserve account/session configuration.

## Native packaging

Normal Gradle assembly packages five prebuilt libraries from app/src/main/jniLibs/arm64-v8a/; externalNativeBuild is commented out. CMake describes their source build, but ordinary assembly does not establish source/binary equivalence. Retain binaries, vendored sources and licenses until a replacement is verified.

## Verification and distribution

Online/offline flavors share app behavior and differ in runtime delivery. Default debug uses com.jarves.mh.dev; stable release uses com.jarves.mh. Offline inputs are verified against their pinned manifest. Required phone/build commands are in [rules.md](rules.md).

Optional stacks in Models.kt are Web, Python, Android, C/C++ and PHP. Web uses the included Node.js/npm stack. Runtime assets/toolchains are distinct from the app's own Gradle dependencies. Bundled runtime delivery does not make provider requests offline.

Device frame timing uses the separate benchmark app after setup:

    gradle :app:installOnlineBenchmark
    gradle :benchmark:connectedBenchmarkAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true

Results and Perfetto traces appear under benchmark/build/outputs/connected_android_test_additional_output/. The existing target is P90 frame overrun at or below zero. These commands require working device access.

For local updater testing, preserve the signer, increase the installed version code, serve an HTTPS manifest, use the debug Settings override, and restore the normal update channel afterward. scripts/serve-update-server.sh uses host-specific shell utilities; confirm those on the target host before running it.
