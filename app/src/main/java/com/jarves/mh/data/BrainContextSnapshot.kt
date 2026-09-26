package com.jarves.mh.data

import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionStep
import java.security.MessageDigest

/**
 * Immutable snapshot representing the captured Brain context for a specific execution attempt.
 * Preserves the exact knowledge state captured at attempt launch, protecting against
 * mid-execution mutation or staleness.
 */
data class BrainContextSnapshot(
    val taskId: String,
    val attemptId: String,
    val context: BrainContext = BrainContext(),
    val renderedContext: String,
    val createdAt: Long = System.currentTimeMillis(),
    val fingerprint: String = computeFingerprint(renderedContext)
) {
    companion object {
        fun computeFingerprint(content: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(content.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun create(
            taskId: String,
            attemptId: String,
            context: BrainContext,
            renderedContext: String,
            createdAt: Long = System.currentTimeMillis()
        ): BrainContextSnapshot {
            return BrainContextSnapshot(
                taskId = taskId,
                attemptId = attemptId,
                context = context,
                renderedContext = renderedContext,
                createdAt = createdAt,
                fingerprint = computeFingerprint(renderedContext)
            )
        }
    }
}

/**
 * Failure indicating Brain context assembly could not complete.
 * Causes task execution to halt before agent process launch.
 */
class BrainContextAssemblyException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
