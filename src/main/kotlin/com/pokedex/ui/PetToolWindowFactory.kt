package com.pokedex.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.pokedex.PokedexBundle
import com.intellij.util.ui.JBUI
import com.pokedex.events.PomodoroService
import com.pokedex.events.listeners.ActivityTracker
import com.pokedex.music.MusicPoller
import com.pokedex.render.PetPanel
import com.pokedex.render.SpritePackRegistry
import com.pokedex.world.Personality
import com.pokedex.world.Pet
import java.io.File

/**
 * Entry point for the tool window.
 *
 * Everything created here is registered against the tool window's disposable,
 * so closing it tears down the render loop, listeners and pollers.
 */
class PetToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val settings = PetSettings.getInstance().state

        // Bundled packs plus anything the user dropped in their directory.
        SpritePackRegistry.reload(File(settings.spritePackDir))

        // Simulation, rendering and the frame timer.
        val panel = PetPanel(project)
        Disposer.register(toolWindow.disposable, panel)

        // Starting pet. Falls back to the placeholder if no pack resolves.
        val packId = settings.activePackId.ifEmpty {
            SpritePackRegistry.all().firstOrNull()?.id ?: "placeholder"
        }
        panel.world.add(
            Pet(
                id = "pet-1",
                name = PokedexBundle.message("pet.defaultName"),
                packId = packId,
                size = JBUI.scale(settings.petSize),
                personality = Personality.random(),
            )
        )

        // Typing and idle detection.
        project.service<ActivityTracker>().start(toolWindow.disposable)

        // Pomodoro service; started on demand from the Tools menu.
        project.service<PomodoroService>()

        // Music polling; inert until enabled in settings.
        val poller = MusicPoller(project)
        Disposer.register(toolWindow.disposable, poller)
        poller.start()

        // Title bar actions for the most frequent operations.
        toolWindow.setTitleActions(
            listOf(SpawnPetAction(), ToggleMovementModeAction(), ShuffleAction())
        )

        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}
