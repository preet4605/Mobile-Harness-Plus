package com.jarves.mh.mods

import com.jarves.mh.model.RiskLevel

/**
 * What a mod may ask for (mod.schema.v2.json). Each capability has a fixed risk. [usable] marks
 * the ones this app version can act on, from simple hooks or mod code (`ModOp`); the rest come in
 * later versions, so they are shown in review but never granted. [developer] capabilities work
 * only while Developer mode is on (Mods screen); turning it off makes their grants inert.
 */
enum class ModCapability(
    val id: String,
    val risk: RiskLevel,
    val summary: String,
    val usable: Boolean = false,
    val developer: Boolean = false,
) {
    APP_INFO("app.info", RiskLevel.SAFE, "Read the app version", usable = true),
    PROJECT_INFO("project.info", RiskLevel.SAFE, "Read the open project's name, harness and plan", usable = true),
    UI_TOAST("ui.toast", RiskLevel.SAFE, "Show short messages at the top of the screen", usable = true),
    STORAGE("storage", RiskLevel.SAFE, "Keep its own saved data", usable = true),
    NOTIFY("notify", RiskLevel.REVIEW, "Post notifications", usable = true),
    CHAT_READ("chat.read", RiskLevel.REVIEW, "Read the project's chats", usable = true),
    CHAT_POST("chat.post", RiskLevel.REVIEW, "Add its own messages to the chat (the agent does not see them)", usable = true),
    PROMPT_DRAFT("prompt.draft", RiskLevel.SAFE, "Fill the message box (never sends)", usable = true),
    PROMPT_SEND("prompt.send", RiskLevel.HIGH, "Send messages to the agent", usable = true),
    EVENTS_SUBSCRIBE("events.subscribe", RiskLevel.REVIEW, "Watch the agent's live log while its panel is open", usable = true),
    FILES_READ("files.read", RiskLevel.REVIEW, "Read project files", usable = true),
    FILES_WRITE("files.write", RiskLevel.HIGH, "Change project files", usable = true),
    GIT_READ("git.read", RiskLevel.REVIEW, "Read git status, diffs and history", usable = true),
    BROWSER_OPEN("browser.open", RiskLevel.REVIEW, "Open web pages", usable = true),
    TERMINAL_RUN("terminal.run", RiskLevel.HIGH, "Run terminal commands in your projects", usable = true, developer = true),
    NET_FETCH("net.fetch", RiskLevel.HIGH, "Connect to the websites it names", usable = true),
    SECRETS("secrets", RiskLevel.REVIEW, "Keep its own secrets", usable = true),
    MEMORY_READ("memory.read", RiskLevel.REVIEW, "Read project memory", usable = true),
    AGENT_ASK("agent.ask", RiskLevel.HIGH, "Start agent tasks on any of your harnesses", usable = true),
    PROMPT_TRANSFORM("prompt.transform", RiskLevel.REVIEW, "Change or stop your messages before they are sent", usable = true),
    UI_STATUS("ui.status", RiskLevel.SAFE, "Show a status line", usable = true),
    UI_BAND("ui.band", RiskLevel.SAFE, "Show a row above the message box", usable = true),
    UI_PANE("ui.pane", RiskLevel.SAFE, "Show its own panels and dashboard", usable = true),
    UI_WEBPANE("ui.webpane", RiskLevel.REVIEW, "Show web panels from its own files", usable = true),
    PROMPT_COMPOSE("prompt.compose", RiskLevel.REVIEW, "Add instructions for the agent", usable = true),
    FILES_GATE("files.gate", RiskLevel.REVIEW, "Block or ask before file renames, deletes and saves in Files", usable = true),
    CHANGES_READ("changes.read", RiskLevel.SAFE, "See the agent's pending file changes", usable = true),
    CHANGES_UNDO("changes.undo", RiskLevel.REVIEW, "Undo the agent's file changes", usable = true),
    TERMINAL_GATE("terminal.gate", RiskLevel.REVIEW, "Block or ask before terminal commands and app builds", usable = true),
    TOOLS_GATE("tools.gate", RiskLevel.REVIEW, "Block or change Claude's tool calls and approval requests", usable = true),
    TOOLS_PROVIDE("tools.provide", RiskLevel.HIGH, "Give the agents new tools they may call", usable = true),
    MODEL_COMPLETE("model.complete", RiskLevel.HIGH, "Call the AI model on your account (daily token limit)", usable = true),
    AGENT_CHAIN("agent.chain", RiskLevel.HIGH, "Keep the agent working for up to 25 follow-ups per message", usable = true, developer = true),
    MODS_CALL("mods.call", RiskLevel.REVIEW, "Use tools other mods give", usable = true),
    GIT_WRITE("git.write", RiskLevel.HIGH, "Commit and switch git branches in your projects", usable = true, developer = true),
    BUILD_RUN("build.run", RiskLevel.HIGH, "Build and install the project's Android app", usable = true, developer = true),
    GUEST_TOOLS("guest.tools", RiskLevel.HIGH, "Run the mod's own programs and MCP servers in the Linux guest", usable = true, developer = true),
    TOOLS_APPROVE("tools.approve", RiskLevel.HIGH, "Approve the agents' requests to run tools inside your projects", usable = true, developer = true),
    PROJECTS("projects", RiskLevel.REVIEW, "List, open and create projects and chats (never delete them)", usable = true),
    CLIPBOARD("clipboard", RiskLevel.REVIEW, "Read the clipboard while the app is open, and copy text to it", usable = true),
    DEVICE_SHARE("device.share", RiskLevel.SAFE, "Share text or project files and open links in other apps (you pick the app)", usable = true),
    STORAGE_PICK("storage.pick", RiskLevel.REVIEW, "Ask you to pick a file, and read the one you pick", usable = true),
    UI_THEME("ui.theme", RiskLevel.REVIEW, "Change the app's accent color", usable = true),
    SETTINGS_READ("settings.read", RiskLevel.REVIEW, "Read app settings: harness, model, effort, theme and agent options", usable = true),
    SETTINGS_WRITE("settings.write", RiskLevel.HIGH, "Change app settings (you can undo each change)", usable = true, developer = true),
    ENGINE_MEMORY("engine.memory", RiskLevel.REVIEW, "Use up to 32 MB of memory for its code (2 mods at most)", usable = true, developer = true),
    RUNTIME("runtime", RiskLevel.HIGH, "Install packages and run servers in the Linux guest", usable = true, developer = true),
    SCHEDULES("schedules", RiskLevel.REVIEW, "Wake the app to run its code, at most every 15 minutes", usable = true, developer = true),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): ModCapability? = byId[id]
    }
}
