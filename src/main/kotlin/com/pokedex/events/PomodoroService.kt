package com.pokedex.events

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm

/**
 * Minimal pomodoro timer. The pet announces each transition with a speech
 * bubble rather than a system notification.
 */
@Service(Service.Level.PROJECT)
class PomodoroService(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    var isRunning: Boolean = false
        private set

    var onBreak: Boolean = false
        private set

    var focusMinutes: Int = 25
    var breakMinutes: Int = 5

    fun toggle() = if (isRunning) stop() else start()

    fun start() {
        isRunning = true
        onBreak = false
        project.service<PetEventBus>().emit(PetEvent.PomodoroStarted)
        schedule(focusMinutes) { finishFocus() }
    }

    fun stop() {
        isRunning = false
        onBreak = false
        alarm.cancelAllRequests()
    }

    private fun finishFocus() {
        if (!isRunning) return
        onBreak = true
        project.service<PetEventBus>().emit(PetEvent.PomodoroFinished)
        schedule(breakMinutes) { finishBreak() }
    }

    private fun finishBreak() {
        if (!isRunning) return
        onBreak = false
        project.service<PetEventBus>().emit(PetEvent.BreakFinished)
        schedule(focusMinutes) { finishFocus() }
    }

    private fun schedule(minutes: Int, block: () -> Unit) {
        alarm.cancelAllRequests()
        alarm.addRequest(block, minutes * 60_000L)
    }

    override fun dispose() {
        alarm.cancelAllRequests()
    }
}
