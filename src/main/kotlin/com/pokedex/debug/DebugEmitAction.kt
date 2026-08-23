package com.pokedex.debug

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.pokedex.PokedexBundle
import com.pokedex.events.PetEvent
import com.pokedex.events.PetEventBus

/**
 * Publishes synthetic events so every reaction can be exercised without
 * having to break a build or fail a test on purpose.
 *
 * Available under Tools > Pokedex.
 */
class DebugEmitAction : AnAction() {

    private val samples: List<Pair<String, PetEvent>>
        get() = listOf(
            "debug.buildOk" to PetEvent.BuildSucceeded,
            "debug.buildFail" to PetEvent.BuildFailed(3),
            "debug.testsPass" to PetEvent.TestsFinished(passed = 42, failed = 0),
            "debug.testsFail" to PetEvent.TestsFinished(passed = 38, failed = 4),
            "debug.breakpoint" to PetEvent.BreakpointHit,
            "debug.exit0" to PetEvent.ProcessExited(0),
            "debug.exit1" to PetEvent.ProcessExited(1),
            "debug.syntax5" to PetEvent.SyntaxErrors(5),
            "debug.syntax12" to PetEvent.SyntaxErrors(12),
            "debug.clean" to PetEvent.FileClean,
            "debug.commit" to PetEvent.CommitSucceeded(7),
            "debug.pomodoroEnd" to PetEvent.PomodoroFinished,
            "debug.breakEnd" to PetEvent.BreakFinished,
            "debug.typing" to PetEvent.TypingBurst(520),
            "debug.idle" to PetEvent.UserIdle,
            "debug.returned" to PetEvent.UserReturned,
            "debug.pet" to PetEvent.Petted(),
            "debug.music" to PetEvent.MusicStarted("Bulls On Parade", "Rage Against The Machine"),
            "debug.musicStop" to PetEvent.MusicStopped,
        ).map { (key, event) -> PokedexBundle.message(key) to event }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
        e.presentation.text = PokedexBundle.message("action.debug.text")
        e.presentation.description = PokedexBundle.message("action.debug.description")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val bus = project.service<PetEventBus>()

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(samples.map { it.first })
            .setTitle(PokedexBundle.message("debug.title"))
            .setItemChosenCallback { label ->
                samples.firstOrNull { it.first == label }?.let { bus.emit(it.second) }
            }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }
}
