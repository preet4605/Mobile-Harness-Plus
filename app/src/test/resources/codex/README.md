Captured from a real `codex exec --json` run (codex-cli 0.161.0, linux x64) against a local mock
Responses server. Not hand-written, except where a test marks its input as synthesized.

- text.jsonl: reasoning + agent message + turn.completed
- shell.jsonl: command_execution started/completed
- auth-failure.jsonl: 401 -> error + turn.failed
- unsupported-tool.jsonl: a tool the model was not offered (stderr noise omitted)
- stream-dropped.jsonl: agent message, then the stream drops -> error + turn.failed

Code mode (added after a device report that every tool call failed with
"failed to spawn code-mode host /usr/local/bin/codex-code-mode-host"):

- code-mode.jsonl: the model's catalog entry selects code mode (`tool_mode: code_mode_only`, set through
  `-c model_catalog_json=…` to mimic the ChatGPT account catalog); `codex-code-mode-host` sits next to the
  binary; the model's `exec` cell runs a shell command -> normal command_execution events.
- code-mode-no-host.jsonl: the same run without the helper binary; Codex reports "Code Mode is unavailable"
  and the model's cell never runs. The host path in the message was rewritten from the capture directory to
  /usr/local/bin.
