package com.jarves.mh.runtime

/** PRoot mount mitigation; this is not an OS security boundary or a sandbox for hostile native code. */
internal object CredentialMounts {
    private val homes = listOf("/root/.claude", "/root/.codex", "/root/.dsh", "/root/.gemini", "/root/.antigravity-accounts")
    fun hiddenHomes(activeHome: String): List<String> {
        require(homes.any { activeHome == it || activeHome.startsWith("$it/") }) { "Unknown credential home" }
        require(!activeHome.split('/').any { it == ".." || it == "." })
        // Mask the account collection too; the selected account is rebound after this mount.
        return homes.filter { it != activeHome }
    }
}
