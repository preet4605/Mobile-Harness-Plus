package com.jarves.mh.runtime

import org.json.JSONObject

/** Typed user intent, persisted separately from the rules/Brain/attachment prompt envelope. */
internal data class CodexNativeControl(
    val kind: String,
    val text: String = "",
    val target: String = "",
    val tokenBudget: Long? = null,
) {
    val localPlan: Boolean get() = kind in setOf("planOn", "planOff", "planStatus")
    val goalControl: Boolean get() = kind in setOf("goalStatus", "goalPause", "goalClear")
    fun encode(): String = JSONObject().put("kind", kind).put("text", text).put("target", target)
        .put("tokenBudget", tokenBudget ?: JSONObject.NULL).toString()

    companion object {
        const val GOAL_HELP = "/goal <objective> | --budget <tokens> <objective> | status | pause | resume | clear"
        const val PLAN_HELP = "/plan [on | off | status | <task>]"
        const val REVIEW_HELP = "/review [--base <branch> | --commit <sha> | --custom <instructions>]"
        fun parse(command: String, args: String): CodexNativeControl {
            val raw = args.trim()
            return when (command) {
                "compact" -> { require(raw.isEmpty()) { "Usage: /compact" }; CodexNativeControl("compact") }
                "plan" -> when (raw.lowercase()) {
                    "", "on" -> CodexNativeControl("planOn")
                    "off" -> CodexNativeControl("planOff")
                    "status" -> CodexNativeControl("planStatus")
                    else -> CodexNativeControl("planTurn", raw)
                }
                "goal" -> when (raw.lowercase()) {
                    "", "status" -> CodexNativeControl("goalStatus")
                    "pause" -> CodexNativeControl("goalPause")
                    "resume" -> CodexNativeControl("goalResume")
                    "clear" -> CodexNativeControl("goalClear")
                    else -> {
                        var objective = raw
                        var budget: Long? = null
                        if (raw.startsWith("--budget")) {
                            val pieces = raw.split(Regex("\\s+"), limit = 3)
                            require(pieces.size == 3 && pieces[0] == "--budget") { "Usage: $GOAL_HELP" }
                            budget = pieces[1].toLongOrNull()
                            require(budget != null && budget > 0L) { "Token budget must be a positive whole number. Usage: $GOAL_HELP" }
                            objective = pieces[2].trim()
                        }
                        require(objective.isNotBlank() && objective.length <= 4000 && !objective.startsWith("--")) {
                            "A goal needs an objective of 1–4000 characters. Usage: $GOAL_HELP"
                        }
                        CodexNativeControl("goalSet", objective, tokenBudget = budget)
                    }
                }
                "review" -> {
                    if (raw.isEmpty()) CodexNativeControl("review", target = "uncommittedChanges")
                    else {
                        val option = raw.substringBefore(' ')
                        val value = raw.substringAfter(' ', "").trim()
                        require(value.isNotEmpty()) { "Usage: $REVIEW_HELP" }
                        when (option) {
                            "--base" -> { require(value.length <= 256 && !value.startsWith('-') && !value.any { it.isWhitespace() || it.code < 32 }) { "Specify one branch. Usage: $REVIEW_HELP" }; CodexNativeControl("review", value, "baseBranch") }
                            "--commit" -> { require(Regex("[0-9a-fA-F]{7,64}").matches(value)) { "Specify a commit SHA. Usage: $REVIEW_HELP" }; CodexNativeControl("review", value, "commit") }
                            "--custom" -> CodexNativeControl("review", value, "custom")
                            else -> error("Native review supports changes, a base branch, a commit or custom instructions. Usage: $REVIEW_HELP")
                        }
                    }
                }
                else -> error("Unsupported native command: $command")
            }
        }

        fun decode(raw: String): CodexNativeControl = try {
            val obj = JSONObject(raw)
            val kind = obj.getString("kind")
            val text = obj.optString("text")
            val control = when (kind) {
                "compact" -> parse("compact", text)
                "goalSet" -> parse("goal", if (obj.isNull("tokenBudget")) text else "--budget ${obj.getLong("tokenBudget")} $text")
                "goalStatus", "goalPause", "goalResume", "goalClear" -> parse("goal", kind.removePrefix("goal").lowercase())
                "planOn", "planOff", "planStatus" -> parse("plan", kind.removePrefix("plan").lowercase())
                "planTurn" -> parse("plan", text)
                "review" -> parse("review", when (obj.getString("target")) {
                    "uncommittedChanges" -> ""
                    "baseBranch" -> "--base $text"
                    "commit" -> "--commit $text"
                    "custom" -> "--custom $text"
                    else -> error("Invalid native review target")
                })
                else -> throw IllegalArgumentException("Invalid native control")
            }
            require(control.kind == kind) { "Invalid native control" }
            control
        } catch (error: org.json.JSONException) {
            throw IllegalArgumentException("Invalid native control", error)
        }
    }
}

internal data class CodexNativeGoal(val objective: String, val status: String, val tokenBudget: Long?, val tokensUsed: Long, val timeUsedSeconds: Long) {
    fun describe(): String = "Goal: $status\n$objective\nTokens: $tokensUsed${tokenBudget?.let { " / $it" }.orEmpty()} · Time: ${timeUsedSeconds}s"
    companion object {
        fun parse(obj: JSONObject): CodexNativeGoal {
            val status = obj.getString("status")
            require(status in setOf("active", "paused", "blocked", "usageLimited", "budgetLimited", "complete")) { "Unknown native goal status" }
            return CodexNativeGoal(obj.getString("objective"), status,
                if (obj.isNull("tokenBudget")) null else obj.getLong("tokenBudget"), obj.getLong("tokensUsed"), obj.getLong("timeUsedSeconds"))
        }
    }
}
