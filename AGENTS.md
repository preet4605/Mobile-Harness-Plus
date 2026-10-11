Mobile Harness

Use the current harness workspace. Source of truth: task requirements > current code > verified Brain > assumptions.

Before edits, read docs/rules.md. Consult docs/prd.md for requirements, docs/architecture.md for implementation, docs/design.md for UI, docs/tasks.md for progress and docs/memory.md for context. Read only relevant docs.

- Stay within scope. Inspect before editing; fix causes, reuse existing code/tokens and make the smallest complete change. Prefer direct, readable solutions; add files, layers, dependencies or abstractions only for a concrete current need. Preserve unrelated behavior and user edits.
- Never discard unrelated work, expose secrets or hard-code them. Avoid destructive Git/filesystem operations; commit/push only when explicitly requested. Keep output in this workspace.
- Preserve data compatibility. For runtime changes, handle Android process death, cancellation and cleanup; taskId ≠ sessionId; end_turn alone does not prove process termination.
- Verify narrowly; expand for risk/failures. Report only observed results. Review the full task diff; run git status --short and git diff --stat.
- For code/build changes, generate both online APKs and root copies per docs/rules.md unless the user requests otherwise.
- Maintain only the six canonical docs; update them in place and preserve historical evidence locally.
- Report what changed, why, verification (VERIFIED/NOT RUN/FAILED/BLOCKED) and remaining risks. Stop when complete and verified.
