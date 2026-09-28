package com.jarves.mh.model.brain

/**
 * Dedicated mandatory global execution policies guaranteed to reach the agent
 * on every execution attempt, independent of lexical retrieval, ranking, or context budget.
 */
object GlobalExecutionPolicies {

    const val REASONING_BUDGET_POLICY_NAME = "REASONING BUDGET POLICY"
    val REASONING_BUDGET_POLICY = """
REASONING BUDGET POLICY: Use deep reasoning only when task complexity requires it.
For straightforward implementation, bug fixes, tests, or known changes:
plan briefly → edit → targeted test → finish.

Do not perform redundant planning, repeated verification,
or unnecessary alternative implementations.
""".trimIndent()

    const val EXECUTION_EFFICIENCY_POLICY_NAME = "EXECUTION EFFICIENCY POLICY"
    val EXECUTION_EFFICIENCY_POLICY = """
EXECUTION EFFICIENCY POLICY: Work efficiently without sacrificing correctness.

1. Inspect only files relevant to the current task.
2. Reuse existing architecture, APIs, tests and previous findings before exploring alternatives.
3. Do not repeatedly reread unchanged files or rediscover established facts.
4. Prefer one targeted tool call over many exploratory calls.
5. Make a concise plan, then execute it directly.
6. Avoid speculative refactors and unrelated improvements.
7. After modifying code, run the smallest relevant verification.
""".trimIndent()

    val DEFAULT_POLICIES: List<String> = listOf(
        REASONING_BUDGET_POLICY,
        EXECUTION_EFFICIENCY_POLICY
    )

    fun isGlobalPolicyKey(key: String?): Boolean {
        if (key.isNullOrBlank()) return false
        val upper = key.uppercase()
        return upper.contains("REASONING BUDGET POLICY") ||
               upper.contains("EXECUTION EFFICIENCY POLICY")
    }
}
