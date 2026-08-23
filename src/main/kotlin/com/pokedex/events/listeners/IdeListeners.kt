package com.pokedex.events.listeners

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskManager
import com.intellij.task.ProjectTaskListener
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManagerListener
import com.pokedex.events.PetEvent
import com.pokedex.events.PetEventBus

/** Project build lifecycle. */
class PetBuildListener(private val project: Project) : ProjectTaskListener {

    override fun started(context: ProjectTaskContext) {
        project.service<PetEventBus>().emit(PetEvent.BuildStarted)
    }

    override fun finished(result: ProjectTaskManager.Result) {
        val bus = project.service<PetEventBus>()
        when {
            result.isAborted -> return
            result.hasErrors() -> bus.emit(PetEvent.BuildFailed(errors = 0))
            // An exact error count would require collecting ERROR-level
            // MessageEvents from BuildProgressListener, keyed by build id.
            else -> bus.emit(PetEvent.BuildSucceeded)
        }
    }
}

/** Test results from any framework built on the SM runner. */
class PetTestListener(private val project: Project) : SMTRunnerEventsAdapter() {

    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        val leaves = testsRoot.allTests.filter { it.isLeaf }
        project.service<PetEventBus>().emit(
            PetEvent.TestsFinished(
                passed = leaves.count { it.isPassed },
                failed = leaves.count { it.isDefect },
            )
        )
    }
}

/** Start and stop of any run configuration. */
class PetExecutionListener(private val project: Project) : ExecutionListener {

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        project.service<PetEventBus>().emit(PetEvent.RunStarted)
    }

    override fun processTerminated(
        executorId: String,
        env: ExecutionEnvironment,
        handler: ProcessHandler,
        exitCode: Int,
    ) {
        project.service<PetEventBus>().emit(PetEvent.ProcessExited(exitCode))
    }
}

/**
 * Debugger sessions.
 *
 * The platform exposes no global "breakpoint hit" topic, so a session
 * listener is attached as each debug process starts.
 */
class PetDebuggerListener(private val project: Project) : XDebuggerManagerListener {

    override fun processStarted(debugProcess: XDebugProcess) {
        debugProcess.session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() {
                project.service<PetEventBus>().emit(PetEvent.BreakpointHit)
            }
        })
    }
}
