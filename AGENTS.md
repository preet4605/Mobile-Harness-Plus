# Mobile Harness — Agent Instructions

Workspace: /workspace/clever-kalam

## 1. CORE RULES

- Preserve existing behavior unless the task explicitly changes it.
- Stay strictly within requested scope.
- Reuse existing architecture, components, utilities, and tokens.
- Never invent test results, hashes, file sizes, commits, or runtime observations.
- Do not commit or push unless explicitly requested.
- Current source/configuration is authoritative.
- When complete and verified, STOP.

Preferred workflow:

BRAIN → TARGETED DISCOVERY → BATCH READ → PLAN → EDIT → VERIFY → BUILD → LEARN → REPORT

## 2. PROJECT BRAIN

Project Brain is the persistent project knowledge layer.

For non-trivial tasks:
1. Use relevant Brain context first.
2. Use it to recover architecture, decisions, constraints, previous fixes, failures, and successful patterns.
3. Use Brain knowledge to narrow workspace discovery.

Source-of-truth hierarchy:

CURRENT WORKSPACE > VERIFIED BRAIN KNOWLEDGE > ASSUMPTIONS

If Brain conflicts with current code:
- inspect the current implementation
- treat current code as authoritative
- identify the conflict
- use the current verified state

Do not blindly apply stale Brain knowledge.

Keep Brain retrieval bounded and task-relevant.
Do not repeatedly retrieve the same knowledge during one task.

Verified implementation patterns, failures, constraints, and durable decisions may be learned by Brain.
Never store speculation as verified knowledge.

## 3. AGENT EFFICIENCY

Before searching:
1. understand the task
2. consult relevant Brain knowledge
3. identify likely entry points
4. identify the smallest complete file set
5. inspect related files
6. form the implementation plan

Prefer:
- one broad search over many narrow searches
- batch reads of related files
- definitions + important call sites together
- complete reads for small files
- targeted ranges for large files

Avoid:
- repeated searches for the same symbol
- rereading unchanged files
- rediscovering known architecture
- overlapping reads
- exploratory calls without a concrete question

If exploration becomes:

search → read → search → read

without implementation progress, reassess the plan.

Once the relevant entry point, implementation, abstractions, call sites, and required changes are understood, STOP exploring and implement.

Further searches require a concrete unresolved question.

## Large File & Edit Efficiency

- When a relevant component fits within the available file-read limit, read the complete component in one call.
- Do not split a known component into arbitrary 20–100 line slices.
- Use the largest practical read window for relevant code.
- Retain and reuse source context already read during the current task.
- Do not reread unchanged content unless a specific missing region is required, the file changed, or context was compacted.

### Multi-Block Edits

When modifying multiple regions of the same file:
1. Prefer one coordinated edit when safely possible.
2. Otherwise edit from highest line number to lowest line number when using line-number-sensitive replacement.
3. Avoid unnecessary top-to-bottom edits that create line-number drift.
4. Do not reread unchanged regions merely to recalculate line numbers.

### Read → Plan → Edit

After sufficient source context is obtained:
1. finish the implementation plan
2. apply the edits
3. verify

Do not perform unrelated searches between discovery and editing.

### Context Compaction

If context compaction occurs:
- use existing task context and Project Brain first
- recover only the specific source regions required
- do not repeat the entire discovery process
- use the largest practical read window

## 4. EDITING

Prefer one coherent edit per file.

Do not repeatedly modify the same file in tiny increments unless verification exposes a real problem.

Make the smallest coherent change set.
## 5. WORKSPACE

All persistent project files, source, tests, configs, scripts, and build artifacts MUST remain inside:

/workspace/clever-kalam

NEVER write persistent project output to:

~/.gemini/antigravity-cli/scratch
/tmp

Do not create duplicate project sources outside the workspace.

## 6. EXISTING USER CHANGES

Never overwrite, revert, discard, or clean up unrelated uncommitted changes.

Before modifying a file with existing changes:
- inspect the relevant diff
- preserve unrelated work

Never assume uncommitted work is disposable.

## 7. DESTRUCTIVE OPERATIONS

Do not perform destructive operations unless explicitly required and authorized.

Never casually use:
- git reset --hard
- git clean -fd
- git clean -fdx
- rm -rf on project directories
- mass deletion
- database destruction/reset

Prefer targeted, reversible changes.

## 8. SOURCE OF TRUTH

Prefer existing project abstractions before creating new ones.

Before creating a new component, helper, manager, utility, abstraction, design token, or adapter:
- search for an existing equivalent

If multiple implementations exist, determine which one the active build actually uses.

Do not hand-edit generated files when the source that generates them exists.

## 9. DEPENDENCIES

Do not add, remove, upgrade, downgrade, or replace dependencies unless required by the task.

Do not change Kotlin, Compose, Gradle, AGP, SDK, or build-tool versions as an incidental fix.

Before changing a dependency, verify the task cannot be completed with the existing stack.

Report dependency changes explicitly.

## 10. SECRETS

Never hard-code:
- API keys
- tokens
- passwords
- cookies
- private keys
- credentials

Never print secrets into logs or reports.

Do not commit credential files.

Use the existing project secret/configuration mechanism.

## 11. SCOPE

Modify only what the task requires.

Do not:
- redesign unrelated screens
- refactor unrelated architecture
- change runtime during UI-only work
- change UI during runtime-only work
- perform opportunistic cleanup
- modify generated output

If an unrelated issue is discovered, report it separately instead of fixing it automatically.
## 12. MULTI-ENGINE PROTOCOLS

Antigravity/Gemini uses its native payload structures:
- contents
- systemInstruction

Claude/DeepSeek/OpenAI use their respective protocol formats.

Do not mix engine-specific payload structures.

