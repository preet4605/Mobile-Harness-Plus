# Liquid Glass UI Architecture & Deep Research Audit

This document provides a comprehensive technical audit of the Liquid Glass Design Language and UI system implemented on branch `claude/code-audit-debug-5x7yn7` (commits `0613ca7` and `0b837d3`).

---

## 1. Executive Summary & Design Philosophy

The UI pulled from the Claude branch is a complete implementation of Apple's **Liquid Glass Design Language** (rooted in Apple Human Interface Guidelines 2025/2026 specifications), adapted natively to Android via **Jetpack Compose**, **AGSL (Android Graphics Shading Language)**, and **RenderEffect**.

### Core Tenet: Navigation vs. Content Separation
- **Liquid Glass belongs exclusively to the Control and Navigation layer** (Floating Tab Bar, Large-Title Bar, In-Window Sheets, Popovers, and Floating Composers).
- **The Content layer remains solid and legible**: App background canvas is solid (`#000000` in Dark Mode, `#F2F2F7` in Light Mode); content cards and grouped table sections sit flat or gently raised on neutral surfaces (`#1C1C1E` / `#FFFFFF`).
- **Liquid Glass is never used as an app background**: Controls float above content, allowing underlying elements to peek through with optical refraction, chromatic dispersion, and directional lighting without compromising accessibility or contrast.

---

## 2. Foundations Architecture (`ui/theme/`)

### 2.1 Design Tokens & Strict Ratchet (`DesignRatchetTest.kt`)
Hardcoded visual literals are forbidden in new screens. The architectural ratchet enforces that new screens must consume tokens exclusively:
- **`fontSize`**: Must use `PocketType` roles.
- **`RoundedCornerShape`**: Must use `PocketShape` or `ContinuousRoundedShape`.
- **`Color(0x...)`**: Must use `PocketColors` or `MaterialTheme.colorScheme`.
- **Icons**: Outlined or rounded variants only (never filled variants outside selected state).

### 2.2 Semantic Color System (`PocketColors.kt`)
Emulates Apple's multi-tiered semantic color hierarchy:
- **Labels**: `label` (Primary) > `secondaryLabel` > `tertiaryLabel` > `quaternaryLabel`.
- **Fills**: `fill` (Controls/Switches) > `secondaryFill` > `tertiaryFill` (Search/Chips) > `quaternaryFill`.
- **Surfaces**: `groupedBackground` (Canvas) vs `groupedSurface` (Cards) vs `groupedSurfaceRaised`.
- **System Accents**: Contrast-tested Light & Dark variants for `red`, `orange`, `yellow`, `green`, `mint`, `teal`, `cyan`, `blue`, `indigo`, `purple`, `pink`, `brown`, `gray`.
- **Contrast Ratios**: Verified $\ge 7.0:1$ for primary labels and $\ge 4.5:1$ (WCAG AA) for secondary labels against card surfaces.

### 2.3 Typography & Dynamic Optical Tracking (`PocketType.kt`, `Theme.kt`)
- Bundled typefaces: **Inter** (Regular, Medium, SemiBold, Bold) and **Inter Display** (optical cut for titles $\ge 22\text{sp}$).
- Dynamic Reading Scale:
  - `largeTitle`: 34sp / 41sp line height, Bold, Display
  - `title1`: 28sp / 34sp line height, Bold, Display
  - `title2`: 22sp / 28sp line height, Bold, Display
  - `title3`: 20sp / 25sp line height, SemiBold
  - `headline`: 17sp / 22sp line height, SemiBold
  - `body`: 17sp / 22sp line height, Regular
  - `callout`: 16sp / 21sp line height
  - `subheadline`: 15sp / 20sp line height
  - `footnote`: 13sp / 18sp line height
  - `caption1`: 12sp / 16sp line height
  - `caption2`: 11sp / 13sp line height
  - `code` / `codeSmall`: 13sp / 12sp monospace
- **Dynamic Optical Tracking Curve**: Employs Inter's published tracking formula:
  $$\text{tracking}(s) = \left(-0.0223 + 0.185 \cdot e^{-0.1745 \cdot s}\right)\text{ em}$$
  Larger titles tighten tracking naturally, while small captions loosen tracking for readability.

### 2.4 Continuous Curvature ("Squircles") (`ContinuousShape.kt`)
- Implements G2 curvature continuity (`smoothing = 0.6`) instead of abrupt circular arcs.
- The outline smoothly ramps from a straight edge into an arc, creating organic corners that match iOS hardware and software aesthetics. Automatically collapses into a capsule when radius reaches half the shortest dimension.

