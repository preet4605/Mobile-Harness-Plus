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

    /** The bundled "mod-authoring" skill (spec v2 §6.5), so any harness can build a mod anywhere. */
    fun skill(): String = """
        ---
        name: mod-authoring
        description: Build Mobile Harness mods (mod.json, a hooks module with on() and the ${'$'} API, dashboards) and check them with the mh-mod kit.
        ---

        # Building a Mobile Harness mod

        - Start a mod with `mh-mod init dev.yourname.mod-name`, or work in a Mod Studio draft, which has
          the full guide in `$GUIDE_FILE`. Read that guide before writing code; it lists every event, `${'$'}` call
          and permission.
        - Check your work before you say it is done: `mh-mod validate` (what the app would refuse) and
          `mh-mod test` (runs `tests/*.test.js` against the app's own shim with a mocked host).
        - A mod is one hooks module (`hooks/register.js`, plain JavaScript, no imports) plus mod.json.
          Ask only for the permissions it uses, with a reason each.
        - The user installs it from Mods > Studio (or imports a .mhmod). Once installed from a draft,
          each finished agent run in the draft reloads it, unless it asks for new permissions.
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
        appendLine("- **Mod code**: one JavaScript file with hooks for app events, its own saved data, timers,")
        appendLine("  a status line, and commands that answer in code. See \"Mod code\" below. Use it only")
        appendLine("  when the simple parts above can't do the job.")
        appendLine("- **Dashboards and panels** drawn by mod code with the app's own text, buttons, switches, lists,")
        appendLine("  progress bars and images: on the mod's page, in a project's Mods tab, and as a row above the message box.")
        appendLine("  See \"Drawing\" below.")
        appendLine()
        appendLine("- **Web panels**: HTML pages from the mod's own files, opened from its page; they talk to the mod code")
        appendLine("  through messages only. See \"Web panels\" below.")
        appendLine()
        appendLine("Not supported yet, so never add it: `types`.")
        appendLine()
        appendLine("## Files")
        appendLine()
        appendLine("Only these files become the mod. Everything else in this folder, this guide included, stays out.")
        appendLine()
        appendLine("- `mod.json` (required): the manifest described below.")
        appendLine("- The rule files, skill folders and hooks module `mod.json` names, for example `rules/style.md`,")
        appendLine("  `skills/review-pr/SKILL.md` and `hooks/register.js`.")
        appendLine("- `README.md` and anything under `assets/` (optional). A web panel's scripts, styles and images go under `assets/`.")
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
        appendLine("| `hooks` | Optional: `{ \"module\": \"hooks/register.js\" }`, the mod code file (`.js` or `.mjs`, at most 512 KB). |")
        appendLine("| `dashboard` | Optional, needs `hooks`: `{ \"pane\": \"main\", \"actions\": [\"refresh\"] }`. The pane id the mod's page shows first, and up to 8 of the mod's commands shown as buttons under it. |")
        appendLine()
        appendLine("### contributes")
        appendLine()
        appendLine("- `slashCommands`: `name` (lowercase, 2 to 31 characters, not a built-in command), `description`,")
        appendLine("  `template` (may use `{{args}}`, the text after the command, and `{{settings.key}}`), and optional")
        appendLine("  `agents`, a list from `claude`, `deepseek`, `antigravity`, `codex`. With mod code, leave out")
        appendLine("  `template` and answer the command in code (see \"Mod code\").")
        appendLine("- `promptTemplates`: `id`, `title`, `template` (may use `{{settings.key}}`).")
        appendLine("- `settings`: `key` (lowercase letters, digits, `_`), `title`, `type` (`bool`, `enum`, `string`, `number`, `secret`),")
        appendLine("  `default` (required except for `secret`, which may have none), `options` for `enum`, optional")
        appendLine("  `maxLength` and `description`. Secret settings never appear in templates.")
        appendLine("- `rules`: `id` and `file` (a `.md` path). Rules start off; the user turns each one on.")
        appendLine("- `skills`: `id` (the slash name; not a built-in command) and `dir`, a folder holding `SKILL.md`")
        appendLine("  with `name` and `description` front matter. Skills start off too.")
        appendLine("- `hooks`: `id`, `on` (an event below), `action`, and an optional `filter` with `status`")
        appendLine("  (for events that have one) and `harness`.")
        appendLine("- `guestTools` (up to 8) and `mcpServers` (up to 4), which need `guest.tools` (Developer mode): `name`")
        appendLine("  (lowercase letters, digits, `_`), `description`, `command` (the argv, such as `[\"node\", \"./tools/lint.js\"]`;")
        appendLine("  `./` items are files in the mod, the first item is a program name or a `./` file), and optional `env`")
        appendLine("  (up to 16 strings; not PATH, HOME, MOD_DIR, LD_*, NODE_OPTIONS). Before each task the app copies the mod")
        appendLine("  into the Linux guest at `${ModGuestTools.GUEST_ROOT}/pkg/<id>` (`\$MOD_DIR`). A guest tool becomes the command")
        appendLine("  `${ModGuestTools.GUEST_ROOT}/bin/mod_<last part of the id>_<name>`, listed to every harness, run in the agent's folder")
        appendLine("  with the agent's arguments, ${ModGuestTools.TOOL_CPU_SECONDS} s of CPU and 1 GB of data memory. An MCP server is started by")
        appendLine("  Claude and Codex for their task (${ModGuestTools.SERVER_CPU_SECONDS} s of CPU, 1 GB). Turning the mod or Developer mode off kills them.")
        appendLine("- `panels` (up to 4), which need `ui.webpane`: `id` (lowercase letters, digits, dashes), `title`")
        appendLine("  (up to 40 characters) and `entry`, a `.html` file in the mod. See \"Web panels\".")
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
            val developer = if (capability.developer) " (Developer mode)" else ""
            appendLine("| `${capability.id}` | ${capability.risk.name.lowercase()}$developer | ${capability.summary} |")
        }
        appendLine()
        appendLine("Permissions marked Developer mode work only while the user has Developer mode on in Mods, and")
        appendLine("only after they allow each one for the mod. Turning Developer mode off stops them at once.")
        appendLine()
        appendLine("These files can never be written by a mod: `.claude/`, `.codex/`, `.agents/`, `.gemini/`, `.git/`,")
        appendLine("`.github/workflows/`, `.mh/`, `.mcp.json`, `.claude.json`, `CLAUDE.md`, `AGENTS.md`, `GEMINI.md`, `.gitattributes`.")
        appendLine()
        appendLine(CODE_GUIDE.trimIndent())
        appendLine()
        appendLine("## Checking your work")
        appendLine()
        appendLine("- Run `python3 -m json.tool mod.json > /dev/null` to catch JSON mistakes.")
        appendLine("- Check every file `mod.json` names exists, and every `{{variable}}` is allowed where it is used.")
        appendLine("- Run `mh-mod validate` here: it checks mod.json, permissions and the hooks module the way the app")
        appendLine("  loads them. Then `mh-mod test` runs `tests/*.test.js` against the app's own shim with a mocked host:")
        appendLine("  `test('name', async () => { const mod = await loadMod({ mocks: { 'agent.ask': () => ({ text: 'ok' }) } });")
        appendLine("  await mod.emit('run.end', { status: 'completed' }); assert.deepEqual(mod.toasts, ['Done']) })`.")
        appendLine("  `mod.gate(event, e)`, `mod.render(surface, e)` and `mod.press(surface, key)` test gates and drawings;")
        appendLine("  `mod.calls`, `mod.store`, `mod.logs`, `mod.commands` and `mod.tools` show what it did.")
        appendLine("  After installing, the mod's page shows whether its code runs (Controls) and its log (Activity).")
        appendLine("- After the user installs this draft once, every agent run you finish here reloads the mod, so they")
        appendLine("  see your changes on its page. New or wider permissions wait until they tap Install again.")
        appendLine("- Then tell the user to open Mods > Studio and tap Install on this draft.")
    }

    private const val CODE_GUIDE = """
        ## Mod code

        Name one JavaScript file in `mod.json` as `"hooks": { "module": "hooks/register.js" }`. The app
        runs it in a locked-down sandbox with no files, no network, no DOM, no Node and no `import`: the
        file is the whole mod, and everything outside it goes through `${'$'}`.

        ```js
        export function register(on, options) {
          on('session.start', async (${'$'}, e, next) => {
            await ${'$'}.command.register({ name: 'tally', description: 'Count finished tasks' })
            return next(e)
          })
          on('run.end', { status: 'completed' }, async (${'$'}, e) => {
            const done = ((await ${'$'}.store.get('done')) || 0) + 1
            await ${'$'}.store.set('done', done)
            await ${'$'}.ui.summary(done + ' tasks finished')
          })
          on('command.run', { name: 'tally' }, async (${'$'}) => ({ text: 'Finished: ' + ((await ${'$'}.store.get('done')) || 0) }))
        }
        ```

        - `register(on, options)` runs once each time the mod loads. `options` holds the mod's settings
          (never secrets). Add every hook there; `on` refuses later calls.
        - `on(event, matcher?, hook)`. A hook is `async (${'$'}, e, next)`; `e` is frozen. A matcher object
          compares each key with `e`: a value must be equal, a RegExp must match, an array lists choices.
        - `next(e)` runs this mod's later hooks for the event and returns their answer. Most events only
          report what happened, so a hook may return without calling `next`. Gates (below) are different:
          there, returning without `next` answers for the action.
        - `on(...).catch((${'$'}, e, next, error) => ...)` answers when that hook throws. Without it, a hook
          that throws is skipped.
        - Every `${'$'}` call returns a promise, except `${'$'}.log` and `${'$'}.clock.now/after/every/cancel`.
          `console.log` writes to the mod log too.
        - `atom(name, initial)`, `read(${'$'}, atom)` and `update(${'$'}, atom, fn)` keep values in memory; they
          are lost when the mod reloads. Use `${'$'}.store` for anything to keep.
        - Limits: each hook gets 10 s of its own time (1 s for `app.*` events); time spent waiting on
          `${'$'}` calls does not count. Code that runs 3 s without awaiting is stopped. Memory is capped at
          16 MB (32 MB for a mod allowed `engine.memory` in Developer mode; 2 such mods at most, the rest
          keep 16 MB), and going over restarts every mod. Five problems (a throw, a timeout, a stop) in 10 minutes
          stop the mod until the user restarts it from its page.

        ### Events for mod code

        Every `e` has `event` and `project` (`{ id, name, harness, chatId }`, or `null` when no project is open).

        | Event | When | More in `e` |
        | --- | --- | --- |
        | `session.start` | The mod loads, or the open project changes | |
        | `session.end` | The project closes, or the mod stops | |
        | `chat.switch` | The user switches chats | `title` |
        | `run.start` | A task starts | `prompt`, `taskId` |
        | `run.end` | A task ends | `status` (`completed`, `failed`, `stopped`), `durationMs`, `error`, `taskId` |
        | `message.end` | The agent finished its reply | `text` (only with `chat.read`) |
        | `tool.call` | The agent starts a tool; for Claude and DeepSeek, and Codex where its switch is on, also a gate before it runs (`canGate: true`, see "Gates") | `tool`, `detail`, `sessionId`; as a gate `tool`, `input`, `harness`, `inputTruncated` |
        | `tool.result` | A tool finished | `tool`, `summary`, `sessionId` |
        | `tool.approval` | The agent asks the user to approve a tool | `tool`, `risk`, `explanation`, `paths`, `command`, `canGate` |
        | `files.changed` | The agent changed files | `paths`, `count` |
        | `usage.update` | Token use changed (at most once a second) | `promptTokens`, `completionTokens`, `cachedTokens`, `contextLimit`, `costUsd` |
        | `terminal.end` | A terminal command finished | `command`, `exitCode` |
        | `build.end` | An Android build ended | `status`, `detail` |
        | `app.foreground`, `app.background` | The app is shown or hidden | |
        | `command.run` | The user ran one of the mod's commands | `name`, `args` |
        | `plan.update` | The open chat's task plan changed | `taskId`, `plan` (`title`, `status`, `current`, `steps`; only with `project.info`) |
        | `run.log` | The running task's live output, batched once a second, only while one of the mod's surfaces is showing | `lines` (`kind`: `reply`, `tool` or `log`; `text`; `time`), `dropped`. Needs `events.subscribe` |
        | `panel.message` | The open web panel sent a message | `panel`, `data` (the text it sent). Needs `ui.webpane` |
        | `ui.render` | The app needs a surface drawn (see "Drawing") | `component`, `requestId`, `title`, `surface` |
        | `prompt.submit` | Gate: the user sends a message (see "Gates") | `text`, `harness`, `attachments` (a count). Needs `prompt.transform` |
        | `prompt.compose` | Gate: a message is about to go to the agent | `prompt`, `harness`, `sections`. Needs `prompt.compose` |
        | `terminal.command` | Gate: the user runs a command in the project terminal | `command`. Needs `terminal.gate` |
        | `files.op` | Gate: the user renames, deletes or saves a file in Files | `op` (`rename`, `delete`, `save`), `path`, `newName`. Needs `files.gate` |
        | `build.start` | Gate: the user starts an Android build | `install`, `force`. Needs `terminal.gate` |

        ### `${'$'}` for mod code

        | Call | Needs permission | Does |
        | --- | --- | --- |
        | `${'$'}.log(level?, ...values)` | none | Writes to the mod log (`debug`, `info`, `warn`, `error`) |
        | `${'$'}.store.get(key)`, `.set(key, value)`, `.delete(key)`, `.keys()` | `storage` | Saved data, kept across restarts; 1 MB in all |
        | `${'$'}.clock.sleep(ms)` | none | Waits, up to 10 minutes |
        | `${'$'}.clock.after(ms, fn)`, `.every(ms, fn)`, `.cancel(id)` | none | Timers (`every` at least 1000 ms, 16 at most); `fn` gets a fresh `${'$'}`; they pause while the app is hidden and no task runs |
        | `${'$'}.ui.toast(text)` | `ui.toast` | A short message at the top of the screen, at most once a second |
        | `${'$'}.ui.status(text)`, `${'$'}.ui.summary(text)` | `ui.status` | One line on the mod's page, or on its card in My mods (80 characters; `null` clears) |
        | `${'$'}.ui.notify({ title, body })` | `notify` | A notification, at most once every 10 s |
        | `${'$'}.command.register({ name, description, argumentHint })`, `.unregister(name)` | none | Adds a `/` command (16 at most) |
        | `${'$'}.project.info()`, `${'$'}.plan.current()` | `project.info` | The open project, and the plan of the running task when the app made one |
        | `${'$'}.app.info()` | `app.info` | The app version |
        | `${'$'}.chat.messages(n)` | `chat.read` | The newest messages of the open chat (`from`, `text`), up to 50 |
        | `${'$'}.chat.append(text)` | `chat.post` | Adds the mod's own message to the chat; the agent never sees it |
        | `${'$'}.prompt.draft(text)` | `prompt.draft` | Fills an empty message box; never sends |
        | `${'$'}.fs.read(path)` / `${'$'}.fs.list(dir)` / `${'$'}.fs.stat(path)` / `${'$'}.fs.exists(path)` | `files.read` | The open project's files (1 MB per read, 500 entries per list), only paths in the permission's `scope.paths` when it has any |
        | `${'$'}.fs.write(path, text)` | `files.write` | Replaces or creates a file (1 MB) inside `scope.paths`, which this permission needs. The edit shows in Changes, where the user can undo it |
        | `${'$'}.secrets.get(key)` / `${'$'}.secrets.set(key, value)` | `secrets` | The mod's own encrypted secrets, including its secret settings; `null` removes one |
        | `${'$'}.prompt.send(text)` | `prompt.send` | Sends a message to the agent in the open chat (at most 3 after each message from the user) |
        | `${'$'}.browser.open(url)` | `browser.open` | Opens an https page in the browser |
        | `${'$'}.http.fetch(url, { method, headers, body })` | `net.fetch` | https only, to the hosts in the permission's `scope.hosts` (`api.example.com`, or `*.example.com`); no cookies or redirects followed; 1 MB, 15 s; resolves `{ status, ok, headers, text }` |
        | `${'$'}.model.complete({ prompt, system, maxTokens })` | `model.complete` | One answer from the user's API-key provider: `{ text, inputTokens, outputTokens, usedToday, dailyLimit }`; 50,000 tokens a day per mod |
        | `${'$'}.model.usage()` | `model.complete` | `{ usedToday, dailyLimit }` |
        | `${'$'}.changes.list()` | `changes.read` | The agent's file changes the user hasn't kept or undone: `{ path, additions, deletions, binary }` |
        | `${'$'}.changes.undo(path)` | `changes.undo` | Undoes the agent's change to one file from its checkpoint; `true` when undone. Only paths in the permission's `scope.paths`, when it has any |
        | `${'$'}.tool.register({ name, description, inputSchema })` | `tools.provide` | Gives the agents a tool; resolves to the name they see (see "Tools for the agents") |
        | `${'$'}.tool.unregister(name)` | `tools.provide` | Takes the tool away |
        | `${'$'}.agent.list()` | `agent.ask` | The harnesses: `{ id, name, installed, current }` |
        | `${'$'}.agent.ask({ harness, prompt, model, effort, chat, timeoutMs })` | `agent.ask` | Runs a task in the open chat (or another chat of the project) on a harness and resolves when it ends (see "Agent tasks") |
        | `${'$'}.agent.stop(taskId)` | `agent.ask` | Stops a task the mod's `agent.ask` started; `true` when it was running |
        | `${'$'}.mods.tools()`, `${'$'}.mods.call(name, input)` | `mods.call` | Other mods' tools (see "Tools for the agents") |
        | `${'$'}.git.status()`, `${'$'}.git.diff({ path, staged })`, `${'$'}.git.log(n)` | `git.read` | The open project's repository: `{ branch, tracking, clean, files: [{ path, index, worktree }] }`, `{ text, truncated }`, and up to 100 `{ hash, author, time, subject }` |
        | `${'$'}.git.commit({ message, all })`, `${'$'}.git.branch(name)`, `${'$'}.git.switch(name)` | `git.write` | Commits (adding every change unless `all: false`) and resolves `{ hash }`; makes and switches to a branch, or switches. Never pushes, resets or forces: git refuses a switch that would lose changes. Waits for no task, terminal command or build to run |
        | `${'$'}.terminal.run(command, { cwd, timeoutMs })` | `terminal.run` | Runs a bash command in the project folder (or `cwd` inside it) in the Linux guest: 2 minutes by default, 10 at most, 1 MB of output; resolves `{ exitCode, output, timedOut, truncated }`. It passes the same `terminal.command` gates as the user's commands, and a gate asking to confirm stops it. One at a time, never while a task, terminal command or build runs; stopping the mod kills it |
        | `${'$'}.build.run({ install })` | `build.run` | Builds the project's Android app (and installs it) through the app's own build, after the `build.start` gates; resolves `{ status, message, issues }` when it ends |
        | `${'$'}.memory.list()` | `memory.read` | The project's memory: `{ key, value, source, updatedAt }` |
        | `${'$'}.ui.open({ id, title })`, `${'$'}.ui.close(id)` | `ui.pane` | Adds a panel (8 at most; `id` is lowercase letters, digits, dashes) or removes one; `open` returns `{ isPlaced }`, whether it is on screen now |
        | `${'$'}.ui.post(panel, data)` | `ui.webpane` | Sends the mod's web panel `panel` a message (text, or JSON for anything else; at most 64 KB). Dropped when that panel isn't open |
        | `${'$'}.ui.redraw(id?)` | none | Draws the mod's shown surfaces again (or just pane `id`) |
        | `${'$'}.ui.resolve(e)` | none | The element names, so `const { Column, Text } = ${'$'}.ui.resolve(e)` works as in other mod systems |

        A `command.run` hook answers with `{ text }`, shown in the chat as the mod's reply (the agent
        never sees it), or `{ prompt }`, sent to the agent as if the user typed it. Declare commands in
        `contributes.slashCommands` without a `template`, or add them with `${'$'}.command.register`.
        A `${'$'}` call without its permission throws an error that names the permission.

        ### Gates

        Gate events happen before an action, and the mod's answer decides it:

        - `return next(e)` lets it go on; `next({ ...e, text })` changes it on the way (only the fields
          the app sent can change, and only `prompt.submit`'s `text` is used).
        - `prompt.submit` may answer `{ drop: 'why' }` (the message is not sent; the user sees why) or
          `{ context: 'text' }` (sent along for the agent to read, at most 8 KB); to change the text and add
          context, answer `{ ...(await next({ ...e, text })), context: 'text' }`.
        - `prompt.compose` adds sections the agent reads before the message:
          `next({ ...e, sections: [...e.sections, 'Always write tests.'] })`, at most 8 KB per mod,
          marked with the mod's id.
        - `terminal.command`, `files.op` and `build.start` may answer `{ deny: 'why' }` (stopped) or
          `{ confirm: 'why' }` (the user is asked first).
        - `tool.call` with `canGate: true` (needs `tools.gate`) may answer `{ deny: 'why' }`, or
          rewrite the call with `next({ ...e, input: { ...e.input, command: 'pnpm test' } })`. A rewritten
          call is never approved by the mod: with approvals on, the user is asked. Match tools by name,
          `on('tool.call', { tool: 'Bash' }, ...)`, so Claude asks the app only about those. An input
          too large to send (`inputTruncated: true`) can be denied but not rewritten. `e.harness` says
          whose call it is. `claude`: tools by Claude's names. `deepseek`: DeepSeek Harness's own tool
          names (it asks about every tool); it can't take a changed input, so a rewrite denies the call
          and tells the agent the input you want. `codex`: only in projects where the user turned on
          "Mods gate Codex" (Developer mode); commands come as `Bash` (`command`, `cwd`) and file changes
          as `apply_patch` (`paths`), and a deny or rewrite declines the step. Antigravity has no gate.
        - `tool.approval` (needs `tools.gate`) may answer `{ deny: 'why' }` before the user answers. A mod
          also allowed `tools.approve` (Developer mode) may answer `{ approve: 'why' }`: the request is
          approved for the user only when no mod denies it and every path and the command stay in the
          project folder (no `..`, `~`, `${'$'}`, backticks or outside absolute paths); otherwise the user
          decides. Mods without `tools.gate` still hear of it, with `canGate: false`.
        - `run.end` (not a gate) may answer `{ followUp: 'text' }` to queue the next message for the agent;
          needs `prompt.send`, and at most 3 follow-ups chain after one message from the user.
        - Mods run one after another; each sees what the mods before it passed on. Gates fail open: a
          hook that throws or runs out of time (3 s; 2 s for `prompt.compose`) lets the action go on,
          unless its `.catch` answers otherwise. A user waits on gates, so keep them fast.

        ### Tools for the agents

        `${'$'}.tool.register` gives every harness a tool the mod answers (at most 16 per mod). Register it in
        `session.start` and answer it with a `tool.call` hook matched by name; the answer is a string,
        `{ text }`, `{ error }` or any object (sent as JSON). The hook has 5 s of its own time and 25 s in
        all, `${'$'}` calls included.

        ```js
        on('session.start', async (${'$'}, e, next) => {
          await ${'$'}.tool.register({ name: 'ticket', description: 'Reads a ticket by number',
            inputSchema: { type: 'object', properties: { id: { type: 'number' } }, required: ['id'] } })
          return next(e)
        })
        on('tool.call', { tool: 'ticket' }, async (${'$'}, e) => ({ text: await ${'$'}.store.get('ticket-' + e.input.id) || 'No such ticket' }))
        ```

        The agents see it as `mod_<last part of the mod id>_<name>`: Claude as an MCP tool (`mcp__mods__…`,
        allowed because you granted `tools.provide`), Codex through its harness-tools MCP server, and
        DeepSeek and Antigravity as the shell command `harness-tools mod <name> '<json>'`, listed in their
        instructions. A tool registered during a task reaches Claude and Codex while they run (their MCP
        server checks every 10 s and tells them the list changed); `harness-tools mod` lists the current ones.

        With `mods.call`, a mod uses tools other mods give: `${'$'}.mods.tools()` lists them (`name`, `mod`,
        `description`, `inputSchema`) and `${'$'}.mods.call(name, input)` resolves `{ text, isError }`. At
        most 8 such calls run at once across all mods, so mods calling each other in a loop fail fast.

        ### Agent tasks

        `${'$'}.agent.ask` lets a mod hand work to any installed harness and read the answer, so one mod
        can run a pipeline: plan on one harness, build on another, review on a third.

        ```js
        on('command.run', { name: 'ship' }, async (${'$'}, e) => {
          const plan = await ${'$'}.agent.ask({ harness: 'codex', prompt: 'Plan this; no code yet: ' + e.args })
          if (plan.status !== 'completed') return { text: 'Planning stopped: ' + (plan.error || plan.status) }
          await ${'$'}.agent.ask({ harness: 'antigravity', prompt: 'Implement this plan:\n' + plan.text })
          const review = await ${'$'}.agent.ask({ harness: 'claude', prompt: 'Review the changes strictly. Answer PASS or list each problem.' })
          return { text: review.text }
        })
        ```

        - It waits until the open chat is free (no task, queued message, terminal command or build),
          switches to `harness` (`claude`, `deepseek`, `antigravity`, `codex`; leave it out for the
          current one), and sends `prompt` there as a normal message, after a note saying the mod sent it.
          The harness stays selected afterwards.
        - It resolves when the task ends: `status` (`completed`, `failed`, `stopped`, or `timeout`),
          `text` (the agent's final reply), `taskId`, `harness` and, on failure, `error`. `timeoutMs`
          defaults to 30 minutes (10 s to 2 hours); on timeout the task keeps running.
        - One mod task runs at a time; a second call waits its turn. Waiting does not count against the
          hook's time. It throws when the chat closes or the harness can't start (for example, not
          installed or not signed in).
        - `model` and `effort` pick the harness's model and effort level first (the ids `/model` and
          `/effort` take); like the harness, they stay selected. `chat: 'new'` starts a new chat in the
          project, and a chat id opens that chat; the answer's `chatId` says where it ran.
        - `${'$'}.agent.stop(taskId)` stops a task the mod started. When the user presses Stop, mods start
          no task or follow-up until the user sends a message, so a runaway chain always stops.
        - `run.end` follow-ups and `${'$'}.prompt.send` share a limit of 3 after each message from the user;
          a mod allowed `agent.chain` (Developer mode) may chain up to 25. Cap your own loops too.

        ### Drawing: dashboards, panels and the row above the message box

        A mod draws by answering `ui.render` with a tree built by `h(type, props, ...children)` (what JSX
        compiles to; the module itself is plain JavaScript, so call `h` directly). The app draws the tree
        with its own components; no mod script runs on screen. Return `null` to draw nothing.

        ```js
        const count = atom('count', 0)
        export function register(on) {
          on('session.start', async (${'$'}, e, next) => { await ${'$'}.ui.open({ id: 'main', title: 'Counter' }); return next(e) })
          on('ui.render', { component: 'Pane', requestId: 'main' }, (${'$'}) =>
            h('Column', { gap: 12 },
              h('Text', { style: 'title2' }, 'Pressed ' + read(${'$'}, count) + ' times'),
              h('Button', { onPress: () => update(${'$'}, count, (n) => n + 1) }, 'Press')))
          on('ui.render', { component: 'AbovePrompt' }, (${'$'}) => h('Text', { style: 'footnote' }, 'Count: ' + read(${'$'}, count)))
        }
        ```

        - Surfaces: a pane (`component: 'Pane'`, `requestId` = its id; needs `ui.pane`) on the mod's
          Dashboard page and, when the user switches it on, in a project's Mods tab; the row above the
          message box (`component: 'AbovePrompt'`; needs `ui.band`, and the user switches it on). Set
          `"dashboard": { "pane": "<id>" }` in `mod.json` so the page opens on that pane.
        - `ui.render` hooks must be synchronous and quick: 250 ms of the mod's own time. Read data that
          comes from `${'$'}` calls in other hooks, keep it in atoms, and only `read` it while drawing. An
          atom read while drawing redraws the surface when `update` changes it (at most 10 times a second).
          After a tap's function finishes, its surface is drawn again too.
        - Functions in props run on taps: `onPress()` for Button and Chip, `onChange(value)` for Toggle,
          TextField (after a short pause) and Select, `onSubmit(value)` for TextField. Each gets
          `(value, ${'$'})` and may be async.
        - Limits: 2,000 elements, 32 levels deep, 100,000 characters of text. A tree the app can't draw
          shows a message on the surface, and the mod log says why.

        | Element | Props (all optional) | Children |
        | --- | --- | --- |
        | `Column`, `List`, `Box` | `gap`, `padding` (dp, up to 48), `align` (`start`, `center`, `end`), `card` (a rounded background) | elements |
        | `Row` | the same, plus `scroll` (scrolls sideways); a child with `grow: true` takes the free width | elements |
        | `Text` | `style` (`largeTitle`, `title`, `title2`, `title3`, `headline`, `body`, `callout`, `subheadline`, `footnote`, `caption`), `color`, `bold`, `mono`, `maxLines`, `align` | text |
        | `Markdown` | | text |
        | `Code` | `format` (`plain` or `diff`), `maxLines` | text |
        | `Button` | `onPress`, `style` (`filled`, `tinted`, `gray`, `plain`, `glass`), `destructive`, `disabled` | text |
        | `Chip` | `onPress`, `color`, `selected` | text |
        | `Toggle` | `label`, `value`, `onChange`, `disabled` | none |
        | `TextField` | `label`, `value`, `placeholder`, `onChange`, `onSubmit`, `multiline`, `disabled` | none |
        | `Select` | `label`, `value`, `options` (text, or `{ value, label }`), `onChange`, `disabled` | none |
        | `Progress` | `value` (0 to 1; leave out to spin), `label`, `color` | none |
        | `Divider`, `Spacer` (`size`) | | none |
        | `Image` | `src` (a PNG/JPEG/WebP file in the mod such as `assets/chart.png`, or a PNG/JPEG data URL up to 512 KB), `height`, `description`, `fit` (`fit` or `fill`) | none |

        Colors: `primary`, `secondary`, `tertiary`, `accent`, `red`, `orange`, `yellow`, `green`, `blue`,
        `purple`, `pink`, `gray`. Every element also takes `key` (keeps list rows steady) and `grow`.

        ### Web panels

        When the app's elements aren't enough (a chart library, a canvas, an editor), a web panel shows an
        HTML page of the mod. The user opens it from the mod's page (Controls), one panel at a time.

        - The page loads from `https://<mod id>.mod.invalid/<entry>` and can load only the mod's own files
          (put scripts, styles and images under `assets/`, and load them with relative paths). It has no
          internet, no device files, no pop-ups, no camera or location, and no file picker. Inline
          `<script>` is blocked: use `<script src="assets/panel.js">`.
        - After the page loads, the app sends it one message whose data is `mh-port` with a port in
          `ports[0]`. Talk to the mod code through that port only:

        ```js
        window.addEventListener('message', (event) => {
          if (event.data !== 'mh-port') return
          const port = event.ports[0]
          port.onmessage = (m) => render(JSON.parse(m.data))
          port.postMessage('ready')
        })
        ```

        - In the mod code, `on('panel.message', (${'$'}, e) => ...)` gets what the page sent (`e.panel`,
          `e.data`, always text), and `${'$'}.ui.post(panelId, data)` sends the page text or JSON. Messages
          are at most 64 KB; a page may send 20 a second, more are dropped. Ask for what the page needs
          when it starts (as `ready` above): messages sent while no panel is open are dropped.
        - If the page's renderer crashes or runs out of memory, the panel says it stopped and offers a
          reload. The app and other mods keep running.
    """

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
