package com.pokedex.events.listeners

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import com.pokedex.events.PetEvent
import com.pokedex.events.PetEventBus
import com.pokedex.ui.PetSettings

/**
 * Tracks typing activity and idleness.
 *
 * Started by the tool window and disposed along with it.
 */
@Service(Service.Level.PROJECT)
class ActivityTracker(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var charsInWindow = 0
    private var windowStartMs = System.currentTimeMillis()
    private var isIdle = false
    private var started = false

    fun start(parent: Disposable) {
        if (started) return
        started = true

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = onTyping(event.newLength)
            },
            parent,
        )

        scheduleIdleCheck()
    }

    private fun onTyping(chars: Int) {
        if (isIdle) {
            isIdle = false
            project.service<PetEventBus>().emit(PetEvent.UserReturned)
        }

        charsInWindow += chars
        val now = System.currentTimeMillis()
        val elapsed = now - windowStartMs

        // Rate is sampled over a rolling ten second window.
        if (elapsed >= 10_000) {
            val cpm = (charsInWindow * 60_000L / elapsed).toInt()
            if (cpm > 120) {
                project.service<PetEventBus>().emit(PetEvent.TypingBurst(cpm))
            }
            charsInWindow = 0
            windowStartMs = now
        }

        scheduleIdleCheck()
    }

    private fun scheduleIdleCheck() {
        alarm.cancelAllRequests()
        val timeoutMs = PetSettings.getInstance().state.idleTimeoutMinutes * 60_000L
        alarm.addRequest({
            isIdle = true
            project.service<PetEventBus>().emit(PetEvent.UserIdle)
        }, timeoutMs)
    }

    override fun dispose() {
        alarm.cancelAllRequests()
    }
}
