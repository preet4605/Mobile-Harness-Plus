package com.jarves.mh.runtime

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class WireFormat { ANTHROPIC, OPENAI }

data class AntigravityToolCall(
    val id: String,
    val name: String,
    val argsJson: String,
)

data class ParsedPart(
    val text: String? = null,
    val thinking: String? = null,
    val toolCall: AntigravityToolCall? = null,
)

object AntigravityProtocolAdapter {

    val SUPPORTED_MODELS = listOf(
        "gemini-3.8-pro",
        "gemini-3.8-flash",
        "gemini-2.5-pro",
        "gemini-2.5-flash",
        "claude-3-7-sonnet",
        "claude-3-5-sonnet",
    )

    fun toUpstreamModel(model: String): String {
        val clean = model.trim().lowercase()
        return when {
            clean.contains("3-7-sonnet") || clean.contains("3-5-sonnet") || clean.contains("sonnet") -> "claude-sonnet-4-6"
            clean.contains("opus") -> "claude-opus-4-6-thinking"
            clean.startsWith("claude") -> "claude-sonnet-4-6"
            clean.contains("3.8-pro") || clean.contains("3-8-pro") || clean.contains("pro-agent") || clean == "gemini-pro" -> "gemini-pro-agent"
            clean.contains("3.1-pro-low") -> "gemini-3.1-pro-low"
            clean.contains("3.8-flash") || clean.contains("3-8-flash") || clean.contains("flash-tiered") -> "gemini-3.8-flash-tiered"
            clean.contains("3.6-flash") || clean.contains("3-6-flash") -> "gemini-3.6-flash-high"
            clean.contains("2.5-flash") || clean.contains("2-5-flash") -> "gemini-2.5-flash"
            clean.contains("2.5-pro") || clean.contains("2-5-pro") -> "gemini-pro-agent"
            clean.contains("flash") -> "gemini-3.8-flash-tiered"
            clean.contains("pro") -> "gemini-pro-agent"
            else -> clean
        }
    }

    fun toUpstreamPayload(geminiRequest: JSONObject): JSONObject {
        val model = geminiRequest.optString("model", "gemini-pro-agent")
        val upstreamModel = toUpstreamModel(model)
        val innerRequest = JSONObject(geminiRequest.toString())
        innerRequest.remove("model")
        return JSONObject()
            .put("project", "")
            .put("model", upstreamModel)
            .put("request", innerRequest)
    }

    fun normalizeModel(requested: String?, defaultModel: String = "gemini-3.8-pro"): String {
        val req = requested?.trim().orEmpty().lowercase()
        return when {
            req.isEmpty() || req == "default" -> defaultModel.ifBlank { "gemini-3.8-pro" }
            req.contains("3-7-sonnet") || req.contains("sonnet-4-6") -> "claude-3-7-sonnet"
            req.contains("3-5-sonnet") -> "claude-3-5-sonnet"
            req.contains("opus") -> "claude-3-7-sonnet"
            req.startsWith("claude") -> "claude-3-7-sonnet"
            req.contains("deepseek") -> "gemini-3.8-flash"
            req.contains("gpt-oss") -> "gemini-3.8-flash"
            req in SUPPORTED_MODELS -> req
            req.contains("3.8-pro") || req.contains("3-8-pro") || req.contains("3.1-pro") || req.contains("3-1-pro") -> "gemini-3.8-pro"
            req.contains("3.8-flash") || req.contains("3-8-flash") || req.contains("3.6-flash") || req.contains("3-6-flash") -> "gemini-3.8-flash"
            req.contains("2.5-pro") || req.contains("2-5-pro") -> "gemini-2.5-pro"
            req.contains("2.5-flash") || req.contains("2-5-flash") -> "gemini-2.5-flash"
            req.startsWith("gemini") -> {
                val stripped = req.removeSuffix("-high").removeSuffix("-medium").removeSuffix("-low").removeSuffix("-tiered")
                if (stripped in SUPPORTED_MODELS) stripped
                else if (stripped.contains("flash")) "gemini-3.8-flash"
                else "gemini-3.8-pro"
            }
            else -> defaultModel.ifBlank { "gemini-3.8-pro" }
        }
    }

