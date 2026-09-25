package com.jarves.mh.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarves.mh.MainActivity
import com.jarves.mh.R
import com.jarves.mh.runtime.task.TaskSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal object RuntimeTaskController {
    @Volatile var stopAction: (() -> Unit)? = null

    fun requestStop() {
        stopAction?.invoke()
    }
}

/**
 * Thin Android foreground service adapter. Exposes foreground notifications
 * and user stop controls while delegating execution state and power management
 * to TaskSupervisor.
 */
class RuntimeExecutionService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var projectName: String = "your project"
    private var notificationTitle: String = "Mobile Harness is working"
    private var canStop: Boolean = true
    private var currentTaskId: String? = null
    private var acquiredWakeLockTaskId: String? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_PROJECT_NAME)?.takeIf(String::isNotBlank)?.let { projectName = it }
        intent?.getStringExtra(EXTRA_TITLE)?.takeIf(String::isNotBlank)?.let { notificationTitle = it }
        intent?.getStringExtra(EXTRA_TASK_ID)?.takeIf(String::isNotBlank)?.let { currentTaskId = it }
        if (intent?.hasExtra(EXTRA_CAN_STOP) == true) canStop = intent.getBooleanExtra(EXTRA_CAN_STOP, true)

        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                RuntimeTaskController.requestStop()
                serviceScope.launch {
                    val taskSupervisor = TaskSupervisor.getInstance(applicationContext)
                    val taskId = currentTaskId ?: acquiredWakeLockTaskId
                    if (taskId != null) {
                        taskSupervisor.requestStop(taskId)
                    } else {
                        taskSupervisor.requestStopActive()
                    }
                }
                getSystemService(NotificationManager::class.java).notify(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification("Stopping safely…", includeStop = false),
                )
            }
            ACTION_PROGRESS -> {
                val detail = intent?.getStringExtra(EXTRA_DETAIL)?.takeIf { it.isNotBlank() }
                    ?: "Working in $projectName"
                getSystemService(NotificationManager::class.java).notify(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification(detail, includeStop = canStop),
                )
            }
            ACTION_COMPLETE -> finishTask(
                title = "Task completed",
                detail = intent?.getStringExtra(EXTRA_DETAIL) ?: "Mobile Harness finished working in $projectName.",
                failed = false,
            )
            ACTION_FAILED -> finishTask(
                title = "Task needs attention",
                detail = intent?.getStringExtra(EXTRA_DETAIL) ?: "Mobile Harness could not finish the task.",
                failed = true,
            )
            ACTION_CANCELLED -> {
                RuntimeTaskController.stopAction = null
                releaseWakeLockSafely()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val taskId = currentTaskId ?: "fgs-service-task"
                acquiredWakeLockTaskId = taskId
                TaskSupervisor.getInstance(applicationContext).wakeLockManager.acquire(taskId)
                startSpecialUseForeground(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification("Working in $projectName", includeStop = canStop),
                )
            }
        }
        return START_NOT_STICKY
    }

    private fun releaseWakeLockSafely() {
        val supervisor = TaskSupervisor.getInstance(applicationContext)
        acquiredWakeLockTaskId?.let {
            supervisor.wakeLockManager.release(it)
            acquiredWakeLockTaskId = null
        }
        currentTaskId?.let {
            supervisor.wakeLockManager.release(it)
        }
    }

    private fun runningNotification(detail: String, includeStop: Boolean): android.app.Notification {
        val builder = NotificationCompat.Builder(this, RUNNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notificationTitle)
            .setContentText(detail)
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (includeStop) {
            val stopIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, RuntimeExecutionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Stop task", stopIntent)
        }
        return builder.build()
    }

    private fun finishTask(title: String, detail: String, failed: Boolean) {
        RuntimeTaskController.stopAction = null
        releaseWakeLockSafely()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(if (failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(RESULT_NOTIFICATION_ID, notification)
        stopSelf()
    }

    private fun startSpecialUseForeground(id: Int, notification: android.app.Notification) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            androidx.core.app.ServiceCompat.startForeground(
                this,
                id,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(id, notification)
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        RuntimeTaskController.stopAction = null
        releaseWakeLockSafely()
        serviceScope.cancel(java.util.concurrent.CancellationException("Service destroyed"))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.jarves.mh.START_RUNTIME"
        const val ACTION_STOP = "com.jarves.mh.STOP_RUNTIME"
        const val ACTION_PROGRESS = "com.jarves.mh.PROGRESS_RUNTIME"
        const val ACTION_COMPLETE = "com.jarves.mh.COMPLETE_RUNTIME"
        const val ACTION_FAILED = "com.jarves.mh.FAIL_RUNTIME"
        const val ACTION_CANCELLED = "com.jarves.mh.CANCEL_RUNTIME"
        const val EXTRA_PROJECT_NAME = "project_name"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CAN_STOP = "can_stop"
        const val EXTRA_TASK_ID = "task_id"

        private const val RUNNING_CHANNEL_ID = "runtime"
        private const val RESULT_CHANNEL_ID = "task-results"
        private const val RUNNING_NOTIFICATION_ID = 41
        private const val RESULT_NOTIFICATION_ID = 42

        fun start(
            context: Context,
            taskId: String? = null,
            projectName: String,
            detail: String? = null,
            canStop: Boolean = true
        ) {
            runCatching {
                val intent = Intent(context, RuntimeExecutionService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_PROJECT_NAME, projectName)
                    if (taskId != null) putExtra(EXTRA_TASK_ID, taskId)
                    if (detail != null) putExtra(EXTRA_DETAIL, detail)
                    putExtra(EXTRA_CAN_STOP, canStop)
                }
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun updateProgress(context: Context, detail: String, projectName: String? = null) {
            runCatching {
                val intent = Intent(context, RuntimeExecutionService::class.java).apply {
                    action = ACTION_PROGRESS
                    putExtra(EXTRA_DETAIL, detail)
                    if (projectName != null) putExtra(EXTRA_PROJECT_NAME, projectName)
                }
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun finish(context: Context, title: String, detail: String, failed: Boolean, projectName: String? = null) {
            runCatching {
                ensureNotificationChannels(context)
                val manager = context.getSystemService(NotificationManager::class.java)
                val openAppIntent = PendingIntent.getActivity(
                    context,
                    1,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(detail)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                    .setContentIntent(openAppIntent)
                    .setAutoCancel(true)
                    .setCategory(if (failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
                manager?.notify(RESULT_NOTIFICATION_ID, notification)
                context.stopService(Intent(context, RuntimeExecutionService::class.java))
            }
        }

        fun cancel(context: Context) {
            runCatching {
                context.stopService(Intent(context, RuntimeExecutionService::class.java))
            }
        }

        fun ensureNotificationChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(RUNNING_CHANNEL_ID, "Running coding tasks", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows progress while Mobile Harness is working in the background"
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(RESULT_CHANNEL_ID, "Task results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Notifies you when a coding task finishes or needs attention"
                },
            )
        }
    }
}
