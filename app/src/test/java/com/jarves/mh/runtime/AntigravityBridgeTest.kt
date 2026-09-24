package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityBridgeTest {
    @Test
    fun `init event exposes conversation id before task completion`() {
        val event = AntigravityEventParser.parse(
            """{"event":"init","conversation_id":"conversation-early","init":{"model":"gemini"}}""",
        )
        assertEquals(AntigravityParsedEvent.Initialized("conversation-early"), event)
    }

    @Test
    fun `parses streamed response deltas`() {
        val parsed = AntigravityEventParser.parse(
            """{"event":"step_update","step_update":{"state":"ACTIVE","step_type":"agent_response","text_delta":"hello"}}""",
        )
        assertTrue(parsed is AntigravityParsedEvent.Text)
        assertEquals("hello", (parsed as AntigravityParsedEvent.Text).value)
    }

    @Test
    fun `parses successful result and conversation id`() {
        val parsed = AntigravityEventParser.parse(
            """{"event":"result","result":{"conversation_id":"conversation-1","status":"SUCCESS","response":"done"}}""",
        )
        assertTrue(parsed is AntigravityParsedEvent.Result)
        val result = parsed as AntigravityParsedEvent.Result
        assertEquals("conversation-1", result.conversationId)
        assertEquals("SUCCESS", result.status)
        assertEquals("done", result.response)
        assertNull(result.error)
    }

    @Test
    fun `shows the command from official tool info`() {
        val parsed = AntigravityEventParser.parse(
            """{"event":"step_update","step_update":{"state":"ACTIVE","step_type":"tool","tool_name":"run_command","tool_info":{"name":"run_command","parameters":{"CommandLine":"python3 hello.py"}}}}""",
        )
        assertEquals(
            AntigravityParsedEvent.ToolStarted("Bash", "python3 hello.py"),
            parsed,
        )
    }

    @Test
    fun `shows the target path from official write tool info`() {
        val parsed = AntigravityEventParser.parse(
            """{"event":"step_update","step_update":{"state":"DONE","step_type":"tool","tool_name":"write_to_file","tool_info":{"name":"write_to_file","parameters":{"TargetFile":"/workspace/app/main.py"}}}}""",
        )
        assertEquals(
            AntigravityParsedEvent.ToolCompleted("Write", "/workspace/app/main.py"),
            parsed,
        )
    }

    @Test
    fun `ignores unknown and malformed events`() {
        assertNull(AntigravityEventParser.parse("not json"))
        assertNull(AntigravityEventParser.parse("""{"event":"future_event","payload":{}}"""))
    }

    @Test
    fun `extracts a wrapped Google PKCE url`() {
        val output = """
            Your browser should open automatically. If not:
            https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=client.apps.googleusercontent.com&code_chall
            enge=challenge&code_challenge_method=S256&state=fresh-state
            If you aren't automatically redirected, paste the authorization code below:
        """.trimIndent()
        val url = extractGoogleOAuthUrl(output)
        assertTrue(url!!.startsWith("https://accounts.google.com/"))
        assertTrue("client_id=client.apps.googleusercontent.com" in url)
        assertTrue("code_challenge=challenge" in url)
        assertTrue("state=fresh-state" in url)
    }

    @Test
    fun `oauth url excludes terminal labels appended after state`() {
        val output = """
            https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=client.apps.googleusercontent.com&code_chall
            enge=challenge&code_challenge_method=S256&prompt=consent&state=eqPPegReyKP37OxuRsM0EQ
            ──────────────────────────────────────────────────
            Copy and paste the URL or click on the link below:
            → Click here to authenticate
            After authenticating, copy the code displayed in the browser and paste it below:
            authorization code... shift+up/down Navigate
        """.trimIndent()

        assertEquals(
            "https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=client.apps.googleusercontent.com&code_challenge=challenge&code_challenge_method=S256&prompt=consent&state=eqPPegReyKP37OxuRsM0EQ",
            extractGoogleOAuthUrl(output),
        )
    }

    @Test
    fun `headless command uses exact model configuration without conflicting effort`() {
        val command = antigravityCommand("gemini-model", "high", "conversation-1")
        assertTrue(command.containsAll(listOf(
            "--input-format", "stream-json",
            "--output-format", "stream-json",
            "--print-timeout", "60m",
            "--dangerously-skip-permissions",
            "--model", "gemini-model",
            "--conversation", "conversation-1",
        )))
        assertTrue("--effort" !in command)
        assertTrue("--new-project" !in command)
    }

    @Test
    fun `new headless conversation creates an official project`() {
        val command = antigravityCommand("", "high", null)
        assertTrue("--new-project" in command)
        assertTrue("--conversation" !in command)
        assertTrue(command.containsAll(listOf("--effort", "high")))
    }

    @Test
    fun `workspace prompt keeps generated files in the mounted project`() {
        val prompt = antigravityWorkspacePrompt("bold-kalam", "Create hello.py")
        assertTrue("/workspace/bold-kalam" in prompt)
        assertTrue("Do not create project output" in prompt)
        assertTrue(prompt.endsWith("Create hello.py"))
    }

    @Test
    fun `quota error detection identifies rate limits and exhausted resource errors`() {
        assertTrue(isQuotaError("Resource has been exhausted (e.g. check quota)"))
        assertTrue(isQuotaError("HTTP 429: Too Many Requests"))
        assertTrue(isQuotaError("You are out of credits for this billing period"))
        assertTrue(isQuotaError("Rate limit exceeded for model gemini-pro"))
        org.junit.Assert.assertFalse(isQuotaError("Syntax error in script: invalid syntax"))
    }

    @Test
    fun `auth error detection identifies missing or invalid credentials`() {
        assertTrue(isAuthError("Authentication required. Please run agy login"))
        assertTrue(isAuthError("OAuth token expired or invalid"))
        assertTrue(isAuthError("Not signed in to Google account"))
        org.junit.Assert.assertFalse(isAuthError("FileNotFoundException: file does not exist"))
    }

    @Test
    fun `buildFailoverPrompt preserves recent conversation turns`() {
        val history = listOf(
            com.jarves.mh.model.ChatMessage(fromUser = true, text = "Add button"),
            com.jarves.mh.model.ChatMessage(fromUser = false, text = "Button added"),
            com.jarves.mh.model.ChatMessage(fromUser = true, text = "Style it blue"),
        )
        val prompt = buildFailoverPrompt("test-project", "Make it darker blue", history)
        assertTrue("/workspace/test-project" in prompt)
        assertTrue("Previous conversation context:" in prompt)
        assertTrue("User: Add button" in prompt)
        assertTrue("Assistant: Button added" in prompt)
        assertTrue("User: Style it blue" in prompt)
        assertTrue("Current task:\nMake it darker blue" in prompt)
    }

    @Test
    fun `invoke_subagent parses multiple subagents from Subagents array`() {
        val line = """
            {"event":"step_update","step_update":{"state":"ACTIVE","step_type":"tool","tool_name":"invoke_subagent","tool_info":{"name":"invoke_subagent","parameters":{"Subagents":[{"Role":"Codebase Researcher","TypeName":"research","Prompt":"Explore architecture","conversationId":"sub-1"},{"Role":"Test Auditor","TypeName":"test","Prompt":"Audit tests","conversationId":"sub-2"}]}}}}
        """.trimIndent()

        val events = AntigravityEventParser.parseEvents(line)
        val subagentEvents = events.filterIsInstance<AntigravityParsedEvent.Subagent>()
        assertEquals(2, subagentEvents.size)

        assertEquals("sub-1", subagentEvents[0].subagent.conversationId)
        assertEquals("Codebase Researcher", subagentEvents[0].subagent.role)
        assertEquals(com.jarves.mh.model.SubagentState.RUNNING, subagentEvents[0].subagent.state)

        assertEquals("sub-2", subagentEvents[1].subagent.conversationId)
        assertEquals("Test Auditor", subagentEvents[1].subagent.role)
        assertEquals(com.jarves.mh.model.SubagentState.RUNNING, subagentEvents[1].subagent.state)
    }

    @Test
    fun `invoke_subagent DONE state sets subagent state to DONE`() {
        val line = """
            {"event":"step_update","step_update":{"state":"DONE","step_type":"tool","tool_name":"invoke_subagent","tool_info":{"name":"invoke_subagent","parameters":{"Role":"Planner","TypeName":"general","Prompt":"Create plan","conversationId":"sub-done-1"}}}}
        """.trimIndent()

        val events = AntigravityEventParser.parseEvents(line)
        val subagent = events.filterIsInstance<AntigravityParsedEvent.Subagent>().firstOrNull()
        org.junit.Assert.assertNotNull(subagent)
        assertEquals(com.jarves.mh.model.SubagentState.DONE, subagent!!.subagent.state)
        assertEquals("sub-done-1", subagent.subagent.conversationId)
    }

    @Test
    fun `manage_subagents kill parses termination for specific ids`() {
        val line = """
            {"event":"step_update","step_update":{"state":"ACTIVE","step_type":"tool","tool_name":"manage_subagents","tool_info":{"name":"manage_subagents","parameters":{"Action":"kill","ConversationIds":["sub-id-1","sub-id-2"]}}}}
        """.trimIndent()

        val events = AntigravityEventParser.parseEvents(line)
        val subagents = events.filterIsInstance<AntigravityParsedEvent.Subagent>()
        assertEquals(2, subagents.size)
        assertEquals("sub-id-1", subagents[0].subagent.conversationId)
        assertEquals(com.jarves.mh.model.SubagentState.TERMINATED, subagents[0].subagent.state)
        assertEquals("sub-id-2", subagents[1].subagent.conversationId)
        assertEquals(com.jarves.mh.model.SubagentState.TERMINATED, subagents[1].subagent.state)
    }

    @Test
    fun `manage_subagents kill_all parses wildcard termination`() {
        val line = """
            {"event":"step_update","step_update":{"state":"ACTIVE","step_type":"tool","tool_name":"manage_subagents","tool_info":{"name":"manage_subagents","parameters":{"Action":"kill_all"}}}}
        """.trimIndent()

        val events = AntigravityEventParser.parseEvents(line)
        val subagent = events.filterIsInstance<AntigravityParsedEvent.Subagent>().firstOrNull()
        org.junit.Assert.assertNotNull(subagent)
        assertEquals("*", subagent!!.subagent.conversationId)
        assertEquals(com.jarves.mh.model.SubagentState.TERMINATED, subagent.subagent.state)
    }

    @Test
    fun `manage_task kill parses task termination`() {
        val line = """
            {"event":"step_update","step_update":{"state":"ACTIVE","step_type":"tool","tool_name":"manage_task","tool_info":{"name":"manage_task","parameters":{"Action":"kill","TaskId":"task-99"}}}}
        """.trimIndent()

        val events = AntigravityEventParser.parseEvents(line)
        val task = events.filterIsInstance<AntigravityParsedEvent.Task>().firstOrNull()
        org.junit.Assert.assertNotNull(task)
        assertEquals("task-99", task!!.task.taskId)
        assertEquals(com.jarves.mh.model.BackgroundTaskStatus.TERMINATED, task.task.status)
    }

    @Test
    fun `schedule event parses timer with duration`() {
        val line = """
            {"event":"step_update","step_update":{"state":"DONE","step_type":"tool","tool_name":"schedule","tool_info":{"name":"schedule","parameters":{"DurationSeconds":120,"Prompt":"Check build"}}}}
        """.trimIndent()

        val events = AntigravityEventParser.parseEvents(line)
        val timer = events.filterIsInstance<AntigravityParsedEvent.Timer>().firstOrNull()
        org.junit.Assert.assertNotNull(timer)
        assertEquals(120, timer!!.timer.totalSeconds)
        assertEquals("Check build", timer.timer.prompt)
    }

    @Test
    fun `write_to_file with markdown file parses artifact discovery`() {
        val line = """
            {"event":"step_update","step_update":{"state":"DONE","step_type":"tool","tool_name":"write_to_file","tool_info":{"name":"write_to_file","parameters":{"TargetFile":"/workspace/clever-kalam/docs/TEST_PLAN.md","ArtifactMetadata":{"Summary":"Detailed test plan document","UserFacing":true}}}}}
        """.trimIndent()

        val events = AntigravityEventParser.parseEvents(line)
        val artifact = events.filterIsInstance<AntigravityParsedEvent.Artifact>().firstOrNull()
        org.junit.Assert.assertNotNull(artifact)
        assertEquals("/workspace/clever-kalam/docs/TEST_PLAN.md", artifact!!.artifact.filePath)
        assertEquals("Detailed test plan document", artifact.artifact.summary)
        assertTrue(artifact.artifact.isUserFacing)
    }

    @Test
    fun `result event parses token usage metrics`() {
        val line = """
            {"event":"result","result":{"status":"SUCCESS","usage":{"input_tokens":1500,"output_tokens":350}}}
        """.trimIndent()

        val events = AntigravityEventParser.parseEvents(line)
        val usage = events.filterIsInstance<AntigravityParsedEvent.TokenUsage>().firstOrNull()
        assertEquals(1500, usage!!.metrics.promptTokens)
        assertEquals(350, usage.metrics.completionTokens)
    }
}
