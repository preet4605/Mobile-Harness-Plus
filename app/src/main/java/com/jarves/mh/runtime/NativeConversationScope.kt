package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderProfile
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray

/** Resume IDs are indexed by the durable chat owner and the exact launch route/workspace. */
internal object NativeConversationScope {
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
