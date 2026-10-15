package com.jarves.mh.mods

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

/**
 * What the user changed about a mod's `contributes.roles` on its console (mod dashboards phase 3):
 * the harness, model and effort picked for each role, and the chat each role works in, per
 * project. Kept in the mod's data folder as `roles.json`, so it goes when the mod is removed.
 */
class ModRoles(private val dataDir: (String) -> File) {
    /** A role's picks; null fields keep what the mod declares. */
    data class Choice(val harness: String? = null, val model: String? = null, val effort: String? = null)

    private class Saved(val choices: Map<String, Choice>, val chats: Map<String, String>)

    private val saved = HashMap<String, Saved>()
    private val _choices = MutableStateFlow<Map<String, Map<String, Choice>>>(emptyMap())
    /** Each mod's role picks, by role id, for mods read so far. */
    val choices: StateFlow<Map<String, Map<String, Choice>>> = _choices.asStateFlow()

    /** [role] with the user's picks: a model or effort declared for another harness is dropped. */
    fun effective(modId: String, role: ModRole): ModRole = apply(role, choice(modId, role.id))

    fun choice(modId: String, roleId: String): Choice = read(modId).choices[roleId] ?: Choice()

    /** Saves the user's picks for [roleId]; an empty [Choice] goes back to what the mod declares. */
    fun setChoice(modId: String, roleId: String, choice: Choice) = synchronized(this) {
        val now = read(modId)
        val choices = if (choice == Choice()) now.choices - roleId else now.choices + (roleId to choice)
        write(modId, Saved(choices, now.chats))
    }

    /** The chat [roleId] works in, in [projectId]; null before its first send. */
    fun chat(modId: String, projectId: String, roleId: String): String? = read(modId).chats["$projectId/$roleId"]

    fun setChat(modId: String, projectId: String, roleId: String, chatId: String) = synchronized(this) {
        val now = read(modId)
        val chats = (now.chats - "$projectId/$roleId" + ("$projectId/$roleId" to chatId)).entries.toList().takeLast(MAX_CHATS)
        write(modId, Saved(now.choices, chats.associate { it.key to it.value }))
    }

    private fun read(modId: String): Saved = synchronized(this) {
        saved[modId] ?: runCatching {
            val json = JSONObject(File(dataDir(modId), FILE).readText())
            val choices = json.optJSONObject("choices")?.let { all ->
                all.keys().asSequence().associateWith { id ->
                    all.getJSONObject(id).let { Choice(it.text("harness"), it.text("model"), it.text("effort")) }
                }
            }.orEmpty()
            val chats = json.optJSONObject("chats")?.let { all -> all.keys().asSequence().associateWith { all.getString(it) } }.orEmpty()
            Saved(choices, chats)
        }.getOrDefault(Saved(emptyMap(), emptyMap())).also { remember(modId, it) }
    }

    private fun write(modId: String, value: Saved) {
        remember(modId, value)
        val json = JSONObject()
            .put("choices", JSONObject(value.choices.mapValues { (_, c) ->
                JSONObject().put("harness", c.harness ?: JSONObject.NULL).put("model", c.model ?: JSONObject.NULL).put("effort", c.effort ?: JSONObject.NULL)
            }))
            .put("chats", JSONObject(value.chats))
        runCatching {
            val dir = dataDir(modId).apply { mkdirs() }
            val temp = File(dir, "$FILE.tmp")
            temp.writeText(json.toString())
            java.nio.file.Files.move(temp.toPath(), File(dir, FILE).toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun remember(modId: String, value: Saved) {
        saved[modId] = value
        _choices.update { it + (modId to value.choices) }
    }

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    companion object {
        /** [role] with [choice] applied. */
        fun apply(role: ModRole, choice: Choice?): ModRole {
            if (choice == null) return role
            val harness = choice.harness ?: role.harness
            val sameHarness = harness == role.harness
            return role.copy(
                harness = harness,
                model = choice.model ?: role.model.takeIf { sameHarness },
                effort = choice.effort ?: role.effort.takeIf { sameHarness },
            )
        }

        const val FILE = "roles.json"
        /** Project and role pairs remembered per mod; the oldest go first. */
        const val MAX_CHATS = 200
    }
}