    fun toGeminiRequest(source: JSONObject, format: WireFormat, defaultModel: String = "gemini-3.8-pro"): JSONObject {
        val requestedModel = source.optString("model")
        val effectiveModel = normalizeModel(requestedModel, defaultModel)
        val target = JSONObject()
        target.put("model", effectiveModel)

        // 1. System instructions
        val systemText = when (format) {
            WireFormat.ANTHROPIC -> extractAnthropicSystem(source.opt("system"))
            WireFormat.OPENAI -> extractOpenAiSystem(source.optJSONArray("messages"))
        }
        if (systemText.isNotBlank()) {
            target.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText))))
        }

        // 2. Messages translation
        val contents = when (format) {
            WireFormat.ANTHROPIC -> convertAnthropicMessages(source.optJSONArray("messages"))
            WireFormat.OPENAI -> convertOpenAiMessages(source.optJSONArray("messages"))
        }
        if (contents.length() == 0) {
            contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", " "))))
            target.put("contents", contents)
        } else if (contents.getJSONObject(0).optString("role") == "model") {
            val fixed = JSONArray()
            fixed.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", " "))))
            for (i in 0 until contents.length()) fixed.put(contents.get(i))
            target.put("contents", fixed)
        } else {
            target.put("contents", contents)
        }

        // 3. Tools translation
        val toolsArray = when (format) {
            WireFormat.ANTHROPIC -> convertAnthropicTools(source.optJSONArray("tools"))
            WireFormat.OPENAI -> convertOpenAiTools(source.optJSONArray("tools"))
        }
        if (toolsArray.length() > 0) {
            target.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", toolsArray)))
        }

        // 4. Generation config
        val genConfig = JSONObject()
        val maxTokens = when (format) {
            WireFormat.ANTHROPIC -> source.optInt("max_tokens", 8192)
            WireFormat.OPENAI -> source.optInt("max_tokens", source.optInt("max_completion_tokens", 8192))
        }
        genConfig.put("maxOutputTokens", if (maxTokens > 0) maxTokens else 8192)

        if (source.has("temperature") && !source.isNull("temperature")) {
            val temp = source.optDouble("temperature")
            if (!temp.isNaN()) genConfig.put("temperature", temp)
        }
        if (source.has("top_p") && !source.isNull("top_p")) {
            val topP = source.optDouble("top_p")
            if (!topP.isNaN()) genConfig.put("topP", topP)
        }
        val stopSeq = source.optJSONArray("stop_sequences") ?: source.optJSONArray("stop")
        if (stopSeq != null && stopSeq.length() > 0) {
            genConfig.put("stopSequences", stopSeq)
        } else {
            val singleStop = source.optString("stop").takeIf { it.isNotBlank() }
            if (singleStop != null) genConfig.put("stopSequences", JSONArray().put(singleStop))
        }

        // Thinking configuration
        val thinking = source.optJSONObject("thinking")
        if (thinking != null && thinking.optString("type") == "enabled") {
            val budget = thinking.optInt("budget_tokens", 4096)
            genConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", budget))
        } else {
            val reqLower = requestedModel.lowercase()
            val budget = when {
                reqLower.contains("-high") -> 16384
                reqLower.contains("-medium") || reqLower.contains("-thinking") -> 4096
                reqLower.contains("-low") -> 1024
                else -> null
            }
            if (budget != null) {
                genConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", budget))
            }
        }

        target.put("generationConfig", genConfig)
        return target
    }

    private fun extractAnthropicSystem(system: Any?): String = when (system) {
        is String -> system.trim()
        is JSONArray -> {
            val sb = StringBuilder()
            for (i in 0 until system.length()) {
                val item = system.opt(i)
                if (item is JSONObject && item.optString("type") == "text") {
                    sb.append(item.optString("text")).append("\n")
                } else if (item is String) {
                    sb.append(item).append("\n")
                }
            }
            sb.toString().trim()
        }
        else -> ""
    }

    private fun extractOpenAiSystem(messages: JSONArray?): String {
        if (messages == null) return ""
        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            if (msg.optString("role") == "system") {
                val content = msg.opt("content")
                if (content is String) {
                    sb.append(content).append("\n")
                } else if (content is JSONArray) {
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j)
                        if (part != null && part.optString("type") == "text") {
                            sb.append(part.optString("text")).append("\n")
                        }
                    }
                }
            }
        }
        return sb.toString().trim()
    }

    private fun convertAnthropicMessages(messages: JSONArray?): JSONArray {
        val result = JSONArray()
        if (messages == null) return result

        val toolIdToName = mutableMapOf<String, String>()

        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            val rawRole = msg.optString("role")
            val role = if (rawRole == "assistant") "model" else "user"
            val parts = JSONArray()

            val content = msg.opt("content")
            if (content is String) {
                if (content.isNotEmpty()) {
                    parts.put(JSONObject().put("text", content))
                }
            } else if (content is JSONArray) {
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    when (part.optString("type")) {
                        "text" -> {
                            val text = part.optString("text")
                            if (text.isNotEmpty()) parts.put(JSONObject().put("text", text))
                        }
                        "thinking" -> {
                            val thinking = part.optString("thinking")
                            if (thinking.isNotEmpty()) {
                                parts.put(JSONObject().put("thought", true).put("text", thinking))
                            }
                        }
                        "tool_use" -> {
                            val toolId = part.optString("id")
                            val fnName = part.optString("name")
                            if (toolId.isNotBlank() && fnName.isNotBlank()) {
                                toolIdToName[toolId] = fnName
                            }
                            val call = JSONObject()
                                .put("name", fnName)
                                .put("args", part.optJSONObject("input") ?: JSONObject())
                            parts.put(JSONObject().put("functionCall", call))
                        }
                        "tool_result" -> {
                            val toolId = part.optString("tool_use_id")
                            val fnName = part.optString("name").ifBlank { toolIdToName[toolId] ?: toolId.ifBlank { "tool" } }
                            val resp = JSONObject()
                                .put("name", fnName)
                                .put("response", JSONObject().put("output", part.opt("content") ?: ""))
                            parts.put(JSONObject().put("functionResponse", resp))
                        }
                    }
                }
            }

            if (parts.length() > 0) {
                mergeOrAppendContent(result, role, parts)
            }
        }
        return result
    }

    private fun convertOpenAiMessages(messages: JSONArray?): JSONArray {
        val result = JSONArray()
        if (messages == null) return result

        val toolIdToName = mutableMapOf<String, String>()

        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            val rawRole = msg.optString("role")
            if (rawRole == "system" || rawRole == "developer") continue // handled separately

            val role = if (rawRole == "assistant") "model" else "user"
            val parts = JSONArray()

            if (rawRole != "tool") {
                val content = msg.opt("content")
                if (content is String && content.isNotEmpty()) {
                    parts.put(JSONObject().put("text", content))
                } else if (content is JSONArray) {
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "text") {
                            val text = part.optString("text")
                            if (text.isNotEmpty()) parts.put(JSONObject().put("text", text))
                        }
                    }
                }
            }

            val toolCalls = msg.optJSONArray("tool_calls")
            if (toolCalls != null) {
                for (k in 0 until toolCalls.length()) {
                    val callObj = toolCalls.optJSONObject(k) ?: continue
                    val fn = callObj.optJSONObject("function") ?: continue
                    val callId = callObj.optString("id")
                    val fnName = fn.optString("name")
                    if (callId.isNotBlank() && fnName.isNotBlank()) {
                        toolIdToName[callId] = fnName
                    }
                    val argsObj = runCatching { JSONObject(fn.optString("arguments", "{}")) }.getOrElse { JSONObject() }
                    val call = JSONObject()
                        .put("name", fnName)
                        .put("args", argsObj)
                    parts.put(JSONObject().put("functionCall", call))
                }
            }

            if (rawRole == "tool") {
                val callId = msg.optString("tool_call_id")
                val fnName = msg.optString("name").ifBlank { toolIdToName[callId] ?: callId.ifBlank { "tool" } }
                val resp = JSONObject()
                    .put("name", fnName)
                    .put("response", JSONObject().put("output", msg.opt("content") ?: ""))
                parts.put(JSONObject().put("functionResponse", resp))
            }

            if (parts.length() > 0) {
                mergeOrAppendContent(result, role, parts)
            }
        }
        return result
    }

    private fun mergeOrAppendContent(result: JSONArray, role: String, newParts: JSONArray) {
        if (result.length() > 0) {
            val last = result.getJSONObject(result.length() - 1)
            if (last.optString("role") == role) {
                val existingParts = last.getJSONArray("parts")
                for (i in 0 until newParts.length()) {
                    existingParts.put(newParts.get(i))
                }
                return
            }
        }
        result.put(JSONObject().put("role", role).put("parts", newParts))
    }

    private fun convertAnthropicTools(tools: JSONArray?): JSONArray {
        val result = JSONArray()
        if (tools == null) return result
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            val decl = JSONObject()
                .put("name", tool.optString("name"))
                .put("description", tool.optString("description"))
                .put("parameters", tool.optJSONObject("input_schema") ?: JSONObject().put("type", "object"))
            result.put(decl)
        }
        return result
    }

    private fun convertOpenAiTools(tools: JSONArray?): JSONArray {
        val result = JSONArray()
        if (tools == null) return result
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            val fn = tool.optJSONObject("function") ?: tool
            val decl = JSONObject()
                .put("name", fn.optString("name"))
                .put("description", fn.optString("description"))
                .put("parameters", fn.optJSONObject("parameters") ?: JSONObject().put("type", "object"))
            result.put(decl)
        }
        return result
    }

    fun parseGeminiChunk(json: JSONObject): List<ParsedPart> {
        val results = mutableListOf<ParsedPart>()
        val effectiveJson = json.optJSONObject("response") ?: json
        val candidates = effectiveJson.optJSONArray("candidates") ?: return results
        if (candidates.length() == 0) return results
        val candidate = candidates.getJSONObject(0)
        val content = candidate.optJSONObject("content") ?: return results
        val parts = content.optJSONArray("parts") ?: return results

        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            when {
                part.has("functionCall") -> {
                    val call = part.getJSONObject("functionCall")
                    val id = "toolu_" + UUID.randomUUID().toString().replace("-", "").take(16)
                    val argsStr = part.optJSONObject("functionCall")?.optJSONObject("args")?.toString() ?: "{}"
                    results.add(ParsedPart(toolCall = AntigravityToolCall(id = id, name = call.optString("name"), argsJson = argsStr)))
                }
                part.optBoolean("thought") || part.has("thought") || part.has("thinking") -> {
                    val isThoughtBool = part.optBoolean("thought", false)
                    val thought = if (isThoughtBool) {
                        part.optString("text")
                    } else {
                        part.optString("thought").ifBlank {
                            part.optString("thinking").ifBlank { part.optString("text") }
                        }
                    }
                    if (thought.isNotEmpty()) results.add(ParsedPart(thinking = thought))
                }
                part.has("text") -> {
                    val text = part.optString("text")
                    if (text.isNotEmpty()) results.add(ParsedPart(text = text))
                }
            }
        }
        return results
    }

    // Anthropic SSE events
    fun anthropicMessageStart(id: String, model: String, inputTokens: Int = 0): String {
        val data = JSONObject()
            .put("type", "message_start")
            .put("message", JSONObject()
                .put("id", id)
                .put("type", "message")
                .put("role", "assistant")
                .put("model", model)
                .put("content", JSONArray())
                .put("stop_reason", JSONObject.NULL)
                .put("stop_sequence", JSONObject.NULL)
                .put("usage", JSONObject().put("input_tokens", inputTokens).put("output_tokens", 0)))
        return "event: message_start\ndata: $data\n\n"
    }

    fun anthropicContentBlockStart(index: Int, type: String, id: String? = null, name: String? = null): String {
        val block = JSONObject().put("type", type)
        when (type) {
            "text" -> block.put("text", "")
            "thinking" -> block.put("thinking", "")
            "tool_use" -> {
                block.put("id", id ?: ("toolu_" + UUID.randomUUID().toString().replace("-", "").take(16)))
                block.put("name", name ?: "tool")
                block.put("input", JSONObject())
            }
        }
        val data = JSONObject()
            .put("type", "content_block_start")
            .put("index", index)
            .put("content_block", block)
        return "event: content_block_start\ndata: $data\n\n"
    }

    fun anthropicTextDelta(index: Int, text: String): String {
        val data = JSONObject()
            .put("type", "content_block_delta")
            .put("index", index)
            .put("delta", JSONObject().put("type", "text_delta").put("text", text))
        return "event: content_block_delta\ndata: $data\n\n"
    }

    fun anthropicThinkingDelta(index: Int, thinking: String): String {
        val data = JSONObject()
            .put("type", "content_block_delta")
            .put("index", index)
            .put("delta", JSONObject().put("type", "thinking_delta").put("thinking", thinking))
        return "event: content_block_delta\ndata: $data\n\n"
    }

    fun anthropicInputJsonDelta(index: Int, partialJson: String): String {
        val data = JSONObject()
            .put("type", "content_block_delta")
            .put("index", index)
            .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", partialJson))
        return "event: content_block_delta\ndata: $data\n\n"
    }

    fun anthropicContentBlockStop(index: Int): String {
        val data = JSONObject()
            .put("type", "content_block_stop")
            .put("index", index)
        return "event: content_block_stop\ndata: $data\n\n"
    }

    fun anthropicMessageDelta(stopReason: String, outputTokens: Int): String {
        val data = JSONObject()
            .put("type", "message_delta")
            .put("delta", JSONObject().put("stop_reason", stopReason).put("stop_sequence", JSONObject.NULL))
            .put("usage", JSONObject().put("output_tokens", outputTokens))
        return "event: message_delta\ndata: $data\n\n"
    }

    fun anthropicMessageStop(): String = "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"

    // OpenAI SSE events
    fun openAiChunk(
        id: String,
        model: String,
        content: String? = null,
        reasoning: String? = null,
        toolCall: AntigravityToolCall? = null,
        finishReason: String? = null,
    ): String {
        val delta = JSONObject()
        if (content != null) delta.put("content", content)
        if (reasoning != null) delta.put("reasoning_content", reasoning)
        if (toolCall != null) {
            val calls = JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("id", toolCall.id)
                    .put("type", "function")
                    .put("function", JSONObject().put("name", toolCall.name).put("arguments", toolCall.argsJson))
            )
            delta.put("tool_calls", calls)
        }
        val choice = JSONObject()
            .put("index", 0)
            .put("delta", delta)
            .put("finish_reason", finishReason ?: JSONObject.NULL)
        val data = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put("choices", JSONArray().put(choice))
        return "data: $data\n\n"
    }

    fun openAiDone(): String = "data: [DONE]\n\n"

    // Non-streaming response builders
    fun toAnthropicResponse(geminiJson: JSONObject, model: String, id: String = "msg_" + UUID.randomUUID().toString().replace("-", "").take(16)): JSONObject {
        val effectiveJson = geminiJson.optJSONObject("response") ?: geminiJson
        val parts = parseGeminiChunk(effectiveJson)
        val contentArray = JSONArray()
        var hasToolCall = false

        for (part in parts) {
            if (part.text != null) {
                contentArray.put(JSONObject().put("type", "text").put("text", part.text))
            }
            if (part.thinking != null) {
                contentArray.put(JSONObject().put("type", "thinking").put("thinking", part.thinking))
            }
            if (part.toolCall != null) {
                hasToolCall = true
                val argsObj = runCatching { JSONObject(part.toolCall.argsJson) }.getOrElse { JSONObject() }
                contentArray.put(
                    JSONObject()
                        .put("type", "tool_use")
                        .put("id", part.toolCall.id)
                        .put("name", part.toolCall.name)
                        .put("input", argsObj)
                )
            }
        }

        val usageMeta = effectiveJson.optJSONObject("usageMetadata")
        val inputTokens = usageMeta?.optInt("promptTokenCount", 0) ?: 0
        val outputTokens = usageMeta?.optInt("candidatesTokenCount", 0) ?: 0

        val stopReason = if (hasToolCall) "tool_use" else "end_turn"
        return JSONObject()
            .put("id", id)
            .put("type", "message")
            .put("role", "assistant")
            .put("model", model)
            .put("content", contentArray)
            .put("stop_reason", stopReason)
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", JSONObject().put("input_tokens", inputTokens).put("output_tokens", outputTokens))
    }

    fun toOpenAiResponse(geminiJson: JSONObject, model: String, id: String = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(16)): JSONObject {
        val effectiveJson = geminiJson.optJSONObject("response") ?: geminiJson
        val parts = parseGeminiChunk(effectiveJson)
        val textBuilder = StringBuilder()
        val toolCalls = JSONArray()

        for (part in parts) {
            if (part.text != null) textBuilder.append(part.text)
            if (part.toolCall != null) {
                toolCalls.put(
                    JSONObject()
                        .put("id", part.toolCall.id)
                        .put("type", "function")
                        .put("function", JSONObject().put("name", part.toolCall.name).put("arguments", part.toolCall.argsJson))
                )
            }
        }

        val msg = JSONObject().put("role", "assistant")
        if (textBuilder.isNotEmpty()) msg.put("content", textBuilder.toString()) else msg.put("content", JSONObject.NULL)
        if (toolCalls.length() > 0) msg.put("tool_calls", toolCalls)

        val hasToolCall = toolCalls.length() > 0
        val choice = JSONObject()
            .put("index", 0)
            .put("message", msg)
            .put("finish_reason", if (hasToolCall) "tool_calls" else "stop")

        val usageMeta = effectiveJson.optJSONObject("usageMetadata")
        val inputTokens = usageMeta?.optInt("promptTokenCount", 0) ?: 0
        val outputTokens = usageMeta?.optInt("candidatesTokenCount", 0) ?: 0

        return JSONObject()
            .put("id", id)
            .put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put("choices", JSONArray().put(choice))
            .put("usage", JSONObject()
                .put("prompt_tokens", inputTokens)
                .put("completion_tokens", outputTokens)
                .put("total_tokens", inputTokens + outputTokens))
    }

    fun anthropicErrorJson(type: String, message: String): String =
        JSONObject().put("type", "error").put("error", JSONObject().put("type", type).put("message", message)).toString()

    fun openAiErrorJson(message: String, type: String = "invalid_request_error", code: Int = 400): String =
        JSONObject().put("error", JSONObject().put("message", message).put("type", type).put("code", code)).toString()
}
