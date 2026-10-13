package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.AgentKind
import com.jarves.mh.runtime.task.DurableTaskRecord
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray

/** Resume IDs are indexed by the durable chat owner and the exact launch route/workspace. */
internal object NativeConversationScope {
    fun mayResumeFrom(
        previousTask: DurableTaskRecord?,
        currentTask: DurableTaskRecord?,
        agentKind: AgentKind,
    ): Boolean = previousTask != null && currentTask != null &&
        previousTask.taskId != currentTask.taskId &&
        previousTask.projectId == currentTask.projectId &&
        previousTask.chatId == currentTask.chatId &&
        previousTask.agentKind == agentKind.name &&
        currentTask.agentKind == agentKind.name &&
        previousTask.providerJson == currentTask.providerJson

    fun key(chatId: String, workspace: File, provider: ProviderProfile, credentialIdentity: String): String {
        val inputs = JSONArray(listOf(workspace.canonicalPath, provider.kind.name, provider.profileId,
            provider.baseUrl, provider.model, provider.dshApi, provider.claudeAuthMode.name, credentialIdentity))
        val digest = MessageDigest.getInstance("SHA-256").digest(inputs.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$chatId:$digest"
    }
    fun validId(value: String?): String? = value?.takeIf {
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(it)
    }
}