Keep protocol logic inside:
- AntigravityRuntimeBridge
- ClaudeRuntimeBridge
- DshRuntimeBridge

Keep tool mapping consistent:
- run_command
- view_file
- replace_file_content
- write_to_file
- invoke_subagent
- manage_subagents
- schedule

## 13. SUBAGENTS

invoke_subagent uses:
- Role
- TypeName
- Prompt
- Model
- Workspace

Subagent updates must match by conversationId or role.

When the parent session ends:
- active/running/waiting subagents → DONE or ERRORED
- background tasks → COMPLETED or FAILED

No task/subagent may remain RUNNING after the parent session ends.

Inspector must support:
- Stop
- Clear finished
- Clear errored

## 14. RUNTIME PROTECTION

Unless explicitly requested, UI work MUST NOT modify:
- task execution
- TaskSupervisor
- recovery
- persistence/database
- Brain implementation
- execution locks
- process supervision
- wake locks
- retry logic
- checkpoints
- terminal execution
- subagent lifecycle
- background tasks

Runtime work must not redesign UI unless requested.

## 15. PERSISTENCE

When changing persistence:
- preserve schema compatibility
- use existing migrations
- never silently delete user data
- never reset databases during normal development
- preserve durable state and restart recovery

Schema changes require appropriate tests.

## 16. COMPOSE UI

Use:
- Jetpack Compose
- Material 3
- existing project tokens
- existing shared components

Preserve:
- navigation
- state restoration
- back handling
- callbacks/actions
- accessibility
- task/session state
- persistence

Visual redesign does not grant permission to change application behavior.

Use project tokens such as:
- PocketBlue
- PocketGreen
- PocketOrange

Avoid deprecated Compose APIs.

## 17. LIQUID GLASS

For Liquid Glass work:
- use the installed design skill
- use the existing Liquid Glass dependency
- reuse existing infrastructure

Prefer existing:
- LiquidGlassMaterial
- LiquidGlassSurface
- LiquidGlassHost
- LiquidGlassConfig
- LiquidGlassFloatingNavBar
- LiquidGlassFloatingNavBarItem
- LiquidGlassTopBar
- LiquidGlassSheet
- LiquidGlassDialog
- LiquidGlassInputCapsule
- LiquidGlassPill
- LiquidGlassSegmentedControl
- LiquidGlassCard

Do not create parallel abstractions when an existing primitive works.

Use glass primarily for:
- navigation
- controls
- floating actions
- sheets
- dialogs
- transient UI

Prefer solid surfaces for:
- primary content
- chat
- code
- terminal
- files
- diffs
- WebView
- sensitive inputs

Avoid unnecessary backdrop captures.

Reduced transparency must provide high-contrast solid surfaces.

Interactive elements must maintain at least 44dp touch targets.
## 18. PERFORMANCE

Avoid unnecessary:
- recompositions
- allocations
- process creation
- database queries
- repeated I/O
- polling
- wake locks
- background workers
- backdrop captures

Prefer event-driven behavior.

Reuse existing lifecycle-aware state and coroutine scopes.

Do not introduce processes, workers, or wake locks without a concrete requirement.

## 19. VERIFICATION

Standard verification:

./gradlew :app:compileOnlineDebugKotlin
./gradlew :app:testOnlineDebugUnitTest
./gradlew assembleOnlineDebug

Run targeted tests when useful, then the complete required suite.

All required tests must pass.

Gradle tasks may take 20–90 seconds.
When background execution is available:
- run long tasks asynchronously
- do not busy-poll
- wait for completion notifications

Never claim verification unless actually performed.

Use:
- VERIFIED
- NOT RUN
- FAILED
- BLOCKED

Never invent test counts, hashes, file sizes, commits, or runtime observations.

## 20. APK DISTRIBUTION

After successful:

./gradlew assembleOnlineDebug

run:

cp -f app/build/outputs/apk/online/debug/app-online-debug.apk app-online-debug.apk
cp -f app-online-debug.apk mobile-harness-dev.apk

Then:

stat -c "%s %n" *.apk
md5sum *.apk

Report actual:
- APK size
- MD5
- build result

## 21. CODE QUALITY

Preserve existing formatting and architecture.

Do not introduce unnecessary warnings.

Do not suppress warnings/errors unless necessary, justified, and narrowly scoped.

Treat build outputs, generated sources, caches, and IDE metadata as generated.
Do not hand-edit generated output.

## 22. GIT

Do NOT commit unless explicitly requested.
Do NOT push unless explicitly requested.

When a commit is requested:
1. inspect git status
2. review changed files
3. verify tests/build
4. commit only intended changes
5. report commit hash

Never include unrelated changes.

## 23. REPORTING

Report concisely:

Changed:
- relevant files
- important implementation details

Verification:
- actual compile/test/build results

APK:
- filename
- size
- MD5, if assembled

Issues:
- actual unresolved issues only

Do not narrate every tool call.

## 24. FILE LINKS

When reporting created/modified/relevant files, provide workspace links:

[FileName.kt](file:///workspace/clever-kalam/path/to/FileName.kt)

Use only real paths.
Never invent file links.

## 25. FINAL LOOP

For non-trivial tasks:

BRAIN
→ TARGETED DISCOVERY
→ BATCH READ
→ PLAN
→ COHERENT EDIT
→ VERIFY
→ BUILD
→ LEARN VERIFIED RESULTS
→ REPORT
→ STOP

Do not use:

SEARCH → READ → SEARCH → READ → SEARCH → READ

The agent should maximize:
- correctness
- reuse
- Brain utilization
- information per tool call
- scope discipline
- verification integrity
- implementation efficiency

Once the requested task is complete and verified, STOP.
