# PRD: Cross-Project & Global Skills Federation Hub

**Feature Codename:** `ProjectSkillFederation`  
**Document Status:** Approved Architecture Spec  
**Target Release:** Mobile Harness v1.4.0+  
**Target File:** [`docs/PRD_CROSS_PROJECT_AND_GLOBAL_SKILLS.md`](file:///workspace/clever-kalam/docs/PRD_CROSS_PROJECT_AND_GLOBAL_SKILLS.md)  
**Related Documents:** [`PRD_GLOBAL_SKILLS_AND_RULES.md`](file:///workspace/clever-kalam/docs/PRD_GLOBAL_SKILLS_AND_RULES.md), [`PRD_PERSISTENT_CONTEXT_MEMORY.md`](file:///workspace/clever-kalam/docs/PRD_PERSISTENT_CONTEXT_MEMORY.md), [`PRD_CLI_PARITY_AND_AUXILIARY_PANES.md`](file:///workspace/clever-kalam/docs/PRD_CLI_PARITY_AND_AUXILIARY_PANES.md)

---

## 1. Executive Summary & Problem Diagnosis

### 1.1 Root Cause Analysis

In Mobile Harness, workspace isolation ensures that project source code and build environments remain strictly separated. Each project created in Mobile Harness resides in its own filesystem directory:
- Android Host: `File(context.filesDir, "workspaces/${project.id}")`
- PRoot Linux Guest: `/workspace/${project.slug}`

When a user, subagent, or external toolchain installs a skill inside a project (for example, by cloning a repository or writing to `.agents/skills/<skill-name>/SKILL.md`), that skill exists **exclusively inside that project's folder tree**.

Currently, [`SkillManager.discoverSkills`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/runtime/SkillManager.kt#L102-L148) executes with single-workspace scoping:
```kotlin
fun discoverSkills(projectWorkspaceDir: File?): List<SkillInfo> {
    // 1. Project skills: Scans ONLY the currently passed project directory
    // 2. Global user skills: Scans ONLY context.filesDir/skills
    // 3. Bundled built-in skills: Scans ONLY context.filesDir/builtin_skills
}
```

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          CURRENT ISOLATION MODEL                            │
│                                                                             │
│   Project A Workspace                       Project B Workspace             │
│   ├── .agents/skills/                       ├── src/                        │
│   │   ├── postgres-optimizer/               └── build.gradle                │
│   │   └── jetpack-compose-review/                                           │
│   └── src/                                  (Blind to Project A's skills!)  │
│                                                                             │
│   SkillManager.discoverSkills(projectB)                                     │
│   └── Only scans Project B + Host Global + Bundled                          │
│       => postgres-optimizer and jetpack-compose-review ARE NOT FOUND!       │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Core Pain Points

1. **Cross-Project Skill Invisibility**:
   - Skills installed, tuned, or tested in Project A cannot be seen, discovered, or reused in Project B without manual CLI file manipulation.
2. **Missing Promotion Mechanism ("Make Global")**:
   - If a developer creates or installs a high-value skill in one project, there is no one-tap action to promote it to the Global Skill Library for universal availability across all projects.
3. **No Multi-Project Skill Browser**:
   - The user cannot view what skills are installed in other projects from within their active workspace.
4. **Ephemeral Toggles**:
   - In [`MainViewModel.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt#L3298-L3306), toggling a skill's `isEnabled` flag is stored strictly in memory (`_state.update`). Switching projects or closing the app resets all toggles back to default.
5. **No Choice Between Soft Referencing vs. Hard Copying**:
   - Users need two distinct workflows:
     - **Link (Virtual Enablement)**: Dynamically inject an existing skill from Project A or Global into Project B's prompt without duplicating files.
     - **Import (Physical Workspace Clone)**: Deep copy the skill folder into Project B's `.agents/skills/` directory so it becomes part of Project B's Git tree.

---

## 2. Product Goals & Enablement Paradigms

### 2.1 Core Product Goals

- **G1 (Federated Cross-Project Discovery)**: Index and catalog skills across all registered projects in Mobile Harness, Android Host Global, PRoot Linux Global (`/root/.gemini/config/skills/`), and Built-in bundles.
- **G2 (Two-Tier Enablement Modes)**:
  - **Mode A: Virtual Link (Referenced Enablement)**: Enable a skill from another project or global catalog for the active workspace via a persistent configuration entry (`ProjectSkillConfig`). No file duplication; dynamically injected into system prompt index.
  - **Mode B: Physical Import (Workspace Clone)**: Deep copy a skill's directory from another project or global into `<active_project>/.agents/skills/<name>/`.
- **G3 (One-Tap "Promote to Global")**: Allow exporting any project-local or linked skill to the Global Library (`files/skills/` and PRoot config), making it accessible to every project.
- **G4 (Persistent Per-Project Customization Store)**: Persist enabled/disabled states and linked skill references in `AppPreferences` or project workspace config (`.pocketdev/skills.json`).
- **G5 (Comprehensive Skills Hub UI)**: Deliver a Material 3 management dialog with tabs for *Project Skills*, *Global Library*, and *Other Projects Browser*, complete with search, filtering, and conflict badges.
- **G6 (CLI & Agent Parity)**: Provide `/skills`, `/skill link`, `/skill import`, and `/skill promote` commands matching the visual UI capabilities.

### 2.2 Non-Goals

- Remote cloud syncing across multiple devices (skills remain local to the Android device).
- Automated dependency package installations inside external project directories (each project maintains its own isolated toolchain).

---

## 3. Architecture & Data Flow

### 3.1 Federation Pipeline Diagram

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                               DISCOVERY & REGISTRATION                                 │
│                                                                                        │
│  Active Project          Other Projects (Registered)        Global & Bundled Repos     │
│  (.agents/skills, etc.)  (Iterate AppPreferences.projects)  (/files/skills, rootfs,    │
│                                                             /builtin_skills)           │
└───────────┬──────────────────────────┬─────────────────────────────────┬───────────────┘
            │                          │                                 │
            ▼                          ▼                                 ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        SkillManager.discoverAllSkillSources()                          │
│  - Discovers:                                                                          │
│    * activeProjectSkills: List<SkillInfo> [Source: PROJECT]                            │
│    * otherProjectSkills: Map<Project, List<SkillInfo>> [Source: OTHER_PROJECT]         │
│    * globalSkills: List<SkillInfo> [Source: GLOBAL]                                    │
│    * bundledSkills: List<SkillInfo> [Source: BUNDLED]                                  │
└──────────────────────────────────────────┬─────────────────────────────────────────────┘
                                           │
                                           ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                  Persistent Resolution: ProjectSkillConfigStore                        │
│  - Loads config for activeProjectId:                                                   │
│    * enabledLocalSkillIds: Set<String>                                                 │
│    * disabledLocalSkillIds: Set<String>                                                │
│    * linkedSkills: List<LinkedSkillReference>                                          │
│      (sourceProjectId, skillName, sourcePath)                                          │
│    * enabledGlobalSkillIds: Set<String>                                                │
└──────────────────────────────────────────┬─────────────────────────────────────────────┘
                                           │
                                           ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                     Active Project Skill Set Compilation                               │
│  1. Active Local Skills (Project .agents/skills filtered by config)                    │
│  2. Linked Skills (Referenced from Other Projects, resolved read-only)                 │
│  3. Enabled Global & Bundled Skills                                                    │
│  * Resolves Precedence: Project Local > Linked > Global > Bundled                      │
└──────────────────────────────────────────┬─────────────────────────────────────────────┘
                                           │
                      ┌────────────────────┴────────────────────┐
                      ▼                                         ▼
┌──────────────────────────────────────────┐  ┌──────────────────────────────────────────┐
│             UI / Interaction             │  │            Prompt Injection              │
│  - Skills Hub Sheet                      │  │  - Progressive disclosure XML index:     │
│  - Search & Filter by Project            │  │    <skills>                              │
│  - Actions: Link, Import (Copy), Promote │  │      - name (path): description          │
│  - Slash Commands (/skill link ...)      │  │    </skills>                             │
└──────────────────────────────────────────┘  └──────────────────────────────────────────┘
```

---

## 4. Enablement Modes & Operations

### 4.1 Mode 1: Virtual Link (Referenced Enablement)

- **Behavior**: The skill folder remains physically located in Project A (`/workspaces/projectA/.agents/skills/db-tools`), but Project B's configuration marks it as **Linked**.
- **Execution**: When compiling the prompt for Project B, `SkillManager` parses `db-tools/SKILL.md` from Project A's directory and injects it into Project B's `<skills>` index.
- **Safety**:
  - The source skill is treated as **Strictly Read-Only** in Project B.
  - Project B cannot delete or overwrite Project A's files.
  - If Project A is deleted, Project B's UI detects the missing reference and marks the skill as `[Orphaned / Missing Source]` with a one-tap "Remove Link" or "Restore from Cache" option.
- **Use Case**: Lightweight sharing of utilities, guidelines, and tool configs without creating duplicate files on disk.

### 4.2 Mode 2: Physical Import (Workspace Clone)

- **Behavior**: Mobile Harness recursively copies the skill directory from the source (Project A or Global) into the active workspace's primary skills directory:
  `<active_workspace>/.agents/skills/<skill-name>/`
- **Execution**:
  1. Validates that `<skill-name>` does not collide with an existing folder in the active workspace (or prompts user for overwrite/rename).
  2. Copies `SKILL.md` and all auxiliary scripts, templates, or resources.
  3. Registers the new skill as a native `SkillSource.PROJECT` skill.
- **Benefits**:
  - Independent lifecycle: Can be edited, modified, and customized specifically for Project B.
  - Version control: The imported skill is committed to Project B's Git repository.
  - Works offline even if the other project is pruned or archived.

### 4.3 Mode 3: Promote to Global Library

- **Behavior**: Copies a project-local skill into the Global Skills Directory:
  - Primary Android Host: `File(context.filesDir, "skills/<skill-name>")`
  - PRoot Linux Guest Mirror: `File(rootfs, "root/.gemini/config/skills/<skill-name>")`
- **Execution**:
  - Validates skill frontmatter (`name`, `description`).
  - Writes standard `SKILL.md` and related files to the global location.
  - All registered projects can now immediately discover and enable it under their Global Skills list.

---

## 5. Detailed Technical Specifications

### 5.1 Data Model Extensions

In [`app/src/main/java/com/jarves/mh/model/Models.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/model/Models.kt):

```kotlin
enum class SkillSource(val title: String) {
    PROJECT("Project Local"),
    LINKED("Linked from Other Project"),
    GLOBAL("Global Library"),
    BUNDLED("Built-in System"),
    OTHER_PROJECT("Available in Other Project");
}

data class LinkedSkillReference(
    val id: String = UUID.randomUUID().toString(),
    val sourceProjectId: String,
    val sourceProjectName: String,
    val skillName: String,
    val relativeSkillPath: String, // e.g. ".agents/skills/my-skill"
    val enabledAtMillis: Long = System.currentTimeMillis(),
)

data class ProjectSkillConfig(
    val projectId: String,
    val enabledSkillIds: Set<String> = emptySet(),
    val disabledSkillIds: Set<String> = emptySet(),
    val linkedSkills: List<LinkedSkillReference> = emptyList(),
)

data class SkillInfo(
    val id: String,
    val name: String,
    val description: String,
    val filePath: String,
    val source: SkillSource,
    val isEnabled: Boolean = true,
    val markdownContent: String? = null,
    val sourceProjectId: String? = null,
    val sourceProjectName: String? = null,
    val isReadOnly: Boolean = false,
    val isMissingSource: Boolean = false,
)
```

### 5.2 Persistence Layer (`AppPreferences.kt` / Store)

In [`app/src/main/java/com/jarves/mh/data/AppPreferences.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/data/AppPreferences.kt):

```kotlin
fun saveProjectSkillConfig(config: ProjectSkillConfig) {
    val key = "skill_config_${config.projectId}"
    val json = JSONObject().apply {
        put("projectId", config.projectId)
        put("enabledSkillIds", JSONArray(config.enabledSkillIds))
        put("disabledSkillIds", JSONArray(config.disabledSkillIds))
        val linksArr = JSONArray()
        config.linkedSkills.forEach { ref ->
            linksArr.put(JSONObject().apply {
                put("id", ref.id)
                put("sourceProjectId", ref.sourceProjectId)
                put("sourceProjectName", ref.sourceProjectName)
                put("skillName", ref.skillName)
                put("relativeSkillPath", ref.relativeSkillPath)
                put("enabledAtMillis", ref.enabledAtMillis)
            })
        }
        put("linkedSkills", linksArr)
    }
    preferences.edit().putString(key, json.toString()).apply()
}

fun loadProjectSkillConfig(projectId: String): ProjectSkillConfig {
    val key = "skill_config_$projectId"
    val raw = preferences.getString(key, null) ?: return ProjectSkillConfig(projectId)
    return runCatching {
        val obj = JSONObject(raw)
        val enabled = mutableSetOf<String>()
        obj.optJSONArray("enabledSkillIds")?.let { arr ->
            for (i in 0 until arr.length()) enabled.add(arr.getString(i))
        }
        val disabled = mutableSetOf<String>()
        obj.optJSONArray("disabledSkillIds")?.let { arr ->
            for (i in 0 until arr.length()) disabled.add(arr.getString(i))
        }
        val links = mutableListOf<LinkedSkillReference>()
        obj.optJSONArray("linkedSkills")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                links.add(
                    LinkedSkillReference(
                        id = item.optString("id", UUID.randomUUID().toString()),
                        sourceProjectId = item.getString("sourceProjectId"),
                        sourceProjectName = item.optString("sourceProjectName", "Other Project"),
                        skillName = item.getString("skillName"),
                        relativeSkillPath = item.getString("relativeSkillPath"),
                        enabledAtMillis = item.optLong("enabledAtMillis", System.currentTimeMillis()),
                    )
                )
            }
        }
        ProjectSkillConfig(projectId, enabled, disabled, links)
    }.getOrDefault(ProjectSkillConfig(projectId))
}
```

### 5.3 Discovery & Management Engine (`SkillManager.kt`)

Extend [`app/src/main/java/com/jarves/mh/runtime/SkillManager.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/runtime/SkillManager.kt) with cross-project and federated methods:

```kotlin
/**
 * Scans all registered projects to catalog every available skill across the workspace database.
 */
fun discoverAllProjectsSkills(
    allProjects: List<Project>,
    currentProjectId: String?,
    workspacesBaseDir: File
): Map<Project, List<SkillInfo>> {
    val catalog = mutableMapOf<Project, List<SkillInfo>>()
    allProjects.filter { it.id != currentProjectId }.forEach { project ->
        val projectDir = File(workspacesBaseDir, project.id)
        if (projectDir.isDirectory) {
            val skills = scanProjectWorkspaceForSkills(projectDir, SkillSource.OTHER_PROJECT)
                .map { it.copy(sourceProjectId = project.id, sourceProjectName = project.name) }
            if (skills.isNotEmpty()) {
                catalog[project] = skills
            }
        }
    }
    return catalog
}

/**
 * Resolves the active skills for a specific project combining Local, Linked, Global, and Bundled.
 */
fun compileActiveProjectSkills(
    activeProject: Project,
    config: ProjectSkillConfig,
    allProjects: List<Project>,
    workspacesBaseDir: File,
    rootfsDir: File? = null,
): List<SkillInfo> {
    val result = mutableListOf<SkillInfo>()
    val seenNames = mutableSetOf<String>()

    // 1. Current Project Local Skills (Highest precedence)
    val localDir = File(workspacesBaseDir, activeProject.id)
    val localSkills = scanProjectWorkspaceForSkills(localDir, SkillSource.PROJECT)
    localSkills.forEach { skill ->
        val isEnabled = skill.id !in config.disabledSkillIds
        if (seenNames.add(skill.name.lowercase())) {
            result.add(skill.copy(isEnabled = isEnabled))
        }
    }

    // 2. Linked Skills from other projects
    config.linkedSkills.forEach { link ->
        val sourceProject = allProjects.firstOrNull { it.id == link.sourceProjectId }
        val sourceDir = File(workspacesBaseDir, link.sourceProjectId)
        val skillFile = File(sourceDir, "${link.relativeSkillPath}/SKILL.md")
        if (skillFile.isFile) {
            parseSkillFile(skillFile, SkillSource.LINKED)?.let { parsed ->
                if (seenNames.add(parsed.name.lowercase())) {
                    val isEnabled = parsed.id !in config.disabledSkillIds
                    result.add(
                        parsed.copy(
                            id = "linked:${link.sourceProjectId}:${parsed.name}",
                            sourceProjectId = link.sourceProjectId,
                            sourceProjectName = link.sourceProjectName,
                            isEnabled = isEnabled,
                            isReadOnly = true,
                        )
                    )
                }
            }
        } else {
            // Source was deleted or moved
            result.add(
                SkillInfo(
                    id = "linked:${link.sourceProjectId}:${link.skillName}",
                    name = link.skillName,
                    description = "Source file missing from ${link.sourceProjectName}",
                    filePath = skillFile.absolutePath,
                    source = SkillSource.LINKED,
                    isEnabled = false,
                    sourceProjectId = link.sourceProjectId,
                    sourceProjectName = link.sourceProjectName,
                    isMissingSource = true,
                )
            )
        }
    }

    // 3. Global Skills (Host + PRoot Linux)
    val globalSkills = discoverGlobalAndLinuxSkills(rootfsDir)
    globalSkills.forEach { skill ->
        if (seenNames.add(skill.name.lowercase())) {
            val isEnabled = (config.enabledSkillIds.contains(skill.id) || skill.id !in config.disabledSkillIds)
            result.add(skill.copy(isEnabled = isEnabled))
        }
    }

    // 4. Bundled Built-in Skills
    val bundled = discoverBundledSkills()
    bundled.forEach { skill ->
        if (seenNames.add(skill.name.lowercase())) {
            val isEnabled = skill.id !in config.disabledSkillIds
            result.add(skill.copy(isEnabled = isEnabled))
        }
    }

    return result
}

/**
 * Copies a skill folder from any source project or global into target project workspace.
 */
fun importSkillToProject(
    sourceSkillDir: File,
    targetWorkspaceDir: File,
    skillName: String
): File {
    val targetDir = File(targetWorkspaceDir, ".agents/skills/$skillName")
    require(targetDir.canonicalFile.toPath().startsWith(targetWorkspaceDir.canonicalFile.toPath())) {
        "Target escapes workspace directory"
    }
    sourceSkillDir.copyRecursively(targetDir, overwrite = true)
    return targetDir
}

/**
 * Promotes a local skill to Global storage for universal availability.
 */
fun promoteSkillToGlobal(sourceSkillDir: File, skillName: String): File {
    val targetDir = File(globalSkillsDir, skillName).apply { mkdirs() }
    sourceSkillDir.copyRecursively(targetDir, overwrite = true)
    return targetDir
}
```

---

## 6. UI / UX Design Specifications

### 6.1 Unified Skills Hub (`CliParityComponents.kt`)

Replace the existing basic dialog in [`app/src/main/java/com/jarves/mh/ui/CliParityComponents.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/ui/CliParityComponents.kt#L815-L935) with a full-featured **Skills Hub Sheet**:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ ✦ Skills & Customization Hub                                    [ Close ✕ ] │
├─────────────────────────────────────────────────────────────────────────────┤
│ [ Active in Project (4) ]  [ Global Library (6) ]  [ Other Projects (8) ]   │
├─────────────────────────────────────────────────────────────────────────────┤
│ 🔍 Search skills by name, description, or tag...                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  [Tab: Other Projects Selected]                                             │
│                                                                             │
│  ▼ Project: "kalam-backend-api" (3 skills available)                        │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ ⚡ postgres-query-optimizer                                           │  │
│  │    Index analysis, EXPLAIN ANALYZE guidance, and connection pool tuning.│  │
│  │    Location: .agents/skills/postgres-query-optimizer/SKILL.md         │  │
│  │                                                                       │  │
│  │    [ 🔗 Enable in Project ]   [ ⤓ Copy to Workspace ]   [ ★ Make Global ] │
│  └───────────────────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ 🛡️ jwt-auth-validator                                                 │  │
│  │    Token validation, expiration checks, and rotation workflows.       │  │
│  │                                                                       │  │
│  │    [ 🔗 Enable in Project ]   [ ⤓ Copy to Workspace ]   [ ★ Make Global ] │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  ▶ Project: "swift-ios-client" (5 skills available)                         │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│ 💡 Tip: Linking a skill enables it in this project without copying files.   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.2 Key UI Components & Interactions

1. **Active in Project Tab**:
   - Lists all skills currently injected into the AI model's context for this workspace.
   - Distinct badge pills:
     - `[Local Workspace]` (PocketBlue)
     - `[Linked from Project X]` (PocketGreen)
     - `[Global]` (PocketOrange)
     - `[Built-in]` (Gray)
   - Toggles: Instant ON/OFF switch with persistent auto-saving.
   - Quick Actions:
     - For Local: "Promote to Global", "View/Edit SKILL.md", "Delete".
     - For Linked: "Clone to Workspace (Detach)", "Remove Link", "View SKILL.md".
2. **Global Library Tab**:
   - Shows all skills stored in `filesDir/skills/` and PRoot `/root/.gemini/config/skills/`.
   - "Enable in this project" toggle switch.
   - Action to "Create New Global Skill".
3. **Other Projects Browser Tab**:
   - Grouped accordion list of all projects registered in Mobile Harness.
   - Each card displays:
     - Skill name and description extracted from frontmatter.
     - Origin project name and relative path.
     - **Action 1: [ 🔗 Enable (Link) ]**: Adds to `ProjectSkillConfig.linkedSkills`. Instantly marks skill active in this project without disk overhead.
     - **Action 2: [ ⤓ Copy to Workspace ]**: Clones directory to `.agents/skills/`. Gives this project an independent, editable copy.
     - **Action 3: [ ★ Make Global ]**: Promotes to global so all present and future projects gain access.
4. **Collision & Conflict Handler**:
   - If importing a skill with a name that already exists in the destination project:
     - Dialog options:
       - **Rename**: Automatically appends a suffix (e.g. `postgres-optimizer-imported`).
       - **Overwrite**: Replaces the destination folder after confirmation.
       - **Cancel**: Aborts without making changes.

---

## 7. CLI Slash Commands & Agent Tooling

To ensure 100% parity between UI and CLI terminals:

### 7.1 New Slash Commands in [`SlashCommandEngine.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/runtime/SlashCommandEngine.kt)

| Command | Syntax | Description |
|---|---|---|
| `/skills` | `/skills [all \| active \| global \| other]` | Lists skills with source, status, and project origins. |
| `/skill link` | `/skill link <projectName> <skillName>` | Virtually links an external project's skill into the current workspace. |
| `/skill unlink` | `/skill unlink <skillName>` | Removes a linked skill from the current workspace config. |
| `/skill import` | `/skill import <projectName> <skillName>` | Copies an external skill directory into `.agents/skills/<skillName>`. |
| `/skill promote` | `/skill promote <skillName>` | Promotes a local workspace skill to the Global Library. |

### 7.2 System Prompt Envelope Format

When any bridge ([`AntigravityRuntimeBridge`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/runtime/AntigravityRuntimeBridge.kt), [`ClaudeRuntimeBridge`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/runtime/ClaudeRuntimeBridge.kt), or [`DshRuntimeBridge`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/runtime/DshRuntimeBridge.kt)) compiles the runtime prompt, active linked and global skills are formatted identically into the progressive disclosure index:

```xml
<skills>
You can use specialized 'skills' to help you with complex tasks.
Each skill folder contains a SKILL.md file with detailed instructions and runbooks.
If a skill seems relevant to your current task, view its SKILL.md file using view_file before proceeding.

Available skills:
- postgres-query-optimizer (/workspace/clever-kalam/.agents/skills/postgres-query-optimizer/SKILL.md): Index analysis, EXPLAIN ANALYZE guidance, and connection pool tuning. [Source: Project Local]
- jwt-auth-validator (/data/user/0/com.jarves.mh.dev/files/workspaces/proj-123/.agents/skills/jwt-auth-validator/SKILL.md): Token validation, expiration checks, and rotation workflows. [Source: Linked from kalam-backend-api]
- android-developer (/data/user/0/com.jarves.mh.dev/files/builtin_skills/android-developer/SKILL.md): Android expert for Gradle, Jetpack Compose, build variants, APK packaging, and Android SDK debugging. [Source: Built-in]
</skills>
```

---

## 8. Security & Sandboxing Architecture

1. **Path Traversal Defense**:
   - All source and target directory lookups are validated using `canonicalPath.startsWith(...)` against:
     - `context.filesDir/workspaces/`
     - `context.filesDir/skills/`
     - `context.filesDir/builtin_skills/`
     - Linux PRoot `/root/.gemini/config/`
   - Rejects any relative `..` sequences attempting to traverse outside Mobile Harness boundaries.
2. **Read-Only Enclosure for Linked References**:
   - When a skill is linked from another project, its `isReadOnly` flag is strictly enforced.
   - The UI does not provide an "Edit" option for linked skills unless the user chooses "Clone to Workspace" or switches to the owning project.
   - Prevents unintended cross-project state corruption or accidental deletion.
3. **Symlink Loop & Recursion Protection**:
   - Directory cloning utilizes bounded recursion with canonical path cycle detection to avoid circular symlink loops.

---

## 9. Implementation Milestones

| Milestone | Key Deliverables | Primary Files |
|---|---|---|
| **Phase 1: Models & Persistence** | Define `LinkedSkillReference`, `ProjectSkillConfig`, extend `SkillInfo` and `SkillSource`. Implement JSON serialization in `AppPreferences`. | [`Models.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/model/Models.kt), [`AppPreferences.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/data/AppPreferences.kt) |
| **Phase 2: Federation Engine** | Implement `discoverAllProjectsSkills`, `compileActiveProjectSkills`, `importSkillToProject`, and `promoteSkillToGlobal` in `SkillManager`. | [`SkillManager.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/runtime/SkillManager.kt) |
| **Phase 3: ViewModel Wiring** | Wire project switching in `MainViewModel` to load persistent `ProjectSkillConfig`, compile active federated skills, and handle link/import/promote events. | [`MainViewModel.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/ui/MainViewModel.kt) |
| **Phase 4: UI Implementation** | Build 3-tab Skills Hub in Jetpack Compose with search, project grouping, and action buttons (`Link`, `Import`, `Make Global`). | [`CliParityComponents.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/ui/CliParityComponents.kt) |
| **Phase 5: Slash Commands & Tooling** | Update `/skills`, `/skill link`, `/skill import`, `/skill promote` handlers in `SlashCommandEngine` and bridge prompt injection. | [`SlashCommandEngine.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/runtime/SlashCommandEngine.kt) |
| **Phase 6: Quality Gate & Verification** | Comprehensive unit tests for cross-project discovery, cloning, linking, path security, and Gradle compilation check. | [`SkillManagerTest.kt`](file:///workspace/clever-kalam/app/src/test/java/com/jarves/mh/runtime/SkillManagerTest.kt) |

---

## 10. Verification & Acceptance Criteria

### 10.1 Functional Test Cases

1. **Cross-Project Discovery**:
   - Given Project A has `.agents/skills/analytics-skill/SKILL.md`.
   - When the user opens Project B and navigates to *Skills Hub → Other Projects*.
   - Then `analytics-skill` is visible under Project A's accordion with its description and location.
2. **Virtual Linking**:
   - When the user clicks `[🔗 Enable in Project]` on Project A's `analytics-skill` while in Project B.
   - Then the skill appears in Project B's *Active in Project* tab with `[Linked from Project A]` badge.
   - Then the `<skills>` block in the subsequent AI prompt contains `analytics-skill`.
   - When Project B is closed and reopened, `analytics-skill` remains enabled (persistent).
3. **Physical Import (Cloning)**:
   - When the user clicks `[⤓ Copy to Workspace]` on `analytics-skill`.
   - Then Project B's filesystem contains `.agents/skills/analytics-skill/SKILL.md`.
   - Then the skill source switches to `[Project Local]` and becomes fully editable.
4. **Promote to Global**:
   - When the user clicks `[★ Make Global]` on a local skill in Project B.
   - Then the skill is copied to `context.filesDir/skills/<skill-name>`.
   - Then all existing projects immediately show the skill under their *Global Library* tab.
5. **Path Traversal Defense**:
   - Calling import or link with malicious path patterns (`../../etc/shadow`) throws `IllegalArgumentException` and rejects execution.

### 10.2 Build & Integration Gates

- `./gradlew :app:compileOnlineDebugKotlin` executes cleanly with 0 compilation errors.
- `./gradlew :app:testOnlineDebugUnitTest` passes 100% of unit tests.
- Android debug APK is generated and deployed in compliance with project rules:
  - Copied to `app-online-debug.apk` and `mobile-harness-dev.apk`.
  - File size and MD5 checksums reported.
