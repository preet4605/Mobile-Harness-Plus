# UI/UX direction

Preserve the existing Android Compose design: Liquid Glass navigation over solid neutral content, deliberate typography and familiar mobile interactions. Reuse current components and tokens; avoid generic templates, arbitrary gradients and one-off styling.

## Existing design system

| Concern | Source and direction |
| --- | --- |
| Spacing and radii | [DesignTokens.kt](../app/src/main/java/com/jarves/mh/ui/theme/DesignTokens.kt): PocketSpacing uses 2/4/8/12/16/20/24/32 dp; PocketRadius uses 6/10/14/20/28 dp plus the full-radius token. |
| Color | [Theme.kt](../app/src/main/java/com/jarves/mh/ui/theme/Theme.kt), [PocketColors.kt](../app/src/main/java/com/jarves/mh/ui/theme/PocketColors.kt): semantic light/dark roles, neutral surfaces, orange primary with blue/green accents. Use roles rather than literal colors. |
| Typography | [PocketType.kt](../app/src/main/java/com/jarves/mh/ui/theme/PocketType.kt): Inter-backed reading hierarchy and monospaced code/log roles; respect font scaling. |
| Motion | PocketMotion in DesignTokens.kt and [PocketTransitions.kt](../app/src/main/java/com/jarves/mh/ui/theme/PocketTransitions.kt): shared interruptible springs and consistent press/navigation responses. |
| Glass | [LiquidGlassConfig.kt](../app/src/main/java/com/jarves/mh/ui/theme/glass/LiquidGlassConfig.kt) and existing glass host/engine: controlled capture/effects and accessibility fallbacks. |

## Codex account controls

Agent → Codex → ChatGPT lists accounts with Default, In use, Disabled and Needs sign-in labels. Existing grouped rows and options menus provide Set default, Rename, Reconnect, Enable/Disable and confirmed Remove; Add account uses the existing device-code UI. Default switching explains that future tasks can send visible history to the chosen account. Running/queued work retains its binding. Usage identifies the selected account and last refresh; no background quota polling or automatic rotation is implied. Long-label/large-text fixture compiles with the app tests; native device appearance and two real logins still require review.

## Current screens and flows

