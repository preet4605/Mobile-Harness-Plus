Mobile Harness

workspace/curious-curie

Rules

- Follow explicit task requirements.
- Preserve existing behavior outside requested changes.
- Stay within scope.
- Inspect before editing; fix root causes, not symptoms.
- Reuse existing architecture, components, utilities, dependencies, and design tokens.
- Make the smallest complete change.
- Never invent results, observations, hashes, sizes, commits, or test status.
- Never overwrite, revert, reset, or discard unrelated user changes.
- Never commit or push unless explicitly requested.
- Never expose or hard-code secrets.
- When complete and verified, STOP.

Source of Truth

"TASK REQUIREMENTS > CURRENT CODE > VERIFIED BRAIN > ASSUMPTIONS"

Use relevant Project Brain knowledge before non-trivial discovery. Verify Brain knowledge against current code when it matters. Do not repeatedly retrieve the same context.

Workflow

"BRAIN → TARGETED DISCOVERY → BATCH READ → PLAN → EDIT → VERIFY → BUILD → REPORT → STOP"

- Search before creating new abstractions.
- Prefer batch reads and targeted inspection.
- Reuse already-read context.
- Stop exploring once the relevant implementation and call sites are understood.
- Do not reread unchanged code or run unrelated tests.

Scope

UI work must not alter runtime, persistence, recovery, process supervision, retry logic, execution locks, wake locks, terminal execution, or subagent lifecycle unless required.

Runtime work must not redesign UI unless required.

Do not change dependencies or build-tool versions unless necessary.

Runtime

Assume Android process death.

For runtime changes, verify relevant:

- cancellation/timeouts
- retry/duplicate execution
- stale state
- "taskId" / "sessionId"
- child-process cleanup
- CPU/memory usage
- battery impact

"taskId" ≠ "sessionId".

Do not treat provider stream events such as "end_turn" as process/session termination unless the provider contract guarantees it.

Safety

Keep persistent project output inside "/workspace/curious-curie".

Avoid destructive Git/filesystem operations.

Preserve schema/data compatibility and use existing migrations.

Keep engine-specific protocol logic inside existing runtime bridges.

Performance

Prefer event-driven, lifecycle-aware, cancellable work.

Avoid busy loops, unnecessary polling, duplicate processes/workers, excessive I/O, and unnecessary wake locks.

Verification

Run the narrowest relevant verification first; expand according to blast radius.

Only report commands that actually ran.

Use:

"VERIFIED | NOT RUN | FAILED | BLOCKED"

Before finishing:

git status --short
git diff --stat

Inspect the full diff when changes were made.

State

For long investigations, use "docs/WORK_STATE.md".

Keep detailed audits, history, architecture notes, and procedures in "docs/", not here.

Final

Report only:

- what changed
- why
- verification
- remaining risks

Then STOP.