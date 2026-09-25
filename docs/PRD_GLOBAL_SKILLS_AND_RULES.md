# PRD: Global & Per-Project Skills and Selective Rules Architecture

**Feature codename:** `CustomizationScopeEngine`  
**Status:** Draft / Proposed  
**Owner:** Mobile Harness Team  
**Related Specs:** [`PRD_PERSISTENT_CONTEXT_MEMORY.md`](file:///workspace/clever-kalam/docs/PRD_PERSISTENT_CONTEXT_MEMORY.md), [`PRD_CLI_PARITY_AND_AUXILIARY_PANES.md`](file:///workspace/clever-kalam/docs/PRD_CLI_PARITY_AND_AUXILIARY_PANES.md)

---

## 1. Executive Summary & Problem Diagnosis

### 1.1 Current State Analysis (Debugging Findings)

A code-level audit of the Mobile Harness Android runtime revealed how skills and rules are currently discovered, managed, and executed:

| Feature Dimension | Current Discovery Path | UI Visibility | Injection Behavior | Scope Limitation |
|---|---|---|---|---|
| **Skills** | 1. Workspace: `.agents/skills`, `.claude/skills`, `.antigravity/skills`, `.gemini/skills`, `skills/`<br/>2. Android App Global: `/data/user/0/.../files/skills`<br/>3. Bundled: `/data/user/0/.../files/builtin_skills` | Listed in `SkillsManagerDialog` with toggle switch | Injected as `<skills>...</skills>` index block in the user message `runtimePrompt` | **Hybrid with Blind Spots**: Omits PRoot Linux rootfs global skills (`/root/.gemini/config/skills/`). Toggle states are ephemeral (in-memory only). No user choice of scope. |
| **Rules** | Workspace root only: `GEMINI.md`, `CLAUDE.md`, `AGENTS.md` | Read-only snippet in `SkillsManagerDialog` | **Not injected by Mobile Harness!** Reliant entirely on whether the CLI binary (`agy`/`claude`) reads the file from CWD. | **Strictly Per-Project Only**: Global rules (`/root/.gemini/config/rules/*.md`, `/root/.gemini/config/GEMINI.md`) are completely invisible and unselectable. No modular rule selection. Zero injection into DSH/DeepSeek. |

### 1.2 Core Problems Identified

1. **Rule Blindness for Global Personas**:
   - The PRoot filesystem contains rich global personas in `/root/.gemini/config/rules/` (`coding-persona.md`, `design-persona.md`, `debugging-persona.md`, `performance-persona.md`, `security-persona.md`).
   - Mobile Harness's [`SkillManager.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/runtime/SkillManager.kt) has zero awareness of these files. Users cannot view, enable, disable, or select them from the UI.
2. **Missing Rule Injection in Non-CLI Bridges**:
   - [`MainViewModel.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt) injects `skillsIndex` into the user prompt, but **never injects rules**. While Antigravity CLI and Claude Code read local workspace files if invoked with standard options, other bridges ([`DshRuntimeBridge.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/runtime/DshRuntimeBridge.kt) or direct OpenAI/DeepSeek endpoints) receive zero rules.
3. **No Granular Scope Control**:
   - Users cannot configure whether a project should:
     - Use strictly project-local rules and skills.
     - Inherit all global rules and skills.
     - Cherry-pick specific global rules (e.g. selectively turning on `security-persona.md` and `test-driven-development`).
4. **Transient UI State**:
   - Skill toggles (`isEnabled`) are lost when navigating away, switching projects, or restarting the application.

---

## 2. Product Goals & Non-Goals

### 2.1 Goals

- **G1 (Unified Customization Discovery)**: Discover skills and rules across all four layers: Built-in, Android App Global, PRoot Linux Global (`/root/.gemini/config`), and Project Workspace.
- **G2 (Scope Selection Modes)**: Provide project-level and global scope policies:
  - `INHERIT_AND_MERGE` (Default): Project rules/skills override or augment global rules/skills.
  - `PROJECT_ONLY`: Project isolation; ignores all global rules and skills.
  - `GLOBAL_ONLY`: Standard enterprise/developer baseline; suppresses local rules.
  - `CUSTOM_SELECTION`: User granularly toggles individual global and project rules.
