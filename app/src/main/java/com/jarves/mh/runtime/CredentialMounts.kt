package com.jarves.mh.runtime

import java.io.File
import java.nio.file.Files

/** PRoot mount mitigation; this is not an OS security boundary or a sandbox for hostile native code. */
internal object CredentialMounts {
    private val homes = listOf("/root/.claude", "/root/.codex", "/root/.codex-accounts", "/root/.dsh", "/root/.gemini", "/root/.antigravity-accounts")
    fun hiddenHomes(activeHome: String): List<String> {
        require(homes.any { activeHome == it || activeHome.startsWith("$it/") }) { "Unknown credential home" }
        require(!activeHome.split('/').any { it == ".." || it == "." })
        // bindings() masks collection siblings so the active home needs no overlapping rebind.
        return homes.filter { it != activeHome }
    }

    /** Mask siblings instead of overlapping the active home. Existing CLI paths stay valid. */
    fun bindings(rootfs: File, mask: File, activeHome: String): List<String> {
        val hidden = hiddenHomes(activeHome)
        val root = rootfs.canonicalFile
        val active = File(root, activeHome.removePrefix("/"))
        require(active.canonicalFile == active.absoluteFile && active.toPath().startsWith(root.toPath()))
        require(!Files.isSymbolicLink(active.toPath()))
        check(active.isDirectory || active.mkdirs())
        check(mask.isDirectory || mask.mkdirs())
        require(mask.listFiles()?.isEmpty() == true) { "Credential mask must be empty." }
        val parent = hidden.firstOrNull { activeHome.startsWith("$it/") }
        return buildList {
            hidden.filterNot { it == parent }.forEach { add("${mask.absolutePath}:$it") }
            if (parent != null) {
                val collection = File(root, parent.removePrefix("/"))
                require(collection.canonicalFile == collection.absoluteFile && !Files.isSymbolicLink(collection.toPath()))
                val siblings = checkNotNull(collection.listFiles()) { "Cannot inspect the credential collection." }
                siblings.filterNot { it == active }.forEach { sibling ->
                    require(!Files.isSymbolicLink(sibling.toPath())) { "Account collections must not contain symbolic links." }
                    val source = if (sibling.isDirectory) mask else File(mask, ".empty-file").apply {
                        check(isFile || createNewFile())
                    }
                    add("${source.absolutePath}:$parent/${sibling.name}")
                }
            }
        }
    }

    /** Old masks are never rebound or adopted. Erase obsolete shared and account-specific copies. */
    fun removeLegacyCopy(cacheDir: File, accountId: String) {
        require(accountId == CodexAccounts.LEGACY || accountId.matches(Regex("[0-9a-f-]{36}")))
        val cache = cacheDir.canonicalFile
        listOf("credential-mask/$accountId/auth.json", "credential-mask/auth.json").forEach { path ->
            val copy = File(cache, path)
            require(copy.canonicalFile == copy.absoluteFile && !Files.isSymbolicLink(copy.toPath()))
            check(!copy.exists() || copy.delete()) { "Could not remove cached account credentials." }
        }
    }
}
