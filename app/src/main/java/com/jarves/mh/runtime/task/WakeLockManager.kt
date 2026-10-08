package com.jarves.mh.runtime.task

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Event-driven wake-lock manager optimizing battery and CPU consumption.
 * Ensures PARTIAL_WAKE_LOCK is held strictly while an active task is executing CPU instructions,
 * released immediately when process exits or enters terminal states, and paused while waiting
 * for user approval/input. The lock carries a timeout as a leak guard and is renewed while any
 * task is active, so a long silent command (a first Gradle build) keeps the CPU awake.
 */
class WakeLockManager(private val context: Context? = null) {

    private val lock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private val activeTaskIds = ConcurrentHashMap.newKeySet<String>()
    private val pausedTaskIds = ConcurrentHashMap.newKeySet<String>()

    private var renewalHandler: Handler? = null
    private var renewalScheduled = false
    private val renewal = Runnable { renew() }

    @Volatile var lastAcquiredAt: Long = 0L
        private set
    @Volatile var lastReleasedAt: Long = 0L
        private set

    val isHeld: Boolean
        get() = synchronized(lock) { wakeLock?.isHeld == true }

    val activeTaskCount: Int
        get() = activeTaskIds.size

    val pausedTaskCount: Int
        get() = pausedTaskIds.size

    companion object {
        private const val TAG = "WakeLockManager"
        private const val WAKE_LOCK_TAG = "com.jarves.mh:task-execution"
        // 15-minute leak guard: if the app stops renewing, the lock still expires on its own.
        const val TASK_WAKE_LOCK_TIMEOUT_MS = 15 * 60 * 1000L

        // Renewed three times per timeout window so one late tick cannot let the lock lapse.
        const val RENEWAL_INTERVAL_MS = 5 * 60 * 1000L
    }

    fun acquire(taskId: String, timeoutMs: Long = TASK_WAKE_LOCK_TIMEOUT_MS) {
        synchronized(lock) {
            pausedTaskIds.remove(taskId)
            activeTaskIds.add(taskId)

            if (wakeLock == null) {
                runCatching {
                    val pm = context?.getSystemService(PowerManager::class.java)
                    wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                        setReferenceCounted(false)
                    }
                }.onFailure { Log.e(TAG, "Failed to create WakeLock", it) }
            }

            wakeLock?.let { wl ->
                if (!wl.isHeld) {
                    runCatching {
                        wl.acquire(timeoutMs)
                        lastAcquiredAt = System.currentTimeMillis()
                        Log.d(TAG, "Acquired WakeLock for task $taskId (timeout: ${timeoutMs}ms)")
                    }.onFailure { Log.e(TAG, "Failed to acquire WakeLock", it) }
                }
            }
            scheduleRenewalLocked()
        }
    }

    /** Must hold [lock]. A no-op without a real wake lock (no context) or while a tick is pending. */
    private fun scheduleRenewalLocked() {
        if (renewalScheduled || wakeLock == null) return
        val handler = renewalHandler ?: Handler(Looper.getMainLooper()).also { renewalHandler = it }
        renewalScheduled = handler.postDelayed(renewal, RENEWAL_INTERVAL_MS)
    }

    /**
     * Re-arms the timeout, or takes the lock again if it already lapsed. Stops by itself once no
     * task is active; [releaseInternal] also cancels a pending tick.
     */
    private fun renew() {
        synchronized(lock) {
            renewalScheduled = false
            if (activeTaskIds.isEmpty()) return
            wakeLock?.let { wl ->
                runCatching {
                    wl.acquire(TASK_WAKE_LOCK_TIMEOUT_MS)
                    lastAcquiredAt = System.currentTimeMillis()
                }.onFailure { Log.e(TAG, "Failed to renew WakeLock", it) }
            }
            scheduleRenewalLocked()
        }
    }

    /**
     * Pauses wake lock when task is WAITING_FOR_APPROVAL or WAITING_FOR_INPUT.
     * Prevents draining battery while waiting on human intervention.
     */
    fun pause(taskId: String) {
        synchronized(lock) {
            if (activeTaskIds.remove(taskId)) {
                pausedTaskIds.add(taskId)
                runCatching { Log.d(TAG, "Paused WakeLock for task $taskId (awaiting user approval/input)") }
            }
            if (activeTaskIds.isEmpty()) {
                releaseInternal()
            }
        }
    }

    /**
     * Resumes wake lock when user responds to approval or provides input.
     */
    fun resume(taskId: String, timeoutMs: Long = TASK_WAKE_LOCK_TIMEOUT_MS) {
        acquire(taskId, timeoutMs)
    }

    /**
     * Releases wake lock when task completes, fails, cancels, or process terminates.
     */
    fun release(taskId: String) {
        synchronized(lock) {
            activeTaskIds.remove(taskId)
            pausedTaskIds.remove(taskId)
            runCatching { Log.d(TAG, "Released task $taskId from WakeLockManager (remaining active: ${activeTaskIds.size})") }
            if (activeTaskIds.isEmpty()) {
                releaseInternal()
            }
        }
    }

    fun releaseAll() {
        synchronized(lock) {
            activeTaskIds.clear()
            pausedTaskIds.clear()
            releaseInternal()
        }
    }

    private fun releaseInternal() {
        renewalHandler?.removeCallbacks(renewal)
        renewalScheduled = false
        wakeLock?.let { wl ->
            if (wl.isHeld) {
                runCatching {
                    wl.release()
                    lastReleasedAt = System.currentTimeMillis()
                    Log.d(TAG, "Released system WakeLock (no active tasks)")
                }.onFailure { Log.e(TAG, "Error releasing WakeLock", it) }
            }
        }
    }
}
