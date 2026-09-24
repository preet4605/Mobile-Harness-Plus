# PRD: Persistent Context Memory ("AI Brain")

**Feature codename:** `ContextMemory`  
**Status:** Draft  
**Owner:** @user

---

## 1. Problem Statement

Today, context is **harness-local and session-scoped**. When the user:

- switches harness (Claude Code → DeepSeek → Antigravity), or  
- switches provider/model within a harness, or  
- restarts the app

…the new harness starts cold. Even though the full `messages` list is persisted to disk, `AntigravityRuntimeBridge` only injects 4 messages via `buildFailoverPrompt`, and the Claude/DSH bridges send the **entire raw chat log** inside `<conversation_history>` which causes token bloat and no *semantic* continuity once the log grows large. Neither approach gives the AI a stable, structured "brain" that it can reference across all engines.

---

## 2. Goals

| # | Goal |
|---|---|
| G1 | AI retains key facts about the project & user intent **across all harnesses and model switches** |
| G2 | Memory is human-readable & editable in the app |
| G3 | Memory is injected efficiently — small, deterministic, no token explosion |
| G4 | User controls what gets remembered (no silent surveillance) |
| G5 | Works offline / zero cloud dependency |

---

## 3. Architecture

### 3.1 `ContextMemory` Data Model

New file: `data/ContextMemory.kt`

```kotlin
data class MemoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val key: String,          // Short label, e.g. "project-language"
    val value: String,        // The remembered fact, max 500 chars
    val source: MemorySource, // AUTO | USER
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

enum class MemorySource { AUTO, USER }

data class ContextMemory(
    val projectId: String,
    val entries: List<MemoryEntry> = emptyList(),
    val updatedAt: Instant = Instant.now(),
)
```

### 3.2 `ContextMemoryStore` Persistence

New file: `data/ContextMemoryStore.kt`

- Stored as `filesDir/memory/<projectId>.json` (JSON, no extra deps).
- `load(projectId)` / `save(memory)` — synchronized, atomic via `.tmp` → rename.
- `upsert(projectId, key, value, source)` — update if key exists, else append.
- `delete(projectId, entryId)` — remove single entry.
- `clear(projectId)` — wipe all entries.
- Max **50 entries** per project; oldest AUTO entries evicted first when over limit.

### 3.3 Memory Injection via `RuntimeBridge`

All three bridges already accept `history: List<ChatMessage>` and a project context header. We add a new **optional** parameter to `RuntimeBridge.startSession`:

```kotlin
fun startSession(
    projectId: String,
    projectSlug: String,
    projectKind: ProjectKind,
    prompt: String,
    history: List<ChatMessage>,
    provider: ProviderProfile,
    memory: ContextMemory = ContextMemory(projectId),  // NEW
)
```

Each bridge's context-prompt builder gets a new helper:

```kotlin
private fun renderMemoryBlock(memory: ContextMemory): String {
    if (memory.entries.isEmpty()) return ""
    return buildString {
        appendLine("<persistent_memory>")
        appendLine("The following facts were remembered from previous sessions. They remain true across model and harness switches:")
        memory.entries.forEach { entry ->
            appendLine("- ${entry.key}: ${entry.value}")
        }
        appendLine("</persistent_memory>")
    }
}
```

The `<persistent_memory>` block is inserted **before** `<conversation_history>` in all bridges.

For `AntigravityRuntimeBridge`, it is prepended to the `buildFailoverPrompt` / `antigravityWorkspacePrompt` string.

### 3.4 Auto-Memory Extraction

After each `SessionCompleted` event, a lightweight extraction pass runs:

```kotlin
// In MainViewModel, after onRuntimeEvent(SessionCompleted)
extractMemoryFromSession(projectId, messages)
```

Regex/keyword heuristics scan the assistant's last responses for structured patterns (e.g. "I'm using X", "this project is Y"). This is intentionally simple — no extra API call.

**Phase 2 (future):** Optional LLM-based extraction using a cheap model call after session end.