Screen names and behavior follow [PocketDevApp](../app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt); the complete feature list is in [prd.md](prd.md#current-feature-inventory).

| Surface | Interaction direction |
| --- | --- |
| Setup | Compatibility/toolchain selection, installation progress and expandable/copyable logs; separate notification/battery guidance and provider/account setup. Preserve retry, error and offline states. |
| Projects | Searchable project list; named project, quick chat, ZIP import, public Git and authenticated GitHub entry points. Rename/delete confirmations and update banner/sheet must preserve running-work restrictions. |
| Agent | Engine selection/install/update and provider/model/effort controls; saved-key and account status, usage/quota reporting and supported Google routing controls. Keep CLI login, subscription tokens and API keys visually distinct. |
| Settings | System/light/dark appearance and Reduce transparency; toolchain add/remove, runtime versions, terminal-history clearing, on-demand Diagnostics/Refresh/cache cleanup, developer-settings shortcut, version/What's new/privacy/upstream links. Update-manifest override is debug-only. |
| Workspace | Chat, Files, Terminal and Preview views; chats picker and selective ZIP export; changes sheet and composer tools for attachments/camera, commands, skills and Activity. Files has line-numbered text editing, conflict/reload actions and file/folder operations, plus Android build/progress/issue/cancel controls. Memory is a labelled composer-tools action; the title menu exposes full chat/project/provider names. Busy chats show queued follow-ups with Steer and Remove. Mods switched on for projects add status chips and a height-capped row above the composer, and a Mods view that appears only while a mod panel is switched on. |
| Mods | My mods and Studio; each mod page has Dashboard (its panes drawn from mod trees with kit components, a pane switcher and action rows), Controls (including per-place "In projects" switches), Permissions, Activity and About. Mod trees draw only while shown and show a plain reason when the mod isn't running or a tree was refused. |
| Chat/history | Markdown and code copying, supported Run in Terminal actions, attachments, loading/older pages and Latest navigation. Read-only history has a clear banner and Continue here eligibility rather than an active composer. |
| Context sheets | Skills/rules scope and source badges, toggles, linking/import/promotion, rule/skill forms and prompt preview. Memory distinguishes automatic/user facts and confirms destructive clear/delete actions. |
| Activity/review | One collapsed disclosure per work block, with explicit supervisor outcome and live status; expandable events and logs/transcripts; reported usage, subagents/tasks/artifacts/timers; change diffs and per-file/all Keep/Undo. Unsupported child stopping must not imply a child was killed. |
| Terminal/Preview | Mobile command/history/cursor controls and clear/interrupt/Stop states; local Preview address/open/refresh/loading states. A blocked external URL needs an explicit error. |

## Interaction requirements

- Preserve project navigation, chat, file review, terminal and Preview behavior. Keep paged history and the intended first visible chat position.
- Keep the composer usable with the keyboard, long text, slash commands and skill mentions. Preserve cursor selection and stable loading/tab-switch layouts.
- Show useful loading, empty and failure states. Reject stale project/chat/file results; slow work must not publish into a newer screen.
- Explain actual task/subagent controls and unavailable capabilities. Use supported Stop/review actions and preserve ownership.
- Reuse shared sheets, dialogs, rows and buttons. Navigation may use glass; content needs readable solid surfaces and restrained decoration.

## Planned card refinement — Chat/Projects screenshot follow-up

Requested on 2026-10-12; this is design direction, not implemented behavior. Give each recent project its own compact card so separate workspaces are easy to scan. Use a solid neutral surface, a quiet semantic outline, consistent rounded corners and the existing spacing tokens. Emphasize the project name above language/updated-time metadata, with a small tinted initials tile for identity. Show a labelled running state only when real work is active.

Keep creation/import actions in a compact grouped panel with aligned icons and subtle inset separators. Use restrained accent color to distinguish actions from project content; put account details on a secondary line. Keep chat activity as a lightweight disclosure associated with the answer footer rather than introducing large cards around every message.

Reuse SectionCard/ListRow and MonogramTile where practical, preserving click/long-press/accessibility actions and stable project identity. Use the existing press highlight, adaptive row height, light/dark semantic colors and readable text. Reserve glass for navigation. Review the resulting density with long names and larger text before finalizing; the desired result is clearer grouping without large empty card interiors. Scope this refinement to these screens rather than restyling every shared card globally.

## Accessibility and responsive behavior

Support edge-to-edge/system insets, dynamic window sizes, font scaling, readable contrast and meaningful semantics. Aim for WCAG accessibility requirements and appropriate touch targets. Honor Reduce Motion, Reduce Transparency and increased-contrast settings; retain solid fallbacks. Reduce transparency has an app toggle as well as system input; motion/contrast handling comes from the existing system/configuration integration, not separate Settings toggles.

The workspace header has an opaque reading region with a short scroll-edge fade; measured clearances still protect content and the keyboard dock. Header/tab text follows system scaling and grows vertically; full titles remain available in the view menu. Shared interactive targets use at least 48 dp. Badges use onPrimary, active disclosure/copy glyphs use secondaryLabel, and assistant/user reading text uses PocketType.body. Other fixed-height chrome retains its existing scaling policy.

## Verification

Use focused interaction/regression coverage and opt-in screenshot fixtures. Inspect changed flows on-device when possible, including keyboard, loading, scrolling, light/dark themes and accessibility settings. Measure frame timing when changing glass, motion or rendering. Documentation alone does not establish device performance or accessibility compliance. See [architecture.md](architecture.md#verification-and-distribution) and [tasks.md](tasks.md).