- **G3 (Selective Rule Picker UI)**: A clean Material 3 interface allowing users to review and selectively enable/disable specific rule files (e.g. personas, coding standards, security constraints).
- **G4 (Deterministic Cross-Bridge Injection)**: Systematically inject active rules and skills into all runtime bridges (`Antigravity`, `Claude`, `DSH`, `API`) with standard `<user_rules>` and `<skills>` schema formatting.
- **G5 (Persistent Configuration)**: Store rule and skill enablements per project in SQLite / SharedPreferences (`ContextMemory` or `AppPreferences`).

### 2.2 Non-Goals

- Replacing the CLI's native capability to read `.agents/` or `GEMINI.md` directly.
- Cloud syncing of rules across multiple physical devices (out of scope for v1; remains on-device).

---

## 3. Architecture & Data Flow

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Discovery & Aggregation Layer                    │
├──────────────────┬───────────────────┬─────────────────────────────────┤
│ Built-in Bundled │ App Global Files  │ Linux PRoot Global Config       │ Workspace (Local)
│ (builtin_skills) │ (files/skills)    │ (/root/.gemini/config/rules &   │ (.agents/, GEMINI.md,
│                  │ (files/rules)     │  /root/.gemini/config/skills)   │  CLAUDE.md, AGENTS.md)
└─────────┬────────┴─────────┬─────────┴────────────────┬────────────────┴────────┬──────┘
          │                  │                          │                         │
          ▼                  ▼                          ▼                         ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        CustomizationScopeEngine (SkillManager)                         │
│  - Parses Frontmatter & Metadata                                                       │
│  - Filters by Scope Mode: INHERIT_MERGE | PROJECT_ONLY | GLOBAL_ONLY | CUSTOM_SELECT   │
│  - Resolves Precedence & Shadows (Project > Global > Bundled)                          │
└──────────────────────────────────────────┬─────────────────────────────────────────────┘
                                           │
                                           ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                       CustomizationPreferencesStore (Persistent)                       │
│  - Stores: enabledSkillIds, enabledRuleIds, scopeMode per Project ID                    │
└──────────────────────────────────────────┬─────────────────────────────────────────────┘
                                           │
                                           ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                           UI Layer (Skills & Rules Manager)                            │
│  - Tab 1: Project Rules (Local GEMINI.md, CLAUDE.md, .agents/rules)                   │
│  - Tab 2: Global Rules & Personas (coding, design, debugging, security, etc.)         │
│  - Tab 3: Skills (Local, Global, Bundled with source badges & toggles)                 │
│  - Tab 4: Scope Policy & Rule Injection Preview                                        │
└──────────────────────────────────────────┬─────────────────────────────────────────────┘
                                           │
                                           ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                       Prompt Construction & Runtime Bridge                             │
│  - Injects active rules block: <user_rules><RULE>...</RULE></user_rules>               │
│  - Injects active skills index: <skills>...</skills>                                   │
│  - Parity across Antigravity, Claude Code, and DeepSeek Harness (DSH)                  │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Detailed Functional Specifications

### 4.1 Data Models

