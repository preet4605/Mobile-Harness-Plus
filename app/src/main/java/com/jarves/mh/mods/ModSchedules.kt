package com.jarves.mh.mods

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Mod schedules (mod plan row 21; `schedules`, Developer mode): each `contributes.schedules`
 * entry of an allowed mod is a periodic JobScheduler job (no new dependency) that wakes the app at
 * most every 15 minutes, only while the battery isn't low, and fires `schedule.run` with a 60 s
 * budget ([ModHost.runSchedule]). Nothing stays running in between. Jobs are not kept across a
 * reboot; opening the app schedules them again.
 */
object ModSchedules {
    /** Job ids `JOB_BASE until JOB_BASE + JOB_RANGE` belong to mod schedules. */
    const val JOB_BASE = 0x4D480000
    const val JOB_RANGE = 0x10000
    private const val MOD = "modId"
    private const val SCHEDULE = "scheduleId"

    data class Wanted(val modId: String, val scheduleId: String, val everyMinutes: Int) {
        val jobId: Int get() = jobId(modId, scheduleId)
    }

    fun jobId(modId: String, scheduleId: String): Int = JOB_BASE + ("$modId/$scheduleId".hashCode() and 0x7FFFFFFF) % JOB_RANGE

    /** The schedules that should be set now: active mods allowed `schedules` (Developer mode on), one per job id. */
    fun wanted(snapshot: ModsSnapshot): List<Wanted> = snapshot.active
        .filter { it.manifest.hooksModule != null && ModCapability.SCHEDULES in it.granted && it.canHold(ModCapability.SCHEDULES) }
        .sortedBy { it.id }
        .flatMap { mod -> mod.manifest.schedules.map { Wanted(mod.id, it.id, it.everyMinutes) } }
        .distinctBy { it.jobId }

    /** Makes the app's scheduled jobs match [snapshot]. Never throws. */
    fun sync(context: Context, snapshot: ModsSnapshot) {
        runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val wanted = wanted(snapshot).associateBy { it.jobId }
            scheduler.allPendingJobs.filter { it.id in JOB_BASE until JOB_BASE + JOB_RANGE && it.id !in wanted }
                .forEach { scheduler.cancel(it.id) }
            wanted.values.forEach { job ->
                val pending = scheduler.getPendingJob(job.jobId)
                val interval = job.everyMinutes * 60_000L
                if (pending != null && pending.intervalMillis == interval &&
                    pending.extras.getString(MOD) == job.modId && pending.extras.getString(SCHEDULE) == job.scheduleId) return@forEach
                val extras = PersistableBundle().apply {
                    putString(MOD, job.modId)
                    putString(SCHEDULE, job.scheduleId)
                }
                scheduler.schedule(
                    JobInfo.Builder(job.jobId, ComponentName(context, ModScheduleService::class.java))
                        .setPeriodic(interval)
                        .setRequiresBatteryNotLow(true)
                        .setExtras(extras)
                        .build(),
                )
            }
        }
    }

    internal fun modId(params: JobParameters): String? = params.extras.getString(MOD)
    internal fun scheduleId(params: JobParameters): String? = params.extras.getString(SCHEDULE)
}

/** Runs one mod schedule when Android wakes the app for it. Never throws; a failure just ends the job. */
class ModScheduleService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val modId = ModSchedules.modId(params) ?: return false
        val scheduleId = ModSchedules.scheduleId(params) ?: return false
        val app = applicationContext
        jobs[params.jobId] = scope.launch {
            runCatching {
                val registry = ModRegistry.getInstance(app)
                ModHost.getInstance(app).runSchedule(modId, scheduleId)
                // The app woke, ran the mod and is still here: this start was not a crash (mod safe mode).
                registry.markStable()
                ModSchedules.sync(app, registry.snapshot.value)
            }
            jobs.remove(params.jobId)
            runCatching { jobFinished(params, false) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
