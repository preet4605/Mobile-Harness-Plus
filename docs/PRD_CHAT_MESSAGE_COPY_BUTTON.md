# PRD: Chat Message Copy Button ("MessageCopyAffordance")

**Feature Codename:** `MessageCopyAffordance`  
**Status:** Ready for Review  
**Target:** Mobile Harness (`PocketDevApp.kt`)  
**Design Persona:** Principal UI/UX Designer & Frontend Craftsman  
**Architecture Lead:** Senior Software Architect  

---

## 1. Executive Summary & Problem Statement

Currently in Mobile Harness chat ([`PocketDevApp.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt)), individual code blocks rendered inside [`MarkdownText.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/ui/MarkdownText.kt) feature a convenient "Copy code" button in the block header. Text inside message bubbles is wrapped in a `SelectionContainer`, allowing standard Android touch-selection handles.

However, copying an **entire message** (such as a multi-step architectural plan, full prompt, diagnostics report, or composite answer containing both prose and code) currently requires dragging text selection pins across the entire screen. On mobile touchscreens, this is tedious, error-prone, and frustrates developer velocity.

### Core Solution
Introduce a dedicated, low-profile **Message Copy Button** at the footer of each chat bubble (supporting both assistant responses and user inputs). A single tap copies the exact raw markdown/text of the message to the system clipboard, delivers immediate visual checkmark confirmation, and triggers subtle haptic feedback.

---

## 2. Goals & Non-Goals

### 2.1 Goals
- **G1 (1-Tap Full Copy)**: Allow users to copy the entire raw content (`message.text`) of any chat message in a single tap.
- **G2 (Fidelity & Markdown Preservation)**: Preserve exact formatting, indentation, code fences, line breaks, and characters when copied to the clipboard.
- **G3 (Anti-Slop UI & Ergonomics)**: Clean Material 3 action footer that integrates seamlessly with existing metadata (such as `workedMillis` duration chips), avoiding visual clutter or bulky card overlays.
- **G4 (Clear Micro-Feedback)**: Instant state change (<200ms) with a checkmark icon transition, brand color accent (`PocketOrange` / `PocketGreen`), haptic pulse, and 2-second automatic revert.
- **G5 (Accessibility)**: Explicit `contentDescription` states (`"Copy entire message"` and `"Message copied"`), WCAG AA contrast compliance, and compliant touch target sizing.

### 2.2 Non-Goals
- **NG1**: Modifying code block copy buttons inside [`MarkdownText.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/ui/MarkdownText.kt) (they remain independently functional for granular snippet extraction).
- **NG2**: Copying raw attachment binary payloads to the clipboard (attachments have their own chip actions).
- **NG3**: Copying internal tool-call execution metadata from [`WorkBlockCard`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt#L5316) items (which represent internal harness activities, not conversational text).

---

## 3. User Experience & Design Specification

### 3.1 Bubble Footer Layout & Positioning

Message bubbles in [`MessageBubble`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt#L5635) currently have the following vertical structure:
1. Active Skill Badge (User only, optional)
2. Message Text (`SelectionContainer` + `Text` for user, `MarkdownText` for assistant)
3. "Worked for Xs" timing line (Assistant only, optional)
4. Attachment Chips (Optional)

#### Proposed Layout Enhancement:
Introduce an integrated **Action Footer Row** at the bottom of the bubble:
- **Assistant Bubble (`fromUser == false`)**:
  - Full-width row at bottom of bubble (`Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)`).
  - Left side: "Worked for Xs" timing chip (if `workedMillis > 0L`).
  - Right side: Message copy button.
- **User Bubble (`fromUser == true`)**:
  - Right-aligned row at bottom of bubble (`Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)`).
  - Right side: Message copy button with subtle opacity matching `onPrimaryContainer`.

#### Visual Mockup (ASCII):

**Assistant Message Bubble:**
```text
┌──────────────────────────────────────────────────────────────┐
│  Here is the updated configuration for AAPT2 override:      │
│                                                              │
│  ```properties                                               │
│  android.aapt2FromMavenOverride=/root/android-sdk/...        │
│  ```                                                         │
│                                                              │
│  ⏱ Worked for 4s                                    [📋 Copy] │
└──────────────────────────────────────────────────────────────┘
```

**Copied State Feedback (2000ms):**
```text
┌──────────────────────────────────────────────────────────────┐
│  ⏱ Worked for 4s                                    [✓ Copied]│
└──────────────────────────────────────────────────────────────┘
```

**User Message Bubble:**
```text
                        ┌──────────────────────────────────────┐
                        │ Can you plan a PRD for the copy btn? │
                        │                            [📋 Copy] │
                        └──────────────────────────────────────┘