Update and extend [`com.jarves.mh.model.Models.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/model/Models.kt):

```kotlin
enum class CustomizationScopeMode(val title: String, val description: String) {
    INHERIT_AND_MERGE(
        "Inherit & Merge (Recommended)",
        "Combines global rules and skills with project-specific customizations. Project definitions take precedence."
    ),
    PROJECT_ONLY(
        "Project Isolated",
        "Strictly uses rules and skills defined inside this project workspace. Global settings are ignored."
    ),
    GLOBAL_ONLY(
        "Global Baseline Only",
        "Enforces global personas and tools across the project, ignoring local workspace rules."
    ),
    CUSTOM(
        "Custom Selection",
        "Individually select which global and project rules to apply to this workspace."
    )
}

enum class RuleSource(val title: String) {
    PROJECT("Project"),
    GLOBAL("Global Config"),
    BUNDLED("Built-in System")
}

data class RuleInfo(
    val id: String,
    val name: String,
    val title: String,
    val description: String,
    val filePath: String,
    val source: RuleSource,
    val isEnabled: Boolean = true,
    val content: String = "",
)

data class ProjectCustomizationConfig(
    val projectId: String,
    val scopeMode: CustomizationScopeMode = CustomizationScopeMode.INHERIT_AND_MERGE,
    val enabledRuleIds: Set<String> = emptySet(),
    val disabledRuleIds: Set<String> = emptySet(),
    val enabledSkillIds: Set<String> = emptySet(),
    val disabledSkillIds: Set<String> = emptySet(),
)
```

### 4.2 Multi-Layer Discovery in `SkillManager`

Extend [`SkillManager.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/runtime/SkillManager.kt) to discover both host Android files and guest PRoot paths:

1. **Global Rules Discovery**:
   - Android Host: `File(context.filesDir, "rules")`
   - Linux Guest RootFS:
     - `File(rootfsDir, "root/.gemini/config/rules")` (`coding-persona.md`, `design-persona.md`, etc.)
     - `File(rootfsDir, "root/.gemini/config/GEMINI.md")`
     - `File(rootfsDir, "root/.gemini/config/AGENTS.md")`
     - `File(rootfsDir, "root/.claude/CLAUDE.md")`
2. **Project Rules Discovery**:
   - `projectWorkspaceDir/GEMINI.md`, `CLAUDE.md`, `AGENTS.md`
   - `projectWorkspaceDir/.agents/rules/*.md`
   - `projectWorkspaceDir/.gemini/rules/*.md`
   - `projectWorkspaceDir/.claude/rules/*.md`
3. **Global Skills Discovery**:
   - Android Host: `File(context.filesDir, "skills")`
   - Linux Guest RootFS: `File(rootfsDir, "root/.gemini/config/skills")` and `File(rootfsDir, "root/.agents/skills")`
   - Bundled: `File(context.filesDir, "builtin_skills")`

### 4.3 Selective Rule Evaluation Logic

When compiling the active rules for a project:

```kotlin
fun resolveActiveRules(
    projectRules: List<RuleInfo>,
    globalRules: List<RuleInfo>,
    config: ProjectCustomizationConfig
): List<RuleInfo> {
    return when (config.scopeMode) {
        CustomizationScopeMode.PROJECT_ONLY -> {
            projectRules.filter { it.id !in config.disabledRuleIds }
        }
        CustomizationScopeMode.GLOBAL_ONLY -> {
            globalRules.filter { it.id !in config.disabledRuleIds }
        }
        CustomizationScopeMode.INHERIT_AND_MERGE -> {
            val projectNames = projectRules.map { it.name.lowercase() }.toSet()
            // Global rules that are not overridden by a project rule with the same name
            val inheritedGlobals = globalRules.filter { it.name.lowercase() !in projectNames }
            (projectRules + inheritedGlobals).filter { it.id !in config.disabledRuleIds }
        }
        CustomizationScopeMode.CUSTOM -> {
            (projectRules + globalRules).filter { it.id in config.enabledRuleIds }
        }
    }
}
```

### 4.4 Prompt Injection Engine

Update [`MainViewModel.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt) to construct structured prompt envelopes:

```kotlin
fun buildRulesBlock(activeRules: List<RuleInfo>): String {
    if (activeRules.isEmpty()) return ""
    return buildString {
        appendLine("<user_rules>")
        activeRules.forEach { rule ->
            appendLine("<RULE[${rule.name}]>")
            appendLine(rule.content.trim())
            appendLine("</RULE[${rule.name}]>")
        }
        appendLine("</user_rules>")
    }
}
```

This ensures full parity between **Antigravity CLI**, **Claude Code**, and **DeepSeek/Custom API** engines.

---

## 5. UI / UX Design Specifications

### 5.1 Redesigned `Skills & Rules Manager` Modal

Replace the 2-tab dialog in [`CliParityComponents.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/ui/CliParityComponents.kt) with an upgraded sheet:

