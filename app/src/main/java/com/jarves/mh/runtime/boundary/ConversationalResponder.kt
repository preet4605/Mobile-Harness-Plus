package com.jarves.mh.runtime.boundary

import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.network.ProviderApiClient
import com.jarves.mh.runtime.task.DurableTaskRecord

interface ConversationalResponder {
    suspend fun respond(
        prompt: String,
        history: List<ChatMessage>,
        provider: ProviderProfile,
        apiKey: String?,
        lastTaskRecord: DurableTaskRecord? = null,
    ): String
}

class DefaultConversationalResponder(
    private val providerApiClient: ProviderApiClient = ProviderApiClient(),
) : ConversationalResponder {

    override suspend fun respond(
        prompt: String,
        history: List<ChatMessage>,
        provider: ProviderProfile,
        apiKey: String?,
        lastTaskRecord: DurableTaskRecord?,
    ): String {
        val protocol = com.jarves.mh.model.providerProtocolForAgent(provider, com.jarves.mh.model.AgentKind.CLAUDE_CODE)
        val key = apiKey.orEmpty()
        
        // Attempt normal model inference if provider is configured
        if (provider.baseUrl.isNotBlank() && provider.model.isNotBlank()) {
            val systemPrompt = buildSystemPrompt(lastTaskRecord)
            val result = providerApiClient.completeChat(
                baseUrl = provider.baseUrl,
                model = provider.model,
                apiKey = key,
                protocol = protocol,
                messages = history + ChatMessage(fromUser = true, text = prompt),
                systemPrompt = systemPrompt,
            )
            result.getOrNull()?.let { return it.trim() }
        }

        // Safe fallback conversational response when offline, unauthenticated, or API call fails
        return buildFallbackResponse(prompt, lastTaskRecord)
    }

    private fun buildSystemPrompt(lastTaskRecord: DurableTaskRecord?): String = buildString {
        appendLine("You are a helpful software engineering assistant in Mobile Harness.")
        appendLine("You are currently in CONVERSATION mode. You must answer questions, explain concepts, or review results.")
        appendLine("You do NOT have access to workspace execution tools in this turn.")
        if (lastTaskRecord != null) {
            appendLine("Previous task status: ${lastTaskRecord.status}")
            if (!lastTaskRecord.lastError.isNullOrBlank()) {
                appendLine("Previous task error: ${lastTaskRecord.lastError}")
            }
        }
    }

    private fun buildFallbackResponse(prompt: String, lastTaskRecord: DurableTaskRecord?): String {
        val clean = prompt.trim().lowercase()

        // Only use deterministic fallback responses for short, clearly conversational
        // messages. Never scan arbitrary long prompts for words such as "why" or "fail":
        // those words may occur inside quoted examples or task specifications.
        if (clean.length > 240) {
            return "I can answer this conversationally, but the provider is currently unavailable."
        }

        return when {
            clean == "now what" || clean == "now what?" ||
                clean == "what next" || clean == "what next?" ||
                clean == "what should i do next" || clean == "what should i do next?" -> {
                if (lastTaskRecord != null && lastTaskRecord.status.isTerminal) {
                    "The previous task is finished (${lastTaskRecord.status})."
                } else {
                    "I am ready to help with the next step."
                }
            }

            clean == "why" || clean == "why?" ||
                clean == "what happened" || clean == "what happened?" ||
                clean == "what failed" || clean == "what failed?" -> {
                val error = lastTaskRecord?.lastError
                if (!error.isNullOrBlank()) {
                    "The last task failed with: $error"
                } else if (lastTaskRecord != null) {
                    "The last task finished with status: ${lastTaskRecord.status}."
                } else {
                    "There is no recent execution result to explain."
                }
            }

            clean == "what changed" || clean == "what changed?" ||
                clean == "show me the result" || clean == "show me the result." -> {
                "Recent changes and task history are recorded in the chat timeline and workspace checkpoints."
            }

            clean == "is it fixed" || clean == "is it fixed?" ||
                clean == "is that fixed" || clean == "is that fixed?" -> {
                when {
                    lastTaskRecord?.status == com.jarves.mh.runtime.task.TaskExecutionStatus.COMPLETED ->
                        "Yes, the previous execution task completed successfully."
                    !lastTaskRecord?.lastError.isNullOrBlank() ->
                        "Not yet. The last task ended with an error: ${lastTaskRecord?.lastError}."
                    else ->
                        "I don't have enough execution information to confirm that."
                }
            }

            else -> {
                "I can answer questions and explain results in conversation mode."
            }
        }
    }
}
