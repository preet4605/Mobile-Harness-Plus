package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.SlashCommand
import com.jarves.mh.model.SlashCommandCategory
import java.util.Locale

object SlashCommandEngine {

    val ALL_SLASH_COMMANDS: List<SlashCommand> = listOf(
        SlashCommand(
            name = "help",
            description = "Show commands cheat sheet, skills guide, and shortcuts",
            category = SlashCommandCategory.GENERAL,
            isLocalOnly = true,
        ),
        SlashCommand(
            name = "clear",
            description = "Clear current conversation history (keeps project files)",
            category = SlashCommandCategory.GENERAL,
            isLocalOnly = true,
        ),
        SlashCommand(
            name = "compact",
            description = "Summarize earlier turns to reclaim context window space",
            category = SlashCommandCategory.GENERAL,
            isLocalOnly = false,
        ),
        SlashCommand(
            name = "cost",
            description = "Display session token metrics, cache hits, and estimated cost",
            category = SlashCommandCategory.DIAGNOSTICS,
            isLocalOnly = true,
        ),
        SlashCommand(
            name = "status",
            description = "Display current runtime status, active subagents, and background tasks",
            category = SlashCommandCategory.DIAGNOSTICS,
            isLocalOnly = true,
        ),
        SlashCommand(
            name = "doctor",
            description = "Run full system diagnostics (PRoot, ARM64 ABI, storage, git, network)",
            category = SlashCommandCategory.DIAGNOSTICS,
            isLocalOnly = true,
        ),
        SlashCommand(
            name = "model",
            description = "Display active model, quota, and switch reasoning effort",
            category = SlashCommandCategory.CONFIG,
            isLocalOnly = true,
        ),
        SlashCommand(
            name = "skills",
            description = "View and manage active skills and workspace project rules",
            category = SlashCommandCategory.CONFIG,
            isLocalOnly = true,
        ),
        SlashCommand(
            name = "memory",
            description = "View, add, or clear persistent context memory facts",
            category = SlashCommandCategory.CONFIG,
            isLocalOnly = true,
            parameterHint = "[add <key> <val> | clear]",
        ),
        SlashCommand(
            name = "checkpoint",
            description = "Create an instant snapshot checkpoint of project files",
            category = SlashCommandCategory.VCS,
            isLocalOnly = true,
            parameterHint = "[name]",
        ),
        SlashCommand(
            name = "rollback",
            description = "Revert project files to the last saved checkpoint",
            category = SlashCommandCategory.VCS,
            isLocalOnly = true,
        ),
        SlashCommand(
            name = "plan",
            description = "Produce a structured step-by-step plan before writing code",
            category = SlashCommandCategory.AGENT_WORKFLOW,
            parameterHint = "<task description>",
        ),
        SlashCommand(
            name = "goal",
            description = "Autonomous execution mode running tasks to verified completion",
            category = SlashCommandCategory.AGENT_WORKFLOW,
            parameterHint = "<goal description>",
            supportedAgents = setOf(AgentKind.ANTIGRAVITY, AgentKind.CLAUDE_CODE),
        ),
        SlashCommand(
            name = "review",
            description = "Automated code review of recent changes or uncommitted diff",
            category = SlashCommandCategory.AGENT_WORKFLOW,
            parameterHint = "[file or path]",
        ),
        SlashCommand(
            name = "init",
            description = "Analyze repository and generate/update project rules (CLAUDE.md / GEMINI.md)",
            category = SlashCommandCategory.AGENT_WORKFLOW,
        ),
        SlashCommand(
            name = "grill-me",
            description = "Interactive interview to clarify architecture, trade-offs, and requirements",
            category = SlashCommandCategory.AGENT_WORKFLOW,
            parameterHint = "[topic or feature]",
        ),
        SlashCommand(
            name = "boost",
            description = "Activate multi-perspective deep reasoning and rigorous verification",
            category = SlashCommandCategory.AGENT_WORKFLOW,
            parameterHint = "<task description>",
        ),
        SlashCommand(
            name = "learn",
            description = "Extract learnings & bug fixes from this session into persistent rules in AGENTS.md",
            category = SlashCommandCategory.AGENT_WORKFLOW,
        ),
        SlashCommand(
            name = "browser",
            description = "Web research and documentation exploration turn",
            category = SlashCommandCategory.AGENT_WORKFLOW,
            parameterHint = "<url or search query>",
            supportedAgents = setOf(AgentKind.ANTIGRAVITY),
        ),
    )