1. **Header & Scope Policy Banner**:
   - Scope dropdown/selector: `Inherit & Merge` | `Project Only` | `Global Only` | `Custom Selection`.
   - Explanatory subtitle clarifying how rules and skills will be inherited.
2. **Tab 1: Global Personas & Rules**:
   - Displays all discovered global rules (`Software Architect`, `Frontend Design Lead`, `Systems Diagnostician`, `Performance Engineer`, `Security Specialist`).
   - Each card displays:
     - Name and domain badge (e.g. *Security*, *Performance*, *Quality*).
     - Switch/Checkbox to enable or disable for the current workspace.
     - Expandable viewer to preview rule contents.
3. **Tab 2: Project Rules**:
   - Workspace-specific rules (`GEMINI.md`, `CLAUDE.md`, `AGENTS.md`, and modular `.agents/rules/`).
   - Quick actions: "Edit Rule", "Add New Rule", "Delete".
4. **Tab 3: Skills**:
   - List grouped by source: Project Workspace, Global Config, Built-in.
   - Filter chips: `All`, `Active`, `Project`, `Global`.
   - Toggle switch per skill with source badge and file location path.
5. **Tab 4: Injection Preview**:
   - Real-time preview of the exact `<user_rules>` and `<skills>` blocks that will be delivered to the AI model.

---

## 6. Implementation Plan & Milestones

| Milestone | Deliverables | Target Files |
|---|---|---|
| **Phase 1: Model & Storage** | Define `CustomizationScopeMode`, `RuleInfo`, `ProjectCustomizationConfig`. Implement persistent preferences store in `ContextMemoryStore` / `AppPreferences`. | [`Models.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/model/Models.kt), [`AppPreferences.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/data/AppPreferences.kt) |
| **Phase 2: RootFS Discovery** | Enhance `SkillManager` to query PRoot guest paths (`rootfs/root/.gemini/config/rules` and `skills`) and modular project rules. | [`SkillManager.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/runtime/SkillManager.kt) |
| **Phase 3: Prompt Bridge Integration** | Implement `buildRulesBlock` and integrate into `MainViewModel.kt`, `DshRuntimeBridge.kt`, and `ClaudeRuntimeBridge.kt`. | [`MainViewModel.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt), [`DshRuntimeBridge.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/runtime/DshRuntimeBridge.kt) |
| **Phase 4: UI Overhaul** | Redesign `SkillsManagerDialog` into a 4-tab manager with Scope selection chips, Global persona toggles, and live prompt preview. | [`CliParityComponents.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/ui/CliParityComponents.kt) |
| **Phase 5: Verification & Quality Gate** | Unit tests for precedence resolution, prompt compilation, and Kotlin build verification (`assembleOnlineDebug`). | `SkillManagerTest.kt`, Gradle build suite |

---

## 7. Verification & Acceptance Criteria

1. **Discovery Verification**:
   - `SkillManager.discoverRules()` returns all 5 personas from `/root/.gemini/config/rules/` plus workspace rules.
   - `SkillManager.discoverSkills()` returns both Android host skills and PRoot `/root/.gemini/config/skills/`.
2. **Scope Switching**:
   - Setting a project to `PROJECT_ONLY` suppresses all global personas and skills from the generated prompt.
   - Setting `CUSTOM` allows toggling individual personas (e.g. enabling `security-persona` while disabling `design-persona`).
3. **Cross-Engine Parity**:
   - Both DeepSeek Harness and Claude Code sessions receive the configured `<user_rules>` block.
4. **Clean Build**:
   - `./gradlew :app:compileOnlineDebugKotlin` passes with 0 warnings/errors.
   - `./gradlew :app:testOnlineDebugUnitTest` passes 100%.
