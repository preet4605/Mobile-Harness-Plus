package com.jarves.mh.ui

/**
 * The app's terminals run each command with plain pipes, not a terminal device (PTY), and show
 * its output as text. Full-screen programs (agent TUIs, editors, pagers, monitors) need a real
 * terminal: `codex` stops with "stdin is not a terminal" and others hang or print escape codes.
 * This names such a command before it runs, so the terminal can say what to do instead.
 *
 * Only the first command of a line is checked, and only when the line is that one command: in a
 * pipe or a command list the program usually reads from or writes to the pipe, which works.
 */
internal fun fullScreenProgramNotice(command: String): String? {
    val line = command.trim()
    if (line.isEmpty() || line.any { it in "|;&<>`\n" } || "$(" in line) return null
    val isAssignment = { word: String -> '=' in word && !word.startsWith("-") }
    var words = line.split(Regex("\\s+")).dropWhile(isAssignment)
    if (words.firstOrNull()?.substringAfterLast('/') in setOf("sudo", "env")) words = words.drop(1).dropWhile(isAssignment)
    val program = words.firstOrNull()?.substringAfterLast('/') ?: return null
    val args = words.drop(1)
    if (args.any { it in INFO_FLAGS }) return null
    val agent = when (program) {
        "codex" -> "Codex".takeIf { args.firstOrNull() !in CODEX_ONE_SHOT }
        "claude" -> "Claude".takeIf { args.none { it == "-p" || it == "--print" } && args.firstOrNull() !in CLAUDE_ONE_SHOT }
        "gemini" -> "Gemini".takeIf { args.none { it == "-p" || it == "--prompt" } && args.firstOrNull() !in GEMINI_ONE_SHOT }
        else -> null
    }
    if (agent != null) {
        val oneShot = when (program) {
            "codex" -> "codex exec \"…\""
            "claude" -> "claude -p \"…\""
            else -> "gemini -p \"…\""
        }
        return "$program opens a full-screen app, and this terminal is not a real terminal (no PTY), so it can't run here. " +
            "Ask $agent in the Chat tab, or run a one-shot command such as $oneShot."
    }
    if (program in FULL_SCREEN_TOOLS) {
        return "$program is a full-screen program, and this terminal is not a real terminal (no PTY), so it can't run here. " +
            "Use a command that prints its result instead (for example cat, sed or ps), or ask the agent in the Chat tab."
    }
    return null
}

private val INFO_FLAGS = setOf("--version", "-V", "--help", "-h")

/** Subcommands that run to completion without a terminal (codex-cli 0.16x). */
private val CODEX_ONE_SHOT = setOf(
    "exec", "e", "login", "logout", "mcp", "mcp-server", "app-server", "completion", "apply", "a",
    "debug", "sandbox", "features", "help",
)
private val CLAUDE_ONE_SHOT = setOf("config", "mcp", "update", "doctor", "install", "setup-token", "auth", "plugin", "migrate-installer")
private val GEMINI_ONE_SHOT = setOf("mcp", "extensions")

private val FULL_SCREEN_TOOLS = setOf(
    "vi", "vim", "nvim", "nano", "emacs", "top", "htop", "btop", "tmux", "screen", "mc", "ranger",
    "watch", "tig", "lazygit",
)
