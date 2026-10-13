package com.jarves.mh.mods

import org.json.JSONArray
import org.json.JSONObject

/**
 * What Mod Studio writes into a new draft for the agent that builds the mod: `MODDING.md`, built
 * from the same tables the app checks against, a starting `mod.json`, and short pointers for each
 * harness's instruction file. Only what this version of the app can run is described.
 */
object ModAuthoringGuide {
    const val GUIDE_FILE = "MODDING.md"

    /** Instruction files each harness reads by itself; each points at [GUIDE_FILE]. */
    val POINTER_FILES = listOf("AGENTS.md", "CLAUDE.md", "GEMINI.md")

    fun starterManifest(id: String, name: String, description: String): String = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("version", "0.1.0")
        .put("apiVersion", ModManifestParser.MAX_API_VERSION)
        .put("description", description.take(300))
        .put("permissions", JSONArray())
        .put("contributes", JSONObject())
        .toString(2) + "\n"

    fun pointer(): String = """
        # Mod draft

        This folder is a Mobile Harness mod draft. Read `$GUIDE_FILE` before changing anything, and
        build the mod only in the files it describes. The user installs it from Mods > Studio.
    """.trimIndent() + "\n"

    fun firstPrompt(idea: String): String =
        "Build a Mobile Harness mod in this folder. Read $GUIDE_FILE first and keep to what it allows.\n\n" +
            "What the mod should do: ${idea.trim()}"