    /**
     * Splits a raw argument string into whitespace-delimited tokens while respecting quoted substrings.
     */
    fun tokenizeArgs(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var quoteChar = ' '
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (inQuotes) {
                if (c == quoteChar) {
                    inQuotes = false
                } else if (c == '\\' && i + 1 < raw.length && (raw[i + 1] == quoteChar || raw[i + 1] == '\\')) {
                    current.append(raw[i + 1])
                    i++
                } else {
                    current.append(c)
                }
            } else {
                if (c == '"' || c == '\'') {
                    inQuotes = true
                    quoteChar = c
                } else if (c.isWhitespace()) {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                } else {
                    current.append(c)
                }
            }
            i++
        }
        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }
        return tokens
    }

    /**
     * Filters available slash commands matching the user's query and active agent.
     */
    fun filterCommands(rawQuery: String, agent: AgentKind): List<SlashCommand> {
        val query = rawQuery.trim().removePrefix("/").trim().lowercase(Locale.ROOT)
        return ALL_SLASH_COMMANDS.filter { cmd ->
            agent in cmd.supportedAgents && (
                query.isBlank() ||
                cmd.name.lowercase(Locale.ROOT).contains(query) ||
                cmd.description.lowercase(Locale.ROOT).contains(query) ||
                cmd.category.title.lowercase(Locale.ROOT).contains(query)
            )
        }.sortedWith(
            compareBy(
                { !it.name.lowercase(Locale.ROOT).startsWith(query) },
                { it.category.ordinal },
                { it.name },
            )
        )
    }

    /**
     * Checks if the text starts with a slash command and extracts (command, arguments).
     */
    fun parseCommand(input: String): Pair<SlashCommand, String>? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) return null
        val parts = trimmed.substring(1).split(Regex("\\s+"), limit = 2)
        val cmdName = parts[0].lowercase(Locale.ROOT)
        val args = parts.getOrNull(1).orEmpty().trim()
        val command = ALL_SLASH_COMMANDS.firstOrNull { it.name.equals(cmdName, ignoreCase = true) } ?: return null
        return command to args
    }

    /**
     * Constructs a specialized prompt for agent workflow commands.
     */
    fun buildPromptForCommand(command: SlashCommand, args: String, agent: AgentKind): String {
        return when (command.name.lowercase(Locale.ROOT)) {
            "plan" -> buildString {
                appendLine("[WORKFLOW: IMPLEMENTATION PLAN]")
                appendLine("Please create a comprehensive, step-by-step implementation plan for the following task before modifying any files:")
                if (args.isNotBlank()) {
                    appendLine()
                    appendLine("Task:")
                    appendLine(args)
                }
                appendLine()
                appendLine("Plan structure requirements:")
                appendLine("1. Architecture & Context Analysis (files to inspect/change)")
                appendLine("2. Step-by-step Implementation Order")
                appendLine("3. Edge Cases & Potential Risks")
                appendLine("4. Verification & Testing Steps")
                appendLine()
                appendLine("Do NOT make code changes yet. Present the plan clearly and ask for confirmation to proceed.")
            }

            "goal" -> buildString {
                appendLine("[WORKFLOW: AUTONOMOUS GOAL EXECUTION]")
                val targetGoal = args.ifBlank { "Achieve and verify the primary task in this workspace" }
                appendLine("Autonomous Goal: $targetGoal")
                appendLine()
                appendLine("Execute this goal thoroughly from start to finish. Check command exits, inspect generated files, run verification tests, and do not stop until the goal is fully achieved and verified.")
            }

            "review" -> buildString {
                appendLine("[WORKFLOW: CODE REVIEW]")
                if (args.isNotBlank()) {
                    appendLine("Target: $args")
                }
                appendLine("Perform a comprehensive code review on the latest workspace changes and git diff.")
                appendLine("Focus on:")
                appendLine("1. Correctness, bugs, and nullability issues")
                appendLine("2. Architecture and code style consistency")
                appendLine("3. Performance, memory usage, and security considerations")
                appendLine("4. Test coverage")
                appendLine("Provide actionable recommendations with exact file and line references.")
            }

            "init" -> buildString {
                appendLine("[WORKFLOW: REPOSITORY RULES INITIALIZATION]")
                appendLine("Analyze the repository structure, programming languages, build configurations, and project layout.")
                appendLine("Generate or update project guidelines in GEMINI.md or CLAUDE.md:")
                appendLine("1. Project Summary & Architecture overview")
                appendLine("2. Development workflow & build commands")
                appendLine("3. Code style, conventions, and architectural rules")
                appendLine("Write the file directly to the project root once drafted.")
            }

            "grill-me" -> buildString {
                appendLine("[WORKFLOW: INTERACTIVE ARCHITECTURAL INTERVIEW]")
                if (args.isNotBlank()) {
                    appendLine("Topic: $args")
                }
                appendLine("Interview me step-by-step to align on architecture, edge cases, user experience, and design trade-offs.")
                appendLine("Ask 2-3 specific, high-value questions at a time before formulating a solution.")
            }

            "boost" -> buildString {
                appendLine("[WORKFLOW: DEEP REASONING BOOST]")
                val targetTask = args.ifBlank { "Analyze and implement the pending task with deep reasoning and verification" }
                appendLine("Task: $targetTask")
                appendLine()
                appendLine("Apply rigorous multi-perspective analysis, explore edge cases, verify assumptions against the codebase, and validate results carefully.")
            }

            "learn" -> buildString {
                appendLine("[WORKFLOW: RULE EXTRACTION & LEARNING]")
                appendLine("Reflect on the recent turns, user feedback, and bug fixes in this conversation.")
                appendLine("Extract key lessons and coding guidelines, and persist them into AGENTS.md or GEMINI.md so future turns follow these conventions.")
            }

            "browser" -> buildString {
                appendLine("[WORKFLOW: WEB RESEARCH]")
                val targetQuery = args.ifBlank { "documentation and best practices for the active project" }
                appendLine("Research Query / URL: $targetQuery")
                appendLine("Explore documentation, search for official references, and summarize findings with citations.")
            }

            "compact" -> buildString {
                appendLine("Please summarize the key decisions, context, and modified files from our conversation history so far to compact our context window, keeping all necessary context for future turns.")
            }

            else -> if (args.isNotBlank()) "${command.name}: $args" else command.name
        }
    }
}