```

### 3.2 Visual & Micro-Interaction Tokens

| Element | Default State | Copied State (Active for 2000ms) |
|---|---|---|
| **Icon Vector** | `Icons.Default.ContentCopy` | `Icons.Default.Check` |
| **Assistant Tint** | `MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)` | `PocketOrange` (Hex `#FF6D00` / `#E65100`) |
| **User Tint** | `MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)` | `PocketGreen` (Hex `#00C853`) |
| **Button Size** | 28.dp bounding box (minimum 40dp ripple semantics) | 28.dp bounding box |
| **Icon Size** | 15.dp | 15.dp |
| **Haptic Feedback** | N/A | `HapticFeedbackType.LongPress` / Light pulse |
| **Accessibility Desc**| `"Copy entire message"` | `"Message copied to clipboard"` |

---

## 4. Technical Architecture & Implementation Details

### 4.1 Affected Components
- **File**: [`PocketDevApp.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt)
- **Primary Composable**: `MessageBubble(message: ChatMessage, onRunInTerminal: (String) -> Unit, onOpenAttachment: (ChatAttachment) -> Unit)`
- **Dependencies**:
  - `androidx.compose.ui.platform.LocalClipboardManager`
  - `androidx.compose.ui.platform.LocalHapticFeedback`
  - `androidx.compose.ui.hapticfeedback.HapticFeedbackType`
  - `androidx.compose.ui.text.AnnotatedString`
  - `androidx.compose.material.icons.filled.ContentCopy`
  - `androidx.compose.material.icons.filled.Check`

### 4.2 State Management & Concurrency
Each `MessageBubble` maintains its own lightweight, isolated copy state:
```kotlin
var isCopied by rememberSaveable(message.id) { mutableStateOf(false) }
var resetJob by remember { mutableStateOf<Job?>(null) }
val clipboardManager = LocalClipboardManager.current
val hapticFeedback = LocalHapticFeedback.current
val scope = rememberCoroutineScope()
```

#### On Click Flow:
1. **Filter Empty**: If `message.text.isBlank()`, suppress copy action.
2. **Clipboard Write**: `clipboardManager.setText(AnnotatedString(message.text))`
3. **Haptic Pulse**: `hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)`
4. **State Transition**: `isCopied = true`
5. **Debounced Reset Job**:
   ```kotlin
   resetJob?.cancel()
   resetJob = scope.launch {
       delay(2000L)
       isCopied = false
   }
   ```

### 4.3 Proposed Implementation in `MessageBubble`

```kotlin
@Composable
private fun MessageBubble(
    message: ChatMessage,
    onRunInTerminal: (String) -> Unit,
    onOpenAttachment: (ChatAttachment) -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var isCopied by remember { mutableStateOf(false) }
    var resetJob by remember { mutableStateOf<Job?>(null) }

    val copyAction = {
        if (message.text.isNotBlank()) {
            clipboardManager.setText(AnnotatedString(message.text))
            try {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            } catch (_: Exception) {}
            isCopied = true
            resetJob?.cancel()
            resetJob = scope.launch {
                delay(2000L)
                isCopied = false
            }
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (message.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(if (message.fromUser) 0.85f else 1f),
        ) {
            Column(Modifier.padding(top = 12.dp)) {
                SelectionContainer {
                    if (message.fromUser) {
                        // User message content
                        ...
                    } else {
                        // Assistant markdown content
                        ...
                    }
                }

                // Attachments section if present
                if (message.attachments.isNotEmpty()) {
                    ...
                }

                // Action Footer Row: timing and copy button
                if (message.text.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 14.dp,
                                end = if (message.fromUser) 8.dp else 10.dp,
                                top = 2.dp,
                                bottom = 6.dp,
                            ),
                        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!message.fromUser) {
                            if (message.workedMillis > 0L) {
                                Text(
                                    text = "Worked for ${formatDuration((message.workedMillis / 1_000L).coerceAtLeast(1L))}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                )
                            } else {
                                Spacer(Modifier.width(1.dp))
                            }
                        }

                        IconButton(
                            onClick = copyAction,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                contentDescription = if (isCopied) "Message copied to clipboard" else "Copy entire message",
                                tint = when {
                                    isCopied && message.fromUser -> PocketGreen
                                    isCopied -> PocketOrange
                                    message.fromUser -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                },
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}
```

---

## 5. Edge Cases & Boundary Handling

1. **Blank / Empty Message Content**:
   - If `message.text` is empty (e.g. system markers or pure attachment cards), the copy button is omitted to prevent placing empty strings into the user's clipboard.
2. **Extremely Large Messages**:
   - Messages containing extensive logs or multi-thousand-line outputs copy instantaneously via `LocalClipboardManager` without blocking the main composition thread.
3. **Repeated Rapid Taps**:
   - Repeated tapping restarts the 2000ms delay timer without causing duplicate clipboard alerts or UI stutter.
4. **Haptic Feedback Failure**:
   - Guarded in a safe `try-catch` block so devices without vibration motors or disabled haptic permissions do not crash or log exceptions.
5. **Streaming / Incremental Messages**:
   - If an assistant message is streaming in real time, tapping copy copies the content generated up to that moment.

---

## 6. Verification & Test Plan

### 6.1 Unit Tests
- Add unit test in [`PocketDevChatTest.kt`](file:///workspace/clever-kalam/app/src/test/java/com/jarves/mh/ui/) verifying:
  - Copy action retains raw multiline strings with verbatim spacing and markdown syntax.
  - Whitespace-only messages are identified as non-copyable.

### 6.2 Compilation & Build Gates
- Execute standard project verification:
  1. `./gradlew :app:compileOnlineDebugKotlin`
  2. `./gradlew :app:testOnlineDebugUnitTest`
  3. `./gradlew assembleOnlineDebug`
- Ensure resulting APKs are placed in root:
  - `app-online-debug.apk`
  - `mobile-harness-dev.apk`
  - Report exact file size and MD5 hash.

### 6.3 Manual QA Checklist
- [ ] Send a message as user -> verify copy button appears on user bubble.
- [ ] Tap copy on user message -> verify checkmark appears, haptic pulses, text pastes accurately into an external app.
- [ ] Receive assistant reply with markdown and code blocks -> verify copy button appears next to "Worked for Xs".
- [ ] Tap copy on assistant message -> verify full markdown including code blocks is copied.
- [ ] Verify existing code-block copy buttons inside `MarkdownText` still function independently.
- [ ] Switch theme (Dark / Light) -> verify copy icon contrast remains accessible and legible.

---

## 7. Phasing & Implementation Steps

1. **Phase 1: Composable Update**:
   - Integrate the copy action and footer row into `MessageBubble` in [`PocketDevApp.kt`](file:///workspace/clever-kalam/app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt).
2. **Phase 2: Unit Testing**:
   - Add unit test coverage for copy message sanitization and formatting preservation.
3. **Phase 3: Build Verification & Distribution**:
   - Compile Kotlin, run unit test suite, assemble APK, and verify MD5.