    fun markdown(): String = buildString {
        appendLine("# Building a Mobile Harness mod")
        appendLine()
        appendLine("This folder is a mod draft. You write the mod here; the user installs it from")
        appendLine("Mods > Studio > Install, where the app checks it and lists any problems.")
        appendLine()
        appendLine("## What a mod can do in this version")
        appendLine()
        appendLine("- **Slash commands** that send a prompt template: the user types `/name some text`.")
        appendLine("- **Prompt templates** the user picks from the `/` menu to fill the message box.")
        appendLine("- **Settings** the user changes on the mod's page; templates read them as `{{settings.key}}`.")
        appendLine("- **Rules**: Markdown files added to every prompt while the user keeps them on (8 KB in all).")
        appendLine("- **Skills**: folders with a `SKILL.md` the user runs as `/skill-id`.")
        appendLine("- **Automatic actions** (hooks): when an app event happens, show a toast, post a")
        appendLine("  notification, post a note in the chat, put a draft in the message box, or append")
        appendLine("  a line to a project file.")
        appendLine()
        appendLine("Not supported yet, so never add them: a code module (`hooks` at the top level), `types`,")
        appendLine("`dashboard`, `contributes.panels`, `contributes.guestTools`, `contributes.mcpServers`.")
        appendLine()
        appendLine("## Files")
        appendLine()
        appendLine("Only these files become the mod. Everything else in this folder, this guide included, stays out.")
        appendLine()
        appendLine("- `mod.json` (required): the manifest described below.")
        appendLine("- The rule files and skill folders `mod.json` names, for example `rules/style.md` and `skills/review-pr/SKILL.md`.")
        appendLine("- `README.md` and anything under `assets/` (optional).")
        appendLine()
        appendLine("Do not write `integrity`: the app fills it in when the user installs the draft. No links,")
        appendLine("at most ${ModPackage.MAX_FILES} files and 20 MB, `mod.json` at most 64 KB, each `SKILL.md` at most 64 KB.")
        appendLine()
        appendLine("## mod.json")
        appendLine()
        appendLine("```json")
        appendLine(EXAMPLE.trimIndent())
        appendLine("```")
        appendLine()
        appendLine("Unknown fields are errors. Top-level fields:")
        appendLine()
        appendLine("| Field | Rule |")
        appendLine("| --- | --- |")
        appendLine("| `id` | Lowercase, dotted, like `studio.you.mod-name`. Keep the one you were given. |")
        appendLine("| `name` | 1 to 60 characters. |")
        appendLine("| `version` | Like `1.0.0`. Raise it when you change a mod the user already installed. |")
        appendLine("| `apiVersion` | `${ModManifestParser.MAX_API_VERSION}`. |")
        appendLine("| `description`, `author` | Optional, up to 300 and 80 characters. |")
        appendLine("| `permissions` | One entry per capability the mod uses: `capability`, a short `reason`, and for `files.write` a `scope.paths` list of globs. |")
        appendLine("| `contributes` | The parts below. |")
        appendLine()
        appendLine("### contributes")
        appendLine()
        appendLine("- `slashCommands`: `name` (lowercase, 2 to 31 characters, not a built-in command), `description`,")
        appendLine("  `template` (may use `{{args}}`, the text after the command, and `{{settings.key}}`), and optional")
        appendLine("  `agents`, a list from `claude`, `deepseek`, `antigravity`, `codex`.")
        appendLine("- `promptTemplates`: `id`, `title`, `template` (may use `{{settings.key}}`).")
        appendLine("- `settings`: `key` (lowercase letters, digits, `_`), `title`, `type` (`bool`, `enum`, `string`, `number`, `secret`),")
        appendLine("  `default` (required except for `secret`, which may have none), `options` for `enum`, optional")
        appendLine("  `maxLength` and `description`. Secret settings never appear in templates.")
        appendLine("- `rules`: `id` and `file` (a `.md` path). Rules start off; the user turns each one on.")
        appendLine("- `skills`: `id` (the slash name; not a built-in command) and `dir`, a folder holding `SKILL.md`")
        appendLine("  with `name` and `description` front matter. Skills start off too.")
        appendLine("- `hooks`: `id`, `on` (an event below), `action`, and an optional `filter` with `status`")
        appendLine("  (for events that have one) and `harness`.")
        appendLine()
        appendLine("Command names, template ids and skill ids share the `/` menu, so each must be different.")
        appendLine("Built-in commands: ${com.jarves.mh.runtime.SlashCommandEngine.ALL_SLASH_COMMANDS.joinToString(", ") { "`/${it.name}`" }}.")
        appendLine()
        appendLine("### Hook actions")
        appendLine()
        appendLine("| `action.type` | Fields | Needs permission |")
        appendLine("| --- | --- | --- |")
        appendLine("| `ui.toast` | `text` | `ui.toast` |")
        appendLine("| `notify` | `title`, `body` | `notify` |")
        appendLine("| `chat.post` | `text` (shown in the chat, never sent to the agent) | `chat.post` |")
        appendLine("| `prompt.draft` | `text` (fills an empty message box; never sends) | `prompt.draft` |")
        appendLine("| `files.append` | `path` (inside the `files.write` scope), `line` | `files.write` |")
        appendLine()
        appendLine("Action text may use the variables of its event plus these, everywhere:")
        appendLine(ModEvent.COMMON_VARS.joinToString(", ") { "`{{$it}}`" } + ", and `{{settings.key}}`.")
        appendLine("Using `{{message.text}}` also needs the `chat.read` permission. Each action is rate limited")
        appendLine("(a toast at most once a second, a notification once a minute), and a hook that fails five")
        appendLine("times in a row is turned off.")
        appendLine()
        appendLine("### Events")
        appendLine()
        appendLine("| `on` | Meaning | Variables | `filter.status` values |")
        appendLine("| --- | --- | --- | --- |")
        ModEvent.entries.forEach { event ->
            val vars = event.vars.joinToString(", ") { "`{{$it}}`" }.ifEmpty { "none" }
            val statuses = event.statuses.joinToString(", ") { "`$it`" }.ifEmpty { "none" }
            appendLine("| `${event.id}` | ${event.label} | $vars | $statuses |")
        }
        appendLine()
        appendLine("### Permissions")
        appendLine()
        appendLine("Ask only for what the mod uses. The user sees each one with its reason before installing.")
        appendLine()
        appendLine("| `capability` | Risk | What it allows |")
        appendLine("| --- | --- | --- |")
        ModCapability.entries.filter { it.usable }.forEach { capability ->
            appendLine("| `${capability.id}` | ${capability.risk.name.lowercase()} | ${capability.summary} |")
        }
        appendLine()
        appendLine("These files can never be written by a mod: `.claude/`, `.codex/`, `.agents/`, `.gemini/`, `.git/`,")
        appendLine("`.github/workflows/`, `.mh/`, `.mcp.json`, `.claude.json`, `CLAUDE.md`, `AGENTS.md`, `GEMINI.md`, `.gitattributes`.")
        appendLine()
        appendLine("## Checking your work")
        appendLine()
        appendLine("- Run `python3 -m json.tool mod.json > /dev/null` to catch JSON mistakes.")
        appendLine("- Check every file `mod.json` names exists, and every `{{variable}}` is allowed where it is used.")
        appendLine("- Then tell the user to open Mods > Studio and tap Install on this draft.")
    }

    private const val EXAMPLE = """
        {
          "id": "studio.you.build-notes",
          "name": "Build notes",
          "version": "0.1.0",
          "apiVersion": 2,
          "description": "Keeps a log of finished tasks and adds a /recap command.",
          "permissions": [
            { "capability": "ui.toast", "reason": "Say when a task ends" },
            { "capability": "files.write", "reason": "Keep notes/log.md", "scope": { "paths": ["notes/log.md"] } }
          ],
          "contributes": {
            "settings": [
              { "key": "tone", "title": "Recap tone", "type": "enum", "options": ["short", "detailed"], "default": "short" }
            ],
            "slashCommands": [
              { "name": "recap", "description": "Summarize what changed", "template": "Give a {{settings.tone}} recap of {{args}}." }
            ],
            "rules": [ { "id": "style", "file": "rules/style.md" } ],
            "hooks": [
              {
                "id": "done-toast", "on": "task.finished", "filter": { "status": "completed" },
                "action": { "type": "ui.toast", "text": "Done in {{task.duration}}" }
              },
              {
                "id": "log", "on": "task.finished",
                "action": { "type": "files.append", "path": "notes/log.md", "line": "{{date}} {{time}} {{task.status}}" }
              }
            ]
          }
        }
    """
}