### 3.5 UI: Memory Viewer / Editor

New bottom-sheet accessible from the Auxiliary Inspector or a "Brain" chip in the chat header:

- List of `MemoryEntry` tiles (icon for AUTO vs USER, key/value, delete button).
- FAB or "Add memory" text field to manually create entries.
- "Clear all auto-memories" action.
- Accessible via `/memory` slash command.

### 3.6 `AppUiState` Changes

```kotlin
val contextMemory: ContextMemory = ContextMemory(""),   // NEW
val memoryViewerVisible: Boolean = false,               // NEW
```

### 3.7 `MainViewModel` Changes

```kotlin
// On project open:
_state.update { it.copy(contextMemory = memoryStore.load(projectId)) }

// On sendPrompt:
activeRuntimeRequest = RuntimeRetryRequest(
    ...,
    memory = _state.value.contextMemory,   // NEW
)

// On SessionCompleted:
extractMemoryFromSession(...)
```

---

## 4. Data Flow

```
User taps "Send"
  ├─ messages (full history, persisted per chat)
  └─ contextMemory (≤50 facts, persisted per project)
        │
        ▼
  RuntimeBridge.startSession(…, memory)
   ├─ renderMemoryBlock(memory)  ─► <persistent_memory>
   ├─ buildContextPrompt(history) ► <conversation_history>
   └─ currentPrompt              ► "Now respond to: …"

  AI responds → SessionCompleted
   └─ extractMemoryFromSession(messages)
         └─ upsert facts into ContextMemoryStore
```

---

## 5. What Gets Remembered (Examples)

| Key | Value | Source |
|---|---|---|
| `project-language` | Kotlin | AUTO |
| `project-framework` | Jetpack Compose + Material 3 | AUTO |
| `build-system` | Gradle 8.14.3, AGP 8.11.0 | AUTO |
| `goal` | Mobile Harness — AI coding assistant for Android | AUTO |
| `user-preference` | Always use dark theme | USER |
| `important-file` | PocketDevApp.kt is the main UI entry point | USER |

---

## 6. Scope

| In scope | Out of scope |
|---|---|
| Per-project memory | Cross-project global memory |
| Heuristic auto-extraction | Cloud sync / backup |
| Manual CRUD in UI | LLM-based extraction (Phase 2) |
| All 3 runtime bridges | Antigravity native conversation ID (separate mechanism) |

---

## 7. Files to Create / Modify

| Action | File |
|---|---|
| **CREATE** | `data/ContextMemory.kt` — model |
| **CREATE** | `data/ContextMemoryStore.kt` — persistence |
| **MODIFY** | `runtime/RuntimeBridge.kt` — add `memory` param to `startSession` |
| **MODIFY** | `runtime/AntigravityRuntimeBridge.kt` — inject memory block |
| **MODIFY** | `runtime/ClaudeRuntimeBridge.kt` — inject memory block |
| **MODIFY** | `runtime/DshRuntimeBridge.kt` — inject memory block |
| **MODIFY** | `ui/MainViewModel.kt` — load/save memory, pass to runtime, auto-extract |
| **CREATE** | `ui/MemoryViewerSheet.kt` — Compose bottom-sheet UI |
| **MODIFY** | `ui/PocketDevApp.kt` — wire memory viewer, "Brain" chip in header |
| **MODIFY** | `runtime/SlashCommandEngine.kt` — add `/memory` command |

---

## 8. Implementation Plan

### Phase 1 — Core Memory Wiring
1. Create `ContextMemory` model + `ContextMemoryStore`
2. Add `memory` param to all 3 bridges; inject `<persistent_memory>` block
3. Wire memory load/pass in `MainViewModel`
4. Basic UI: memory viewer sheet

### Phase 2 — Auto-Extraction
5. Heuristic extractor running post-session
6. `/memory add <key> <value>` and `/memory clear` slash commands

### Phase 3 — Polish
7. Memory chip/badge in chat header showing entry count
8. Long-press any assistant message → "Remember this" action
