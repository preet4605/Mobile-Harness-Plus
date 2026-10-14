package com.jarves.mh.mods

import android.content.Context
import com.jarves.mh.data.ApiKeyVault

/**
 * A mod's own secrets (spec v2 `$.secrets`, and its secret settings): encrypted in [ApiKeyVault]
 * under `mod:<id>:<key>`, never passed to the mod's `options`. Removing the mod removes them.
 */
class ModSecrets(context: Context) : ModSecretStore {
    private val vault = ApiKeyVault(context.applicationContext)
    private val index = context.applicationContext.getSharedPreferences("mod_secret_keys", Context.MODE_PRIVATE)

    override fun get(modId: String, key: String): String? = if (has(modId, key)) vault.get(storageId(modId, key)) else null

    fun has(modId: String, key: String): Boolean = key in keys(modId)

    /** Saves [value] for [key], or removes it when [value] is null or blank. */
    @Synchronized
    override fun set(modId: String, key: String, value: String?) {
        require(KEY.matches(key)) { "Secret names are 1 to 64 letters, digits, dots, dashes or underscores." }
        require((value?.length ?: 0) <= MAX_SECRET) { "A secret can be at most ${MAX_SECRET / 1024} KB." }
        vault.remove(storageId(modId, key))
        val keys = keys(modId).toMutableSet()
        if (value.isNullOrBlank()) {
            keys.remove(key)
        } else {
            require(key in keys || keys.size < MAX_SECRETS) { "A mod can keep at most $MAX_SECRETS secrets." }
            vault.put(storageId(modId, key), value)
            keys.add(key)
        }
        index.edit().putStringSet(modId, keys).apply()
    }

    @Synchronized
    fun removeAll(modId: String) {
        keys(modId).forEach { vault.remove(storageId(modId, it)) }
        index.edit().remove(modId).apply()
    }

    private fun keys(modId: String): Set<String> = index.getStringSet(modId, null)?.toSet().orEmpty()

    private fun storageId(modId: String, key: String) = "mod:$modId:$key"

    companion object {
        val KEY = Regex("^[A-Za-z0-9_.-]{1,64}$")
        const val MAX_SECRET = 8 * 1024
        const val MAX_SECRETS = 32
    }
}
