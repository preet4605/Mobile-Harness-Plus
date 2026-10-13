package com.jarves.mh.mods

import com.jarves.mh.model.RiskLevel

/**
 * What a mod may ask for (mod.schema.v2.json). Each capability has a fixed risk. [usable] marks
 * the ones this app version can act on; the rest belong to the mod engine, which is not built yet,
 * so they are shown in review but never granted.
 */
enum class ModCapability(
    val id: String,
    val risk: RiskLevel,
    val summary: String,
    val usable: Boolean = false,
) {
    APP_INFO("app.info", RiskLevel.SAFE, "Read the app version"),
    PROJECT_INFO("project.info", RiskLevel.SAFE, "Read the open project's name and harness"),
    UI_TOAST("ui.toast", RiskLevel.SAFE, "Show short messages at the top of the screen", usable = true),
    STORAGE("storage", RiskLevel.SAFE, "Keep its own saved data"),
    NOTIFY("notify", RiskLevel.REVIEW, "Post notifications", usable = true),
    CHAT_READ("chat.read", RiskLevel.REVIEW, "Read the current chat", usable = true),
    CHAT_POST("chat.post", RiskLevel.REVIEW, "Add its own messages to the chat (the agent does not see them)", usable = true),
    PROMPT_DRAFT("prompt.draft", RiskLevel.SAFE, "Fill the message box (never sends)", usable = true),
    PROMPT_SEND("prompt.send", RiskLevel.HIGH, "Send messages to the agent"),
    EVENTS_SUBSCRIBE("events.subscribe", RiskLevel.REVIEW, "Watch what the agent does"),
    FILES_READ("files.read", RiskLevel.REVIEW, "Read project files"),
    FILES_WRITE("files.write", RiskLevel.HIGH, "Add lines to project files", usable = true),
    GIT_READ("git.read", RiskLevel.REVIEW, "Read git status and diffs"),
    BROWSER_OPEN("browser.open", RiskLevel.REVIEW, "Open web pages"),
    TERMINAL_RUN("terminal.run", RiskLevel.HIGH, "Run terminal commands"),
    NET_FETCH("net.fetch", RiskLevel.HIGH, "Connect to the internet"),
    SECRETS("secrets", RiskLevel.REVIEW, "Keep its own secrets"),
    MEMORY_READ("memory.read", RiskLevel.REVIEW, "Read project memory"),
    AGENT_ASK("agent.ask", RiskLevel.HIGH, "Start agent tasks"),
    PROMPT_TRANSFORM("prompt.transform", RiskLevel.REVIEW, "Change your messages before they are sent"),
    UI_STATUS("ui.status", RiskLevel.SAFE, "Show a status line"),
    UI_BAND("ui.band", RiskLevel.SAFE, "Show a row above the message box"),
    UI_PANE("ui.pane", RiskLevel.SAFE, "Show its own panels"),
    UI_WEBPANE("ui.webpane", RiskLevel.REVIEW, "Show web panels"),
    PROMPT_COMPOSE("prompt.compose", RiskLevel.REVIEW, "Add instructions for the agent"),
    FILES_GATE("files.gate", RiskLevel.REVIEW, "Block file renames and deletes"),
    CHANGES_READ("changes.read", RiskLevel.SAFE, "See the agent's pending file changes"),
    CHANGES_UNDO("changes.undo", RiskLevel.REVIEW, "Undo the agent's file changes"),
    TERMINAL_GATE("terminal.gate", RiskLevel.REVIEW, "Block terminal commands"),
    TOOLS_GATE("tools.gate", RiskLevel.REVIEW, "Block agent tool calls"),
    TOOLS_PROVIDE("tools.provide", RiskLevel.HIGH, "Give the agent new tools"),
    MODEL_COMPLETE("model.complete", RiskLevel.HIGH, "Call the AI model on your account"),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): ModCapability? = byId[id]
    }
}
