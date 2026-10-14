package com.jarves.mh.runtime

import com.jarves.mh.model.CustomizationScopeMode
import com.jarves.mh.model.SkillInfo

/** Launch-local native selection; account files are never rewritten. */
internal object CodexSkillSelection {
    data class NativeSkill(val name: String, val path: String, val scope: String = "repo", val enabled: Boolean = true)

    fun config(native: List<NativeSkill>, skills: List<SkillInfo>, scope: CustomizationScopeMode): String {
        require(native.size <= 256) { "Too many native Codex skills" }
        val selections = native.distinctBy { it.path }.map { item ->
            require(item.path.startsWith('/') && item.path.length <= 4096 && (item.path.endsWith("/SKILL.md") || item.path.endsWith("/SKILL.json")))
            require(item.path.split('/').none { it == ".." || it == "." })
            val known = skills.firstOrNull { it.name.equals(item.name, ignoreCase = true) }
            // In merge mode retain native built-ins not managed by the harness. A known skill's
            // precedence and exact guest path still apply; restrictive scopes admit only selections.
            val selected = if (known == null) scope == CustomizationScopeMode.INHERIT_AND_MERGE && item.scope != "repo"
                else known.isEnabled && known.allowImplicitInvocation && !known.isMissingSource && known.guestFilePath == item.path
            "{path=${CodexLaunchBuilder.tomlString(item.path)},enabled=${item.enabled && selected}}"
        }
        return "skills.config=[${selections.joinToString(",")}]".also { require(it.toByteArray(Charsets.UTF_8).size <= 96 * 1024) }
    }
}