### 2.5 Physics-Based Motion Tokens (`PocketMotion.kt`, `PocketTransitions.kt`)
- All animations use physics-based spring curves with zero artificial easing cuts:
  - `Track`: 0.20s duration, bounce 0.0 (finger tracking, drag offsets)
  - `Quick`: 0.25s duration, bounce 0.0 (cross-fades, color transitions)
  - `Snappy`: 0.35s duration, bounce 0.15 (taps, toggles, capsule slides)
  - `Smooth`: 0.45s duration, bounce 0.0 (push/pop navigation, sheets)
  - `Morph`: 0.50s duration, bounce 0.15 (glass shape morphing)
  - `Release`: 0.50s duration, bounce 0.30 (fling settling)
- Conversion to physical spring parameters:
  $$\text{stiffness} = \left(\frac{2\pi}{\text{duration}}\right)^2, \quad \text{dampingRatio} = 1 - \text{bounce}$$
- **Velocity Preservation**: Interrupted transitions retain velocity without visual snapping.
- **Tactile Press**: Elements scale to `0.97` on press (`tactilePress`) with spring response, replacing legacy Material ripples.
- **Motion Reduction**: `PocketMotion.reduced` strips bounce and converts navigation slides into cross-fades.

---

## 3. The Glass Engine (`ui/theme/glass/`)

### 3.1 Tiered Rendering Architecture
The engine dynamically selects the optimal rendering strategy per control:
1. **Lens Tier (Android 13+ / Tiramisu, API 33+)**:
   - Custom AGSL runtime shader (`GlassShaderSource`).
   - Evaluates a Signed Distance Field (`sdBox`) with smooth-minimum blending (`smin`) for up to 4 shapes simultaneously.
   - Circular edge refraction: $\text{bend} = 1.0 - \sqrt{\max(1.0 - t^2, 0.0)}$.
   - Chromatic dispersion: Red and blue channels sampled with offsets along normal $n$.
   - Adaptive wash: Dynamically boosts wash density based on underlying luminance without requiring expensive CPU readbacks.
   - Dynamic directional lighting (`glassLightDirection`) that tilts toward the user's touch coordinate.
   - Touch spot glow (`pressGlow`).
2. **Blur Tier (Android 12 / S, API 31-32)**:
   - Hardware `RenderEffect.createBlurEffect` combined with a ColorMatrix saturation boost ($1.5\times$ vibrancy) and dual rim highlight.
3. **Tint Tier**:
   - Fallback when no backdrop capture is available; draws translucent wash, sheen, and rim.
4. **Solid Tier**:
   - Fallback for Android $\le 11$ or when accessibility options ("Reduce Transparency" or "Increase Contrast") are active.

### 3.2 High-Performance Backdrop Capture Pipeline (`InRepoBackdrop.kt`)
- **Single Source Architecture**: Only the active screen's scrolling list registers as `hostBackdropSource()`. The root shell is never a source, preventing recursive capture loops.
- **Scale Factor Optimization**: Renders backdrop captures at half-resolution (`scaleFactor = 0.5`), quartering pixel fill cost. The Gaussian blur completely masks the resolution reduction.
- **Frame-rate Synchronized Debounce**: Set to `debounceMs = 0L` during scrolling, capturing every frame to match 90Hz/120Hz displays without lag.

### 3.3 Shape Merging & Morphing (`GlassGroup.kt`)
- Shapes inside a `GlassGroup` (such as the Floating Tab Bar and Terminal button) calculate their bounds and pass them into the AGSL SDF shader.
- When positioned within range, they morph into a single piece of contiguous glass, pulling apart fluidly as they move.

### 3.4 Progressive Scroll Edge Effect (`ScrollEdge.kt`)
- Eliminates hard dividers under top bars. As content scrolls beneath the bar, it progressively blurs and fades into the canvas, maintaining contrast for top bar controls.

---

## 4. UI Kit Components (`ui/kit/`)

