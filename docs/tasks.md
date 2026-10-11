# Tasks and progress

Current checkpoint: 2026-10-11. Keep active work and material verification here; stable context belongs in [memory.md](memory.md).

## Refactoring skill research and decision

Research date: 2026-10-11. Request: assess other refactoring skills, plan an app-specific skill and create it only when it adds demonstrable value without unnecessary prompt context.

Decision: do not create or enable a new skill yet. Current [coding rules](rules.md#smallest-complete-change) and the [refactoring plan below](#full-codebase-refactoring-research) already cover scope, caller/deletion evidence, ownership, compatibility, test selection and completion. A skill containing the same advice has no established incremental benefit. This is a research decision, not a failed implementation or a requirement for another approval. No candidate SKILL.md, helper framework or injected instruction was added.

### What existing implementations contribute

The following are inspected source implementations, not evidence that they improve this app or instructions to execute their workflows.

| Implementation | Useful idea | Fit for this checkout |
| --- | --- | --- |
| [Anthropic code-simplifier](https://github.com/anthropics/claude-plugins-official/blob/main/plugins/code-simplifier/agents/code-simplifier.md) | Preserve functionality; favor readable code; usually restrict work to recently changed code. | This is an agent definition, not SKILL.md. Its React/JavaScript conventions and automatic extra simplification pass should not be copied into Android work. |
| [GitHub refactor-plan](https://github.com/github/awesome-copilot/blob/main/skills/refactor-plan/SKILL.md) | Investigate hidden coupling and sequence a multi-file change with intermediate checks. | Its mandatory second confirmation, even after an implementation request, does not fit this project's authorization policy. Fixed phases and a large plan template are excessive for small changes. |
| [Community code-simplifier](https://github.com/caarlos0/dotfiles/blob/main/skills/code-simplifier/SKILL.md) | Follow registration, reflection, configuration and consumers before deletion; retain intentional duplication. | Helpful deletion discipline, but mostly overlaps current rules/plan. Its language references do not include Kotlin. Rejecting all one-use abstractions would also conflict with useful lifecycle or trust boundaries. |
| [OpenAI Agents Python verification](https://github.com/openai/openai-agents-python/blob/main/.agents/skills/code-change-verification/SKILL.md) | A concrete repository verification procedure, with focused checks during iteration and the broad stack after review. | Demonstrates an operational skill rather than a coding persona. Its Python/Make scripts and platform exceptions do not apply here. |
| [OpenAI Guardrails JS verification](https://github.com/openai/openai-guardrails-js/blob/main/.agents/skills/code-change-verification/SKILL.md) | Choose checks by changed area; metadata changes need different evidence from runtime changes. | A useful model for selecting Android checks. Repository commands must come from current Gradle configuration and existing fixtures. |
| [Matt Pocock architecture survey](https://github.com/mattpocock/skills/blob/main/skills/engineering/improve-codebase-architecture/SKILL.md) and [codebase-design](https://github.com/mattpocock/skills/blob/main/skills/engineering/codebase-design/SKILL.md) | Investigate actual architectural friction; ask whether an abstraction removes complexity or relocates it. | The survey depends on other skills, glossary/ADR maintenance, delegated exploration and an HTML report. Installing that workflow would expand this task and document structure. A rigid two-implementation rule is unsuitable for boundaries justified by one current resource owner. |

[OpenAI's skill evaluation guidance](https://developers.openai.com/blog/eval-skills) recommends defining success before writing a skill, capturing real runs/artifacts and checking outcomes, process and efficiency. [Anthropic's skill-creator implementation](https://github.com/anthropics/claude-plugins-official/blob/main/plugins/skill-creator/skills/skill-creator/SKILL.md) compares with-skill runs against a baseline and inspects time/token tradeoffs. These methods support testing a candidate, not declaring it useful because its Markdown validates. [OpenAI's current skill guidance](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra) also warns that broad descriptions, overlapping instructions and elaborate recipes can add irrelevant context. None of these sources supplies measured evidence for a Mobile Harness refactoring skill.

### Actual loading and context cost

Source inspection of [SkillManager](../app/src/main/java/com/jarves/mh/runtime/SkillManager.kt), [SlashCommandEngine](../app/src/main/java/com/jarves/mh/runtime/SlashCommandEngine.kt) and [MainViewModel](../app/src/main/java/com/jarves/mh/ui/MainViewModel.kt) confirms:

- Enabled, guest-readable skills contribute name, description and path to the prompt catalog. Their bodies are available for selective reading; explicit `/skill-name` invocation embeds the body in the request. Catalog metadata is still a cost even when the body is unused.
- Local skills are enabled unless disabled in ordinary project/merge scopes; custom scope uses explicit enabled IDs. Simply adding a skill folder can therefore enlarge future prompts, depending on project settings.
- The harness parser reads name/description and Markdown body. These inspected paths do not enforce Claude's disable-model-invocation field or Codex's agents/openai.yaml invocation policy. Do not promise manual-only loading based on those files without a verified harness behavior change.
- Guest skill snapshots can move resources to a different directory. A future skill must resolve its own resources from its skill folder and project docs from the active workspace, rather than use parent-relative paths to reach this repository.

An [Anthropic issue report](https://github.com/anthropics/claude-code/issues/19889) describes confusion between an agent and a skill. It is anecdotal, was closed as a duplicate, and does not establish a current harness defect; the actual source formats above are the relevant evidence.

### Conditional design and usefulness test

If repeated refactoring work reveals a gap, the narrow candidate is **mobile-harness-refactor**: an on-demand procedure for one requested refactor that records candidate → caller/dynamic-entry evidence → observable contract → applicable checks → retain/simplify/remove/uncertain decision. Its unique purpose would be preventing unsafe deletion and selecting meaningful Android evidence. It should refer to current canonical documents, use a short task-specific description, and avoid personas, a copied feature inventory, mandatory surveys, extra dependencies and automatic follow-up refactoring. A script is warranted only for a demonstrated recurring mechanical need.

Before creating a discoverable skill, compare a minimal candidate against the current AGENTS.md and relevant docs on identical isolated fixtures, with the same model, tools and task allowance. The candidate text should be an experiment retained locally, not added to the enabled catalog. Use these source-backed cases and negative controls:

| Case | Observable acceptance check |
| --- | --- |
| Apparent unused native/Android entry point | Inspect native bindings, manifest/resources, assets/scripts and supported variants; preserve a dynamically referenced entry point. A zero-result text search alone cannot justify removal. |
| Simplifying Codex completion handling | Preserve the documented successful-turn/late-cleanup-exit contract and run the relevant bridge regressions; do not equate a stream event with process termination. |
| Removing an old chat path | Preserve supported legacy/paged storage compatibility; run representative saved-data fixtures and affected storage regressions. |
| Moving a Compose section | Preserve callbacks, effect/state ownership and semantics. Recognize that current -Pscreenshots records renders and does not establish approved-image comparison. |
| Combining cancellation paths | Preserve task/session/request ownership and child cleanup; select applicable timeout/cancellation fixtures and identify missing device evidence. |
| Ordinary bug fix, small feature or documentation edit | Avoid loading a whole-codebase refactoring workflow or proposing unrelated cleanup. Preserve an explicit APK hold. |

Capture traces, task diffs, commands, actual check results, scope violations and available usage/timing. Review outcome quality separately from structural improvement. Repeat paired runs enough to distinguish a consistent benefit from one lucky answer; do not optimize on the evaluation examples alone. Publish only if the candidate fixes an observed baseline weakness or consistently reduces work while retaining correctness, introduces no unsupported deletion or compatibility/scope regression, and avoids false activation on the negative controls. If both versions already perform well, keep the docs and omit the skill. Static schema checks and source inspection are not a substitute for this comparison.

VERIFIED: source bodies for seven related implementations, official evaluation/context guidance, the community format report and this checkout's discovery/invocation paths were inspected. Documentation links and preservation checks were run; existing code, skills, agent entry points and Git index were preserved. Research and the conditional plan were recorded only in this canonical document; local receipts remain in the existing ignored archive's refactoring-skill-research/ directory.

NOT RUN: a candidate skill, paired agent evaluation, live-provider behavior, application refactoring, tests/lint/build or APK generation. Added skill context is zero because no skill was installed. Efficiency and quality improvement remain unproven. Optional raw-page fetch failures and a corrected test-file lookup are recorded in local receipts; successfully opened implementation pages support the comparison. No commit or push was made.

## Full-codebase refactoring research

Research date: 2026-10-11. Requested outcome: a simpler, clearly owned codebase with dead code and unnecessary structure removed while retaining the app's features, UI and supported data/runtime behavior. This section is a proposed implementation plan; no application refactoring was performed.

Recommendation: establish observable behavior checks, then make small, independently reviewable changes within the existing architecture. Each pass needs a current problem, a structural benefit and evidence that behavior survived. A language/framework migration, dependency upgrade, redesign or functional bug fix needs its own explicit scope. This follows [OpenAI's refactoring workflow](https://learn.chatgpt.com/use-cases/refactor-your-codebase) and [Claude's incremental refactoring workflow](https://code.claude.com/docs/en/common-workflows#refactor-code).

### Research evidence and limits

The recommendations below are a synthesis for this checkout. Provider examples and forum experiences do not prove a universally best prompt or guarantee regression-free results.

| Source inspected | Applicable finding |
| --- | --- |
| [OpenAI: refactor your codebase](https://learn.chatgpt.com/use-cases/refactor-your-codebase) | Map the affected area, choose one cleanup theme, preserve contracts and verify each pass. Keep stack and architecture migrations separate. |
| [OpenAI: code modernization](https://developers.openai.com/cookbook/examples/codex/code_modernization) | Start with a pilot flow and compare original/new behavior on shared scenarios. Its COBOL example's extra services and documents are not required for this Android app; reuse the six canonical docs. |
| [OpenAI: custom review rules](https://developers.openai.com/blog/custom-code-review-rules-for-codex) | Concrete repository rules help reviewers, but do not replace mechanical checks. Review instructions are supplementary evidence. |
| [Claude Code: best practices](https://code.claude.com/docs/en/best-practices) | Provide executable checks and specific scope; separate investigation from implementation for uncertain work. Keep persistent instructions short and load task details when needed. |
| [Claude Platform: overeagerness](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/claude-prompting-best-practices#overeagerness) | Explicitly constrain extra files, abstraction and unrequested configurability. Minimal code still needs supported failure handling. |
| [Anthropic: large-scale code migrations](https://claude.com/resources/articles/ai-code-migration) | Validate the behavioral checker on the original implementation and ensure it detects deliberate breakage. Borrow the verification principle; the large migration's parallel orchestration is not a requirement here. |
| [Anthropic's GitHub test reviewer](https://github.com/anthropics/claude-plugins-official/blob/main/plugins/pr-review-toolkit/agents/pr-test-analyzer.md) | Review behavioral coverage, negative cases, async behavior and integration boundaries. A line-coverage percentage alone is insufficient. No plugin was installed or invoked. |
| [Android modularization guide](https://developer.android.com/topic/modularization) | Gradle modules introduce build/boilerplate overhead. Add one for a demonstrated isolation, delivery, reuse or build benefit, not to reproduce a template. |
| [Android: reflection/JNI keep rules](https://developer.android.com/topic/performance/app-optimization/keep-rule-examples) | Dynamic entry points can appear unused to static analysis. Unused-code reports require a boundary-aware review before source deletion. |
| [Android: Robolectric strategies](https://developer.android.com/training/testing/local-tests/robolectric) | Local behavior/rendering tests are useful, but WebView and some system UI require device checks. Host screenshots do not establish exact device rendering. |
| [Roborazzi's repository documentation](https://github.com/takahirom/roborazzi#run-tasks) | Recording, comparison and verification are distinct modes. Verification can fail on image differences; recording alone is not visual parity evidence. Confirm configuration against the installed version before implementation. |
| [Fowler: definition of refactoring](https://martinfowler.com/bliki/DefinitionOfRefactoring.html) | Internal restructuring preserves observable behavior. Keep behavior improvements explicit and separately verifiable. |
| [SWE-Refactor preprint, v1](https://arxiv.org/html/2602.03712v1) | In a 200-instance Java sample, GPT-5.1-Codex completed 138/167 atomic transformations (82.6%) and 13/33 compound transformations (39.4%). Success included the requested structural operation, compilation and tests; some failures were alternative/partial transformations, not proven app regressions. The small compound sample, older model, Java-only and method-level scope prevent extrapolation to this Kotlin/Compose app or current agents. |

First-hand community reports supply failure scenarios, not prevalence or causal proof. [Codex issue #24922](https://github.com/openai/codex/issues/24922) reports weakened/vacuous regression tests, lost behavior and completion claims contradicted by the diff. [Claude Code issue #39703](https://github.com/anthropics/claude-code/issues/39703) describes missed callers and collateral changes during refactoring. A [Claude Code forum account](https://www.reddit.com/r/ClaudeCode/comments/1qp7qbe/claude_code_loves_breaking_stuff_and_then/) describes newly introduced failures being dismissed as pre-existing. These reports justify inspecting test changes and comparing failures with an actual pre-change run; their claims were not independently reproduced here.

### Verified checkout signals

The working checkout on test-ui at 81d75d117c1688ec8d3191356a04f3d889e795ab includes staged documentation consolidation and unstaged/untracked capability work. HEAD alone is an incomplete baseline for that work.

- Main app Kotlin/Java sources: 141 files and 55,911 lines under app/src/main/java/com/jarves/mh. This excludes native/vendored code, assets, scripts and generated sources. Line counts include comments/blank lines and are discovery signals, not quality scores.
- app/src/test contains 136 Kotlin/Java source files, including fixtures. app/src/androidTest contains none; benchmark/src contains one Kotlin source. This does not imply an absence of host UI tests: Compose/Robolectric/Roborazzi fixtures already exist.
- [app/build.gradle.kts](../app/build.gradle.kts) excludes screenshot test classes by default. With -Pscreenshots, it sets roborazzi.test.record=true and writes images under build/outputs/roborazzi. [KitScreenshotTest](../app/src/test/java/com/jarves/mh/ui/KitScreenshotTest.kt), [DesignScreenshotTest](../app/src/test/java/com/jarves/mh/ui/DesignScreenshotTest.kt), [WorkspaceSheetsScreenshotTest](../app/src/test/java/com/jarves/mh/ui/WorkspaceSheetsScreenshotTest.kt) and [GlassScreenshotTest](../app/src/test/java/com/jarves/mh/ui/theme/glass/GlassScreenshotTest.kt) capture images. This configuration supplies renders, not an approved-baseline comparison gate.
- Release minification is currently disabled. Do not claim an R8 usage report already proves which source can be deleted, or enable shrinking as an incidental cleanup.

| Source | Lines | Proposed inspection focus |
| --- | ---: | --- |
| [MainViewModel](../app/src/main/java/com/jarves/mh/ui/MainViewModel.kt) | 6,019 | Many feature handlers and state/event paths. Look for coherent pure calculations first, then isolate feature responsibilities while preserving ownership. |
| [WorkspaceScreen](../app/src/main/java/com/jarves/mh/ui/WorkspaceScreen.kt) | 2,717 | Compose sections and callbacks; retain state lifetime, effects, keys, scroll position, keyboard behavior and Preview ownership. |
| [RuntimeInstaller](../app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt) | 2,270 | Common installation primitives; retain engine differences, trust/provenance, permissions and account/config preservation. |
| [TaskSupervisor](../app/src/main/java/com/jarves/mh/runtime/task/TaskSupervisor.kt) | 2,224 | High-risk orchestration; inspect pure decisions before moving execution, recovery or cleanup ownership. |
| [CliParityComponents](../app/src/main/java/com/jarves/mh/ui/CliParityComponents.kt) | 2,179 | Distinct sheets/components and supported controls; shared appearance does not prove shared behavior. |
| [AppPreferences](../app/src/main/java/com/jarves/mh/data/AppPreferences.kt) | 1,348 | Paged/legacy chats, atomic storage and corruption recovery; preserve on-disk contracts and migration tests. |

These are investigation targets, not confirmed dead code or instructions to split files at a particular line count. No new deletion candidate was proved safe by this research.

### Proposed sequence and acceptance checks

1. Preserve the actual baseline. Snapshot changed source, tests, assets and the index, including required untracked files, without committing or resetting user work. Record existing failures from real runs and preserve their logs. Keep a patch per pass so only that pass can be backed out. Start from the current implementation, not a clean HEAD that omits the repairs.
2. Map contracts to checks. Use the full [feature inventory](prd.md#current-feature-inventory), [architecture](architecture.md) and [UI direction](design.md). Add characterization tests only where the selected behavior lacks meaningful coverage. Tests of existing behavior must pass before restructuring; bug-fix regressions must demonstrate the failure first. Review the assertions, fixtures and delayed async paths so a green result actually exercises the contract.
3. Build an evidence-backed candidate list in this section as work starts. For each candidate record the symbol/path, callers and dynamic entry points, reason, retain/simplify/remove/uncertain decision, affected contract and required check. An rg result or low coverage alone does not authorize deletion.
4. Run one low-risk pilot. Select a proven-unused internal symbol or a coherent pure calculation after caller review. Remove it or simplify its control flow; verify behavior and evaluate the diff before repeating the pattern. Avoid simultaneous extraction, renaming, relocation and behavior changes in the pilot.
5. Simplify feature ownership gradually. Preserve the existing app/benchmark modules and ui/data/model/runtime/provider boundaries. Extract only a coherent responsibility with explicit inputs, outputs and lifecycle ownership. A useful one-caller helper is allowed; forwarding layers, generic registries and extra repositories/use cases need a current reason. Consolidate duplicated logic only when the cases enforce the same invariant.
6. Move UI code after visual gates exist. Freeze pre-change renders and use a genuine compare/verify path that cannot overwrite approved baselines. Reuse installed Roborazzi 1.50.0 and current fixtures; establish the exact supported configuration rather than copying newer README commands blindly. Also test interactions, semantics, state restoration and effects. Keep tokens, glass hosts and framework state ownership intact.
7. Tackle persistence/shared runtime last. Broader execution or storage restructuring requires the corresponding lifecycle, compatibility, engine and real-device checks. Retain provider-specific protocols in existing bridges. Do not remove supported legacy migrations, native binaries, runtime bundles, vendored source or licenses as apparent clutter.
8. Review and verify every pass. Inspect deletions, test diffs and newly added structure; run the smallest relevant tests/checks and broaden for shared behavior or failures. Do not weaken expected outcomes, ignore new failures or refresh screenshot goldens merely to obtain green results. Compare a claimed pre-existing failure with the saved baseline. Keep correctness fixes and measured performance improvements in separately named passes. Follow [APK delivery rules](rules.md#phone-workflow-and-apks) and any explicit user build hold.

### Feature-preservation gates

Apply the rows affected by each pass; the complete refactor cannot be called verified while relevant checks remain missing. Existing regression names below are starting points, not evidence of a run during this research.

| Area | Behavior to preserve | Existing coverage / additional evidence |
| --- | --- | --- |
| Setup, installation and updates | Online/offline inputs, trust failures, extraction containment, retry, credentials, signer/package identity | RuntimeInstallerArchiveTest, RuntimeInstallerDownloadTest, RuntimeCertificatesTest, RuntimeToolAssetsTest, AppUpdaterDownloadTest; fresh/repeated install and update device smoke. |
| Projects and navigation | Creation, ZIP/Git/GitHub import/export, nested roots, tab cancellation and stale loads | GitHubClientTest, MainViewModelTabCancellationTest, MainViewModelOpenRootTest, MainViewModelNestedRootTest; import/export and root-tab interaction smoke. |
| Chats, files and review | Paged/legacy history, corruption recovery, first visible position, attachment ownership, Keep/Undo, symlink boundaries | ChatPagedStorageTest, ChatFileRecoveryTest, ChatInitialPositionTest, MainViewModelFileViewerTest, WorkspaceCheckpointsTest; saved-project fixtures and device file/attachment/review flows. |
| UI and accessibility | Current screens/sheets, tokens, light/dark, font scale, transparency/contrast fallbacks, keyboard, scroll and semantics | Existing design/kit/glass/sheet renders plus a verified image-diff path, interaction checks and device inspection; screenshots alone do not test callbacks. |
| Engines and accounts | All four bridges, supported providers/models/effort, authentication, images, continuity, streaming, approvals and reported usage | Existing Claude/Codex/DSH/Antigravity bridge/auth tests, RuntimeRetrySessionRoutingTest and protocol fixtures; authenticated turns where supported. Similar event names do not make engine contracts equivalent. |
| Task lifetime and Terminal | Cancellation/timeouts, taskId/sessionId, stale events, duplicate prevention, recovery, process groups, locks, notifications and wake-lock release | ProcessSupervisorTest, DuplicateExecutionProtectionTest, RuntimeBridgeCleanupTest, WakeLockManagerTest and task resilience tests; device process-death/background/cancel checks and owned-process observations. |
| Rules, skills, memory and Brain | Scope/toggles, imports/links, resource snapshots, prompt boundaries, persisted facts and migrations | SkillManagerTest, GuestSkillAccessTest, PromptCompactionTest, BrainDatabaseMigrationTest, BrainContextAssemblerTest; guest-readable resource and saved-data scenarios. |
| Preview and native assets | Open WebView ownership, external URL blocking, DOM/console/viewport, deadlines, native packaging and tool provenance | PreviewOwnershipTest, NativeInspectionBridgeTest, LoopbackGatewaySecurityTest and CLI/MCP adapter tests; an open project Preview and device capture. Ordinary Gradle assembly does not rebuild the prebuilt native libraries. |

### Completion evidence and task prompt

A pass is complete when its observable contracts pass, the structural benefit is shown in the diff, required checks/artifacts are delivered or explicitly held, and unrelated work remains intact. Record actual test cases run/failed/skipped, warnings and limitations. Compare affected branches, duplication, dependencies and ownership clarity; line/file reductions are supporting metrics. Claim speed, memory or battery improvements only after measurements under comparable conditions. Passing compilation alone, historical 781-test receipts or a regenerated screenshot is insufficient to certify all features.

The implementation queue should begin with baseline preservation and validation gaps, then a low-risk deletion/simplification pilot, MainViewModel feature responsibilities, UI sections, and finally installer/storage/supervision boundaries. This is a proposed order; caller evidence and risk can change it. Keep architecture.md describing the delivered code until each pass is verified. No new instruction file, framework, plugin or permanent refactor-management layer is needed.

Reusable task wording, to supplement the compact AGENTS.md rather than expand every injected prompt:

    Refactor [one area] in one small pass. Read docs/rules.md and relevant feature contracts. Identify callers and dynamic entry points; state the current behavior, structural benefit, affected files and checks. Remove code only with evidence that it is unused. Prefer existing components and direct logic; preserve UI behavior, data formats, provider contracts and lifecycle ownership. Establish meaningful behavior checks before edits. Never weaken assertions or overwrite approved baselines to make verification pass. Review the full diff, run applicable checks, record observed results and remaining gaps in docs/tasks.md, then stop. Keep functional improvements in explicit separate passes.

VERIFIED: the cited official guidance, first-party GitHub material, preprint methods/limitations, community accounts and checkout configuration were inspected. Only this canonical task document changed for the research; pre-edit content and preservation receipts are retained locally in the existing ignored archive's refactoring-research/ directory.

NOT RUN: refactoring, a complete symbol/reachability audit, application tests/lint/build, screenshot comparison, device/provider checks or performance measurements. Existing validation gaps below remain. FAILED: optional browser retrievals of Codex's root AGENTS.md and version-tagged Roborazzi README pages; these were not used as evidence. Successful official Codex pages and Roborazzi's main documentation support the recommendations, but the exact installed-version verification setup remains to be checked during implementation. No APK was generated and no commit/push was made.

## App feature documentation review

Requested outcome: make the six maintained documents describe the current app and its features accurately.

- Expanded the source-backed feature inventory, screen/flow map, engine contracts and durable product context. Coding rules now require checking callbacks/handlers before documenting a delivered feature.
- Covered setup, permissions, project/chat management, ZIP/Git/GitHub import/export, attachments, terminals/APKs, Preview, task execution/review, usage/activity, rules/skills, project memory/Brain, settings and updates.
- Checked four engines, ten provider kinds, five developer-stack entries, all 25 registered slash commands and three native inspection operations against current source. Engine/provider eligibility is documented separately from the full provider-kind list.
- Corrected boundaries: read-only file viewer; prompt-based workflow/compaction commands; provider-reported timers; Claude-only interactive approvals; full-task rather than individual child stopping; internal named-provider/build helpers without dedicated UI callers. Some help text uses an unregistered singular skill-command spelling; the documented command is `/skills`.
- VERIFIED: exactly six maintained docs; 111 local links/anchors resolve, including 53 distinct source targets. Preservation checks confirm 740 unrelated files and the non-document Git index are unchanged.
- VERIFIED: required online debug/release assembly succeeded in 58s (91 tasks up-to-date). Root copies match the build outputs; both signatures and package IDs pass, retaining the earlier signer. Sizes/MD5 match the artifact table below; the older offline APK remains unchanged. No application code changed in this task.
- NOT RUN: new application tests, updated-device interaction or authenticated provider turns. Source inspection establishes feature wiring, not successful end-to-end operation.

Completed: the six-document baseline, reviewed task diff and check/build receipts are retained in the existing ignored archive's feature-coverage/ directory. Earlier task receipts below remain historical. Documentation updates remain local; no commit or push was made.

## Documentation consolidation

Requested outcome: exactly six maintained files in docs/: product requirements, architecture, coding rules, UI direction, task progress and project context.

- Consolidated current requirements and source-backed architecture/design.
- Preserved complete original docs, local modifications and generated evidence under .review/docs-before-consolidation-2026-10-11/ before replacing the active set. Historical root device reports are retained there too.
- Updated documentation entry points. Root README, legal files and agent rules retain their distinct roles.
- Consolidation and required APK refresh are verified. No application behavior, dependency version or runtime configuration changed for this task. Documentation replacement is staged; no commit or push was made.

The ignored archive is local; fresh clones do not include it. Previously committed originals remain in Git history.

## Earlier completed work

These are recorded results from earlier tasks, not tests rerun by this feature documentation review. Full receipts are preserved in the archive.

| Work | Recorded result |
| --- | --- |
| UI fixes on test-ui | Root-tab cancellation and read-only chat positioning committed/pushed earlier. Preserve subsequent local capability work. |
| Capability repairs | Certificate defaults, guest skill snapshots, ARM64 search/JDK access, scoped Preview/Brain inspection and diagnostic cleanup. 781 Android tests and eight CLI/MCP tests passed; lint had no errors and 125 warnings. |
| Prompt shortening | Header fixture reduced from 3,403 to 2,006 characters; 98 selected tests passed. User rules/history/knowledge remained intact; total prompt size depends on their content. |
| Project-rule compaction | AGENTS.md reduced from 3,930 to 1,387 characters (64.7%); detailed procedures remain in docs/rules.md with six-doc routing and essential inline guards. Document checks passed; no APK or live-agent test run for this update. This measures the project rule file, not the entire injected prompt. |
| Simplicity guidance | Adopted the archived coding-instruction research: one short rule in AGENTS.md and concrete feature, fix and abstraction criteria in docs/rules.md. Existing validation, cleanup, security and test requirements remain. NOT RUN: local agent comparison or APK generation; speed and code-quality effects remain unmeasured. |
| APK delivery | Both online variants built; copies, signatures and package identities passed. Latest previous delivery was the prompt-compaction build; older offline output does not establish delivery of later repairs. |
| Repository review | No tracked APK/build-cache/signing-key pollution. Scratch files, obsolete submodule metadata, editor-task drift and local-artifact ignore gaps identified. |

## Active validation gaps

| Priority | Work | Boundary |
| --- | --- | --- |
| High | Activate updated APK; check guest skills/tools and repeated repair | NOT RUN on the updated device runtime. |
| High | Authenticated provider turns, supported native resume, streaming, images and MCP tools | NOT RUN; preserve completed-turn/cleanup-exit regressions. |
| High | Android background/foreground, process death, cancellation, child cleanup, service/wake-lock release | NOT RUN as real device lifecycle checks. |
| High | Positive device Preview DOM/console/viewport capture | NOT RUN; requires the project Preview to remain open. |
| Medium | Authenticated debugging access | BLOCKED in the earlier check: empty SDK ADB and no authenticated connection. Recheck before relying on that observation. |
| Medium | Fresh/offline install and native source/binary provenance | NOT RUN for latest changes. |
| Medium | CPU/memory/battery, frame timing and visual/accessibility validation | NOT RUN; no measured optimization/compliance claim. |

## Historical review watchlist

The 2026-10-09 full review had 17 findings. Revalidate against current code before fixing; upstream changes supersede some historical implementations. IDs retain the connection to archived reproductions and regression proposals.

| ID | Area to revalidate |
| --- | --- |
| F01 | Creating a chat during project opening; preserving the disk index. |
| F02 | Project/root/request ownership before publishing customizations. |
| F03 | Symlink containment when reading change diffs. |
| F04 | Process aliases and cancellation-flag cleanup. |
| F05 | Startup orphan identity and recycled/unrelated PIDs. |
| F06 | Atomic chat-index writes and saved-history recovery. |
| F07 | Dismissed read-only loads, loading state and late publication. |
| F08 | Loading cleanup after file-refresh exceptions. |
| F09 | Main-thread filesystem work during project opening. |
| F10 | Markdown cache memory bounds. |
| F11 | Retaining corruption backups after earlier successful reads. |
| F12 | Ownership of asynchronous file-viewer results. |
| F13 | Final symlinks during runtime archive extraction. |
| F14 | HTTP cleanup on failed requests. |
| F15 | Skill discovery from the selected project root. |
| F16 | APK-update size/storage bounds. |
| F17 | Phone install script debug package identity. |

Additional optional cleanup: root scratch screenshots, stale .gitmodules, the editor task and personal attribution in the branch-sync PR template. Retain native binaries, vendored sources, licenses and current runtime implementation.

## Release readiness

Before Play submission, recheck the exact artifact, upload signer, package/version, current policy and runtime redistribution constraints. Maintain the public privacy policy, support contact, data-safety/app-content answers, both user-initiated specialUse foreground-service descriptions, reviewer access instructions and demonstration video. Old checked boxes are historical evidence, not present release verification.

Reviewer smoke flow: setup, configure temporary provider access privately, create a small project, run a task, inspect Files/Changes, run a terminal command, start a loopback server and inspect Preview, then stop/background the task and observe its notification. Keep credentials out of source, recordings and screenshots.

## Recorded consolidation verification

- VERIFIED: source/old-document inspection and preservation baseline created before edits.
- VERIFIED: exactly six docs in the filesystem and Git index; 44 local links checked; original 199 docs and six root device reports preserved; 740 unrelated files unchanged. Ignore rules retain the six canonical files and exclude the archive/extra reports.
- VERIFIED: required online debug/release assembly succeeded in 1m 4s (91 tasks up-to-date). Root copies match their build outputs; both signatures verify. Sizes/checksums for every root APK are retained in the local archive.
- VERIFIED: full task diff reviewed and Git whitespace/status/stat checks completed; earlier local source edits remain preserved.
- NOT RUN: new application tests or device/provider checks. Documentation and a build-script comment are the only new source changes.

| Root APK | Bytes | MD5 |
| --- | ---: | --- |
| mobile-harness-dev.apk | 119063399 | 72adf14217177a5e284ecbb58944d9d2 |
| mobile-harness-stable.apk | 96327775 | 2ad46bee228415862263f2ee0968c141 |

app-online-debug.apk and app-online-release.apk match the respective aliases. The previous offline APK is preserved. No new application test, device installation or authenticated model check is implied by this build.
