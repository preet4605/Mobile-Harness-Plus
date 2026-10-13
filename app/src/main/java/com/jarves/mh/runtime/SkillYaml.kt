package com.jarves.mh.runtime

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Only safe YAML values, with explicit input, alias and nesting limits. */
internal object SkillYaml {
    fun parse(text: String): Map<*, *> {
        require(text.length <= SkillFiles.MAX_MANIFEST_BYTES)
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 0
            nestingDepthLimit = 16
            codePointLimit = SkillFiles.MAX_MANIFEST_BYTES
        }
        val value = Yaml(SafeConstructor(options)).load<Any>(text)
        return if (value == null) emptyMap<Any, Any>() else value as? Map<*, *> ?: error("Skill metadata must be a mapping")
    }
}
