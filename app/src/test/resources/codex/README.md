Captured from a real `codex exec --json` run (codex-cli 0.161.0, linux x64) against a local mock
Responses server. Not hand-written, except where a test marks its input as synthesized.

- text.jsonl: reasoning + agent message + turn.completed
- shell.jsonl: command_execution started/completed
- auth-failure.jsonl: 401 -> error + turn.failed
- unsupported-tool.jsonl: a tool the model was not offered (stderr noise omitted)
- stream-dropped.jsonl: agent message, then the stream drops -> error + turn.failed
