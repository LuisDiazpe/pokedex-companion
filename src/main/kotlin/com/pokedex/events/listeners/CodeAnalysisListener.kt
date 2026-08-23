package com.pokedex.events.listeners

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.pokedex.events.PetEvent
import com.pokedex.events.PetEventBus

/**
 * Reports the error count of the file currently open in the editor.
 *
 * This relies on `DaemonCodeAnalyzerImpl`, which is internal platform API and
 * may change between releases. Failures are swallowed so that the rest of the
 * plugin keeps working if it ever stops resolving.
 */
class CodeAnalysisListener(private val project: Project) : DaemonCodeAnalyzer.DaemonListener {

    private var lastCount = -1

    override fun daemonFinished() {
        val count = try {
            ReadAction.compute<Int, Throwable> {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    ?: return@compute -1
                DaemonCodeAnalyzerImpl
                    .getHighlights(editor.document, HighlightSeverity.ERROR, project)
                    .size
            }
        } catch (t: Throwable) {
            -1
        }

        if (count < 0 || count == lastCount) return

        val bus = project.service<PetEventBus>()
        when {
            count == 0 && lastCount > 0 -> bus.emit(PetEvent.FileClean)
            count > 0 -> bus.emit(PetEvent.SyntaxErrors(count))
        }
        lastCount = count
    }
}
