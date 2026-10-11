# Coding rules

[AGENTS.md](../AGENTS.md) remains the agent entry point. Follow the task and current workspace constraints; read only relevant documentation. Source of truth: task requirements, current code, verified project context, then assumptions.

Workflow: Brain → targeted discovery → batch read → plan → edit → verify → build → report → stop. Explicit task instructions override defaults, including a request to skip APK generation.

## Smallest complete change

- Inspect relevant implementation, callers and tests; use relevant Brain facts once and verify them against code.
- Fix the cause, preserve unrelated behavior/user edits, and reuse components, utilities, dependencies and design tokens.
- For features, extend existing components through established interfaces and deliver the requested behavior end to end. For fixes, reproduce the failure and change the path that owns the cause; redesign only when the correction requires it.
- Search before adding a file, layer, dependency, option or abstraction. Prefer direct readable code; extract when it clarifies existing logic, enforces a shared invariant or isolates a necessary resource, protocol or trust boundary. One caller can justify a helper; similar-looking code alone does not justify combining different behavior.
- Reject speculative flexibility, pass-through wrappers and unrelated cleanup. During diff review, check that new structure has a current purpose and briefly explain non-obvious choices. Fewer lines alone are not a quality measure.
- Use idiomatic types, strict null safety, immutable data where practical and clear APIs. Validate supported boundary inputs, surface errors and retain cleanup.
- Reproduce bugs and write meaningful regressions before production fixes. Test observable behavior using existing fixtures. Verify compilation, relevant tests and required style/lint checks before declaring code complete.
- Batch independent searches/reads, use rg and stop discovery once the affected path is understood. Reuse already-read context; do not reread unchanged code or run unrelated tests. Repeat checks only for changed code or new evidence. Measure before/after performance changes.

## Scope and runtime safety

- UI work must preserve persistence, supervision, retry/recovery, execution/wake locks, terminal execution and subagent lifecycle unless required. Runtime work must preserve UI unless required.
- Assume Android process death. Check cancellation, timeouts, retry/duplicate execution, stale state, task/session identity, child cleanup, CPU/memory usage and battery impact where relevant.
- taskId and sessionId are distinct. Stream events such as end_turn are not process termination without an explicit contract.
- Preserve data compatibility/migrations, login, configuration and resume. Keep engine protocols in existing bridges; preserve shared-provider behavior.
- For provider URLs ending in /v1, prioritize $base/models and avoid duplicate /v1/v1/ paths.
- Prefer bounded, event-driven, lifecycle-aware, cancellable work. Avoid busy loops, unnecessary polling/I/O, duplicate workers and unnecessary wake locks.

## Security and Git

Treat external/cross-process input as untrusted. Validate canonical containment, reject traversal/symlink escapes and apply least privilege. Never expose or hard-code secrets. Keep output in this workspace; avoid destructive operations. Never overwrite, revert, reset or discard unrelated work. Never commit or push without explicit authorization. Preserve licenses and attribution.

Use threat modeling at changed trust boundaries, including spoofing, tampering, information disclosure and privilege escalation.

## Phone workflow and APKs

Use installed Gradle and configured ARM64 Android tools. Do not add an aapt2 override or change dependency/build-tool versions without need. Process utilities such as ps/top/free/uptime may fail in PRoot; use bounded harness-tools doctor checks. Log long commands and give progress updates. Keep Preview open during inspection; treat tool errors as failures.

After code/build changes, generate both online variants and place verified APKs in the root:

    gradle assembleOnlineDebug assembleOnlineRelease --console=plain
    cp -f app/build/outputs/apk/online/debug/app-online-debug.apk app-online-debug.apk
    cp -f app-online-debug.apk mobile-harness-dev.apk
    cp -f app/build/outputs/apk/online/release/app-online-release.apk app-online-release.apk
    cp -f app-online-release.apk mobile-harness-stable.apk
    stat -c "%s %n" *.apk
    md5sum *.apk

Run narrow relevant tests first; expand according to blast radius/failures. Only report commands that actually ran. Use -PphoneTests only for the established optional test Conscrypt workaround; it does not establish TLS coverage. Before finishing run git status --short, git diff --stat and review the full task diff, including staged/new files.

## Documentation and completion

Maintain only prd.md, architecture.md, rules.md, design.md, tasks.md and memory.md in docs/. Update the relevant file rather than adding a report for every task. Keep progress in [tasks.md](tasks.md), stable facts in [memory.md](memory.md) and historical evidence locally. Do not inject all six documents into every prompt.

When documenting features, check screen callbacks and handlers as well as declarations. Distinguish current controls, internal helpers, prompt templates, provider-dependent events and planned requirements. Keep the source-backed inventory in [prd.md](prd.md#current-feature-inventory) current; source inspection and old test receipts do not establish new device/provider verification.

Report what changed, why, verification and remaining risks using VERIFIED, NOT RUN, FAILED or BLOCKED. Never invent measurements, hashes or outcomes. When the authorized work is complete and verified, stop.
