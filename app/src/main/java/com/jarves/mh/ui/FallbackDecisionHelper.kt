package com.jarves.mh.ui

import com.jarves.mh.model.AgentKind

object FallbackDecisionHelper {
    /**
     * Pure testable decision logic extracted from MainViewModel:
     * Evaluates whether a fallback attempt is permissible given task and runtime lifecycle states.
     */
    fun shouldAttemptFallback(
        agentKind: AgentKind,
        isRunning: Boolean,
        isStopping: Boolean,
        isCancelled: Boolean,
        isTerminal: Boolean,
        hasEligibleFallback: Boolean
    ): Boolean {
        if (agentKind == AgentKind.ANTIGRAVITY) return false
        if (!isRunning || isStopping) return false
        if (isCancelled || isTerminal) return false
        return hasEligibleFallback
    }
}
