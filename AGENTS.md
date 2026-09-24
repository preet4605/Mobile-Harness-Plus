# Project Instructions & Agent Guidelines: Mobile Harness (Clever Kalam)

This document contains rules, operational guidelines, and architectural conventions for agents working in `/workspace/clever-kalam`.

---

## 1. Workspace Boundaries & Output Restrictions

- **Strict Workspace Containment**:
  - All source code, configs, build scripts, tests, and build artifacts MUST remain inside `/workspace/clever-kalam`.
  - **NEVER** write project output or persistent code to `~/.gemini/antigravity-cli/scratch` or `/tmp`.
- **APK Distribution**:
  - Whenever an Android debug build is assembled (`assembleOnlineDebug`), copy the output APK to the project root with both names:
    ```bash
    cp -f app/build/outputs/apk/online/debug/app-online-debug.apk app-online-debug.apk
    cp -f app-online-debug.apk mobile-harness-dev.apk
    ```
  - Always verify and report file sizes (`stat -c "%s %n" *.apk`) and MD5 checksums (`md5sum *.apk`).

---

## 2. Build & Verification Workflows

- **Toolchain**:
  - Android SDK build tools 35.0.0 with experimental AAPT2 override (`android.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2`).
- **Standard Verification Steps**:
  1. **Kotlin Compilation**:
     ```bash
     ./gradlew :app:compileOnlineDebugKotlin
     ```
  2. **Unit Tests Execution**:
     ```bash
     ./gradlew :app:testOnlineDebugUnitTest
     ```
     Ensure all unit tests pass before considering changes complete.
  3. **Assemble APK**:
     ```bash
     ./gradlew assembleOnlineDebug
     ```
- **Async Execution**:
  - Gradle tasks take 20s–90s. Run them asynchronously using the background task system. Do not busy-poll; rely on reactive notifications upon task completion.

---

## 3. Subagent & Background Task Architecture

- **Protocol Separation**:
  - `invoke_subagent` spawns a subagent (taking `Role`, `TypeName`, `Prompt`, `Model`, `Workspace`). The backend generates the conversation ID UUID.
  - Subagent update events must fuzzy-match by either `conversationId` or `role` to avoid duplicate or orphaned entries.
  - `manage_subagents` handles lifecycle actions (`kill`, `kill_all`, `list`).
- **State Transition Invariants**:
  - When the parent session finishes (`SessionCompleted` or `SessionFailed`), all active/running/waiting subagents must automatically transition to terminal states (`DONE` or `ERRORED`), and background tasks to `COMPLETED` or `FAILED`.
  - Subagents and tasks must never remain stuck in `RUNNING` status once the agent turn or session ends.
- **Inspector UI Controls**:
  - The Auxiliary Inspector Sheet must provide user controls to manually stop active subagents or tasks (`Stop` action button) and clear finished/errored entries (`Clear finished`).

---

## 4. Multi-Engine & Protocol Parity

- **Engine Isolation**:
  - Antigravity / Gemini protocols use native schema payloads (`contents`, `systemInstruction`).
  - Claude / DeepSeek / OpenAI protocols use standard chat completion or Anthropic Messages payloads.
  - Do not mix engine-specific payload structures; keep bridge logic in dedicated runtime adapters (`AntigravityRuntimeBridge`, `ClaudeRuntimeBridge`, `DshRuntimeBridge`).
- **Tool Mapping**:
  - Match tool names and call signatures cleanly across CLI wrappers: `run_command`, `view_file`, `replace_file_content`, `write_to_file`, `invoke_subagent`, `manage_subagents`, `schedule`.

---

## 5. Jetpack Compose & UI Conventions

- Follow Material 3 guidelines and use project brand tokens (`PocketBlue`, `PocketGreen`, `PocketOrange`).
- Avoid deprecated Compose APIs (e.g. use `Icons.AutoMirrored.Filled.*` instead of legacy icon vectors).
- All responses to the user MUST include clickable GitHub-style markdown file links using the `file://` scheme (e.g., `[FileName.kt](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/...)`).
