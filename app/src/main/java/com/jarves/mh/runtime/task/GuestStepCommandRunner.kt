package com.jarves.mh.runtime.task

import android.content.Context
import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.runtime.RuntimeInstaller
import java.io.File

/** Runs checks with the same installed guest tools and workspace mount as the agent. */
class GuestStepCommandRunner(
    context: Context,
    private val bindProcess: (CanonicalTask, Process) -> Boolean,
) : StepCommandRunner {
    private val installer = RuntimeInstaller(context)

    override fun execute(command: String, workingDir: File, timeoutSeconds: Long): Pair<Int, String> =
        Pair(-1, "Guest verification requires an owning task")

    override fun execute(task: CanonicalTask, command: String, workingDir: File, timeoutSeconds: Long): Pair<Int, String> {
        val runner = ControlledStepCommandRunner(onProcessStarted = { process ->
            check(bindProcess(task, process)) { "Verification process no longer belongs to an active task" }
        }) { checkedCommand, workspace ->
            val runtime = installer.installedRuntime()
            installer.process(
                proot = runtime.proot,
                rootfs = runtime.rootfs,
                workspace = workspace,
                environment = emptyMap(),
                guestCommand = listOf("/usr/bin/bash", "-lc", checkedCommand),
                guestWorkspacePath = "/workspace/${task.projectSlug}",
                // Every launch now drains output through a bounded pipe capture.
            )
        }
        return runner.execute(command, workingDir, timeoutSeconds)
    }
}
