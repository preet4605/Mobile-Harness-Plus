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
        appendLine("- **Mod code**: one JavaScript file with hooks for app events, its own saved data, timers,")
        appendLine("  a status line, and commands that answer in code. See \"Mod code\" below. Use it only")
        appendLine("  when the simple parts above can't do the job.")
        appendLine("- **Dashboards and panels** drawn by mod code with the app's own text, buttons, switches, lists,")
        appendLine("  progress bars and images: on the mod's page, in a project's Mods tab, and as a row above the message box.")
        appendLine("  See \"Drawing\" below.")
        appendLine()
        appendLine("Not supported yet, so never add them: `types`, `contributes.panels` (web panels),")
        appendLine("`contributes.guestTools`, `contributes.mcpServers`.")
        appendLine()
        appendLine("## Files")
        appendLine()
        appendLine("Only these files become the mod. Everything else in this folder, this guide included, stays out.")
        appendLine()
        appendLine("- `mod.json` (required): the manifest described below.")
        appendLine("- The rule files, skill folders and hooks module `mod.json` names, for example `rules/style.md`,")
        appendLine("  `skills/review-pr/SKILL.md` and `hooks/register.js`.")
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
        appendLine(CODE_GUIDE.trimIndent())
        appendLine()
        appendLine("## Checking your work")
        appendLine()
        appendLine("- Run `python3 -m json.tool mod.json > /dev/null` to catch JSON mistakes.")
        appendLine("- Check every file `mod.json` names exists, and every `{{variable}}` is allowed where it is used.")
        appendLine("- For mod code, if `node` is installed: `cp hooks/register.js /tmp/check.mjs && node --check /tmp/check.mjs`.")
        appendLine("  After installing, the mod's page shows whether its code runs (Controls) and its log (Activity).")
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
        - `next(e)` runs this mod's later hooks for the event and returns their answer. These events only
          report what happened, so a hook may return without calling `next`.
        - `on(...).catch((${'$'}, e, next, error) => ...)` answers when that hook throws. Without it, a hook
          that throws is skipped.
        - Every `${'$'}` call returns a promise, except `${'$'}.log` and `${'$'}.clock.now/after/every/cancel`.
          `console.log` writes to the mod log too.
        - `atom(name, initial)`, `read(${'$'}, atom)` and `update(${'$'}, atom, fn)` keep values in memory; they
          are lost when the mod reloads. Use `${'$'}.store` for anything to keep.
        - Limits: each hook gets 10 s of its own time (1 s for `app.*` events); time spent waiting on
          `${'$'}` calls does not count. Code that runs 3 s without awaiting is stopped. Memory is capped at
          16 MB, and going over restarts every mod. Five problems (a throw, a timeout, a stop) in 10 minutes
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
        | `tool.call` | The agent starts a tool | `tool`, `detail`, `sessionId` |
        | `tool.result` | A tool finished | `tool`, `summary`, `sessionId` |
        | `tool.approval` | The agent asks the user to approve a tool | `tool`, `risk` |
        | `files.changed` | The agent changed files | `paths`, `count` |
        | `usage.update` | Token use changed (at most once a second) | `promptTokens`, `completionTokens`, `cachedTokens`, `contextLimit`, `costUsd` |
        | `terminal.end` | A terminal command finished | `command`, `exitCode` |
        | `build.end` | An Android build ended | `status`, `detail` |
        | `app.foreground`, `app.background` | The app is shown or hidden | |
        | `command.run` | The user ran one of the mod's commands | `name`, `args` |
        | `plan.update` | The open chat's task plan changed | `taskId`, `plan` (`title`, `status`, `current`, `steps`; only with `project.info`) |
        | `run.log` | The running task's live output, batched once a second, only while one of the mod's surfaces is showing | `lines` (`kind`: `reply`, `tool` or `log`; `text`; `time`), `dropped`. Needs `events.subscribe` |
        | `ui.render` | The app needs a surface drawn (see "Drawing") | `component`, `requestId`, `title`, `surface` |

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
        | `${'$'}.ui.open({ id, title })`, `${'$'}.ui.close(id)` | `ui.pane` | Adds a panel (8 at most; `id` is lowercase letters, digits, dashes) or removes one; `open` returns `{ isPlaced }`, whether it is on screen now |
        | `${'$'}.ui.redraw(id?)` | none | Draws the mod's shown surfaces again (or just pane `id`) |
        | `${'$'}.ui.resolve(e)` | none | The element names, so `const { Column, Text } = ${'$'}.ui.resolve(e)` works as in other mod systems |

        A `command.run` hook answers with `{ text }`, shown in the chat as the mod's reply (the agent
        never sees it), or `{ prompt }`, sent to the agent as if the user typed it. Declare commands in
        `contributes.slashCommands` without a `template`, or add them with `${'$'}.command.register`.
        A `${'$'}` call without its permission throws an error that names the permission.

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