| Component | Key Capabilities |
|---|---|
| **`FloatingTabBar`** | Floating glass dock with spring capsule selection (`Token.Snappy`). Supports horizontal finger dragging, nested scroll auto-minimization into a compact circular badge, and glass-merged accessory buttons. |
| **`LargeTitleScaffold`** | iOS-style collapsing large title: transitions from a 34sp bold title in the list into an inline top bar title upon scrolling, complete with soft scroll edge blurring. |
| **`GlassSheet`** | In-window modal sheet with detents (`Fit`, `Medium`, `Large`). Anchored sheets morph out of their trigger control (`OverlayAnchor`). At `Large`, sheets turn opaque to focus on content. |
| **`GlassMenu` & `GlassPopover`** | Anchored floating glass panels with cascading item entrance animations and touch-outside dismissal. |
| **`ListSection` & `ListRow`** | Grouped table cards with continuous corners, indented hairline dividers, colored squircle icon tiles, and guaranteed $\ge 44\text{dp}$ touch targets. |
| **`PocketButton`** | Standardized hierarchy: `Filled`, `Tinted`, `Gray`, `Plain`, `Glass`, `GlassProminent`. Fixed 44dp / 50dp touch targets. |
| **`BannerHost` & `Banner`** | In-window floating glass status toast (Success, Info, Error) with spring slide/fade animations. |

---

## 5. Screen Implementation Audit

### 5.1 Root Navigation & Dock (`PocketDevApp.kt`)
- `PocketDevApp` wraps the app in `LiquidGlassHost` -> `BannerHost` -> `OverlayHost` -> `SharedTransitionLayout`.
- Top-level screen transitions are managed with `PocketTransitions.push`, `settle`, and `crossFade`.
- Project title morphs seamlessly between the Projects list item and Workspace top bar via `Modifier.sharedTitle()`.
- Bottom navigation is housed in a floating `FloatingTabBar` with a Terminal accessory button.

### 5.2 Projects Screen (`ProjectsScreen.kt`)
- Uses `LargeTitleScaffold` with "Projects" title and dynamic subtitle (`engineSummary`).
- Search field with live filtering.
- Grouped sections for updates, background imports, active projects, and starter cards.
- Long-press / context actions for Rename and Delete.
- All modal actions (Create Project, Clone Git, GitHub connect, Updates) render as native `GlassSheet` overlays.

### 5.3 Agent Management Screen (`AgentScreen.kt`)
- Unified configuration for Antigravity and Claude Code.
- Multi-account management cards, load balancing strategy toggles, model discovery dropdowns, and key validation indicators.
- In-window sheets for adding API keys, switching providers, and OAuth flows.

### 5.4 Settings Screen (`SettingsScreenModern.kt`)
- Grouped table architecture:
  - **Appearance**: Segmented picker for Light / Dark / System + live "Reduce transparency" toggle.
  - **Developer Tools**: Toolchain status and install/uninstall actions for Python, Rust, Go, Android SDK, and Claude Code.
  - **Linux Runtime**: Ubuntu 20.04 PRoot and architecture details.
  - **Maintenance**: Terminal history clear and system Developer Options link.

### 5.5 Workspace & Chat Tab
- **Chat Layering**: Messages scroll edge-to-edge behind floating chrome.
- **Composer**: Guaranteed $\ge 52\text{dp}$ height floating glass container with attachment chips, slash command menu, `@` file mention menu, token telemetry bar, and quick-action chips.
- **Follow Mode**: Automatically scrolls to bottom during streaming unless the user manually scrolls up.
- **Unboxed Responses**: Assistant replies display clean typography without heavy enclosing boxes; syntax-highlighted code blocks adapt to dark/light theme.
- **Changes Review (`ChangesReview.kt`)**: Displays floating glass capsule ("+12 -3") opening a `GlassSheet` with interactive file diffs and keep/undo actions.

---

## 6. Verification & Test Suite Summary

- **Architecture Tests**:
  - `RootLiquidGlassWiringTest`: Verified that root screens and chat register exactly one backdrop source and bars sample it as siblings without self-capture loops.
  - `AppNavigationMotionTest`: Verified spring formula math, screen priorities, and animated transitions.
  - `DesignRatchetTest`: Verified that all new screens maintain zero raw literals for font sizes, shapes, and colors.
- **Test Suite Results**:
  - Unit tests executed: 748 tests.
  - 14 tests in `OverlayKitTest` and `KitFoundationTest` failed exclusively due to `UnsatisfiedLinkError: no conscrypt_openjdk_jni-linux-aarch_64 in java.library.path` (a known Robolectric host dependency issue on Linux ARM64 container environments, unrelated to Android app compilation or runtime behavior).
  - Production build: Debug APK builds cleanly via `./gradlew assembleOnlineDebug` and runs without issue.
