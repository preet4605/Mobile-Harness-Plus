package com.jarves.mh.mods

import com.jarves.mh.model.AgentKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModManifestParserTest {

    private fun problems(manifest: String, appVersionCode: Int = 5): List<String> = try {
        ModManifestParser.parse(manifest, appVersionCode)
        emptyList()
    } catch (e: ModManifestException) {
        e.problems
    }

    private fun assertRejected(manifest: String, fragment: String) {
        val found = problems(manifest)
        assertTrue("expected a problem containing \"$fragment\", got $found", found.any { fragment in it })
    }

    private fun permission(capability: String, paths: List<String> = emptyList()) = JSONObject()
        .put("capability", capability)
        .put("reason", "test")
        .apply { if (paths.isNotEmpty()) put("scope", JSONObject().put("paths", JSONArray(paths))) }

    @Test
    fun `minimal manifest parses`() {
        val manifest = ModManifestParser.parse(ModTestKit.manifest())
        assertEquals("dev.test.sample", manifest.id)
        assertEquals("Sample", manifest.name)
        assertEquals(2, manifest.apiVersion)
        assertTrue(manifest.permissions.isEmpty())
        assertNull(manifest.hooksModule)
    }

    @Test
    fun `full declarative manifest parses`() {
        val text = ModTestKit.manifest(mapOf("rules/style.md" to "Use tabs.", "skills/code-review/SKILL.md" to "# Review")) {
            put("permissions", JSONArray().put(permission("ui.toast")).put(permission("files.write", listOf("notes/*.md"))))
            put("contributes", JSONObject()
                .put("slashCommands", JSONArray().put(JSONObject()
                    .put("name", "tidy").put("description", "Tidy up").put("template", "Tidy {{args}} in {{settings.style}}")
                    .put("agents", JSONArray().put("claude").put("codex"))))
                .put("promptTemplates", JSONArray().put(JSONObject().put("id", "bug").put("title", "Bug report").put("template", "Steps:")))
                .put("settings", JSONArray().put(JSONObject().put("key", "style").put("title", "Style").put("type", "enum")
                    .put("options", JSONArray().put("short").put("long")).put("default", "short")))
                .put("rules", JSONArray().put(JSONObject().put("id", "style").put("file", "rules/style.md")))
                .put("skills", JSONArray().put(JSONObject().put("id", "code-review").put("dir", "skills/code-review")))
                .put("hooks", JSONArray()
                    .put(JSONObject().put("id", "done").put("on", "task.finished")
                        .put("action", JSONObject().put("type", "ui.toast").put("text", "{{project.name}}: {{task.status}}"))
                        .put("filter", JSONObject().put("status", "completed")))
                    .put(JSONObject().put("id", "journal").put("on", "files.changed")
                        .put("action", JSONObject().put("type", "files.append").put("path", "notes/log.md").put("line", "{{date}} {{files.count}}")))))
        }
        val manifest = ModManifestParser.parse(text)
        assertEquals(setOf(AgentKind.CLAUDE_CODE, AgentKind.CODEX), manifest.slashCommands.single().agents)
        assertEquals("short", manifest.settings.single().default)
        assertEquals(ModEvent.TASK_FINISHED, manifest.hooks[0].on)
        assertEquals("completed", manifest.hooks[0].status)
        assertTrue(manifest.hooks[1].action is ModHookAction.FilesAppend)
        assertEquals(listOf("rules/style.md", "skills/code-review/SKILL.md"), manifest.referencedFiles)
    }

    @Test
    fun `unknown fields and bad identity are rejected`() {
        assertRejected(ModTestKit.manifest { put("trusted", true) }, "Unknown field \"trusted\"")
        assertRejected(ModTestKit.manifest { put("id", "NoDots") }, "\"id\"")
        assertRejected(ModTestKit.manifest { put("version", "1.0") }, "\"version\"")
        assertRejected(ModTestKit.manifest { put("apiVersion", 3) }, "not supported")
        assertRejected(ModTestKit.manifest { put("minAppVersionCode", 99) }, "newer version of the app")
        assertRejected("{not json", "not valid JSON")
    }

    @Test
    fun `commands cannot shadow built-ins and need a template without a module`() {
        assertRejected(ModTestKit.manifest {
            put("contributes", JSONObject().put("slashCommands", JSONArray().put(JSONObject()
                .put("name", "help").put("description", "x").put("template", "x"))))
        }, "built-in")
        assertRejected(ModTestKit.manifest {
            put("contributes", JSONObject().put("slashCommands", JSONArray().put(JSONObject()
                .put("name", "tidy").put("description", "x"))))
        }, "needs a \"template\"")
        assertRejected(ModTestKit.manifest(mapOf("skills/review/SKILL.md" to "# Review")) {
            put("contributes", JSONObject().put("skills", JSONArray().put(JSONObject().put("id", "review").put("dir", "skills/review"))))
        }, "hidden by the built-in /review")
        assertRejected(ModTestKit.manifest {
            put("contributes", JSONObject()
                .put("slashCommands", JSONArray().put(JSONObject().put("name", "tidy").put("description", "x").put("template", "x")))
                .put("promptTemplates", JSONArray().put(JSONObject().put("id", "tidy").put("title", "T").put("template", "x"))))
        }, "/tidy is used by more than one")
    }

    @Test
    fun `templates may only use variables that exist there`() {
        assertRejected(ModTestKit.manifest {
            put("contributes", JSONObject().put("slashCommands", JSONArray().put(JSONObject()
                .put("name", "tidy").put("description", "x").put("template", "{{task.status}}"))))
        }, "{{task.status}}")
        assertRejected(ModTestKit.manifest {
            put("contributes", JSONObject()
                .put("settings", JSONArray().put(JSONObject().put("key", "token").put("title", "Token").put("type", "secret")))
                .put("slashCommands", JSONArray().put(JSONObject()
                    .put("name", "tidy").put("description", "x").put("template", "{{settings.token}}"))))
        }, "{{settings.token}}")
    }

    @Test
    fun `hooks must ask for the capability their action uses`() {
        assertRejected(ModTestKit.manifest {
            put("contributes", JSONObject().put("hooks", JSONArray().put(JSONObject().put("id", "x").put("on", "task.finished")
                .put("action", JSONObject().put("type", "chat.post").put("text", "done")))))
        }, "must ask for that permission")
        assertRejected(ModTestKit.manifest {
            put("permissions", JSONArray().put(permission("chat.post")))
            put("contributes", JSONObject().put("hooks", JSONArray().put(JSONObject().put("id", "x").put("on", "assistant.message.completed")
                .put("action", JSONObject().put("type", "chat.post").put("text", "{{message.text}}")))))
        }, "chat.read")
        assertRejected(ModTestKit.manifest {
            put("permissions", JSONArray().put(permission("ui.toast")))
            put("contributes", JSONObject().put("hooks", JSONArray().put(JSONObject().put("id", "x").put("on", "files.changed")
                .put("filter", JSONObject().put("status", "failed"))
                .put("action", JSONObject().put("type", "ui.toast").put("text", "x")))))
        }, "has no status")
    }

    @Test
    fun `file appends stay inside their scope and never touch agent settings`() {
        assertRejected(ModTestKit.manifest {
            put("permissions", JSONArray().put(permission("files.write")))
        }, "scope.paths")
        assertRejected(ModTestKit.manifest {
            put("permissions", JSONArray().put(permission("files.write", listOf("notes/*.md"))))
            put("contributes", JSONObject().put("hooks", JSONArray().put(JSONObject().put("id", "x").put("on", "files.changed")
                .put("action", JSONObject().put("type", "files.append").put("path", "src/main.kt").put("line", "x")))))
        }, "do not cover")
        assertRejected(ModTestKit.manifest {
            put("permissions", JSONArray().put(permission("files.write", listOf("*.md"))))
            put("contributes", JSONObject().put("hooks", JSONArray().put(JSONObject().put("id", "x").put("on", "files.changed")
                .put("action", JSONObject().put("type", "files.append").put("path", "AGENTS.md").put("line", "x")))))
        }, "can never change")
        assertRejected(ModTestKit.manifest {
            put("permissions", JSONArray().put(permission("files.write", listOf(".git/hooks/*"))))
        }, "not valid")
    }

    @Test
    fun `settings defaults match their type`() {
        fun setting(o: JSONObject) = ModTestKit.manifest { put("contributes", JSONObject().put("settings", JSONArray().put(o))) }
        assertRejected(setting(JSONObject().put("key", "on").put("title", "On").put("type", "bool").put("default", "yes")), "true or false")
        assertRejected(setting(JSONObject().put("key", "token").put("title", "T").put("type", "secret").put("default", "x")), "cannot have a default")
        assertRejected(setting(JSONObject().put("key", "mode").put("title", "M").put("type", "enum")
            .put("options", JSONArray().put("a")).put("default", "b")), "one of its options")
        assertRejected(setting(JSONObject().put("key", "n").put("title", "N").put("type", "number")), "must be a number")
    }

    @Test
    fun `features from later versions are refused clearly`() {
        assertRejected(ModTestKit.manifest {
            put("contributes", JSONObject().put("panels", JSONArray().put(JSONObject().put("id", "p").put("title", "P").put("entry", "p.html"))))
        }, "newer version")
        assertRejected(ModTestKit.manifest {
            put("dashboard", JSONObject().put("pane", "main"))
        }, "needs a hooks module")
    }

    @Test
    fun `guest tools and MCP servers need guest-tools and stay inside the mod`() {
        fun program(name: String, command: JSONArray, env: JSONObject? = null) = JSONObject().put("name", name)
            .put("description", "Lints the project").put("command", command).apply { env?.let { put("env", it) } }
        val good = ModTestKit.manifest(mapOf("tools/lint.js" to "")) {
            put("permissions", JSONArray().put(permission("guest.tools")))
            put("contributes", JSONObject()
                .put("guestTools", JSONArray().put(program("lint", JSONArray().put("node").put("./tools/lint.js"), JSONObject().put("LINT_LEVEL", "strict"))))
                .put("mcpServers", JSONArray().put(program("docs", JSONArray().put("python3").put("-m").put("docs_server")))))
        }
        val manifest = ModManifestParser.parse(good)
        assertEquals(listOf("node", "./tools/lint.js"), manifest.guestTools.single().command)
        assertEquals(mapOf("LINT_LEVEL" to "strict"), manifest.guestTools.single().env)
        assertEquals("docs", manifest.mcpServers.single().name)

        assertRejected(ModTestKit.manifest {
            put("contributes", JSONObject().put("guestTools", JSONArray().put(program("lint", JSONArray().put("node")))))
        }, "must ask for guest.tools")
        fun withTool(tool: JSONObject) = ModTestKit.manifest {
            put("permissions", JSONArray().put(permission("guest.tools")))
            put("contributes", JSONObject().put("guestTools", JSONArray().put(tool)))
        }
        assertRejected(withTool(program("Lint", JSONArray().put("node"))), "lowercase letters")
        assertRejected(withTool(program("lint", JSONArray())), "list of 1 to 32 strings")
        assertRejected(withTool(program("lint", JSONArray().put("/usr/bin/node"))), "not an absolute path")
        assertRejected(withTool(program("lint", JSONArray().put("node").put("./../escape.js"))), "must be a file inside the mod")
        assertRejected(withTool(program("lint", JSONArray().put("node"), JSONObject().put("LD_PRELOAD", "x"))), "not a name a mod may set")
    }

    @Test
    fun `safe relative paths`() {
        assertTrue(ModManifestParser.isSafeRelativePath("rules/a.md"))
        listOf("", "/abs", "../up", "a/../b", "a//b", "a\\b", "a/./b", "nul\u0000").forEach {
            assertFalse("\"$it\" must be unsafe", ModManifestParser.isSafeRelativePath(it))
        }
    }

    @Test
    fun `globs and the deny list`() {
        assertTrue(ModPaths.matchesGlob("notes/*.md", "notes/log.md"))
        assertFalse(ModPaths.matchesGlob("notes/*.md", "notes/sub/log.md"))
        assertTrue(ModPaths.matchesGlob("notes/**", "notes/sub/log.md"))
        assertTrue(ModPaths.matchesGlob("**/*.md", "log.md"))
        listOf(".claude/settings.json", "CLAUDE.md", "AGENTS.md", "GEMINI.md", ".codex/config.toml", ".git/config",
            ".github/workflows/ci.yml", "src/.gitattributes", ".mcp.json", ".mh/mods/x/mod.json").forEach {
            if (ModPaths.alwaysDeniedReason(it) == null) fail("$it must be denied")
        }
        assertNull(ModPaths.alwaysDeniedReason("notes/log.md"))
    }
}
