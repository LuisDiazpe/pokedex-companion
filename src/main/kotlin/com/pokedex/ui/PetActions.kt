package com.pokedex.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.pokedex.events.PomodoroService
import com.pokedex.render.PetPanel
import com.pokedex.render.SpritePackRegistry
import com.intellij.util.ui.JBUI
import com.pokedex.world.Pet
import java.io.File
import com.intellij.icons.AllIcons
import com.pokedex.PokedexBundle
import com.pokedex.world.MovementMode

/** Tool window id. Kept ASCII and language independent. */
const val TOOL_WINDOW_ID = "Pokedex"

/** Locates the live panel inside the tool window. */
internal fun AnActionEvent.petPanel(): PetPanel? {
    val project = this.project ?: return null
    val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return null
    return tw.contentManager.contents.firstNotNullOfOrNull { it.component as? PetPanel }
}

abstract class PetAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}

/** Adds a pet through the search-and-name dialog. */
class SpawnPetAction : PetAction() {

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = PokedexBundle.message("action.spawn.text")
        e.presentation.description = PokedexBundle.message("action.spawn.description")
        e.presentation.icon = AllIcons.General.Add
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = e.petPanel() ?: return

        val dialog = SpawnPetDialog(project)
        if (!dialog.showAndGet()) return

        val pack = dialog.chosenPack ?: return
        panel.world.add(
            Pet(
                id = "pet-${System.nanoTime()}",
                name = dialog.chosenName,
                packId = pack.id,
                size = JBUI.scale(PetSettings.getInstance().state.petSize),
                personality = dialog.chosenPersonality,
            )
        )
        panel.repaint()
    }
}

class RemoveLastPetAction : PetAction() {

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = PokedexBundle.message("action.remove.text")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val panel = e.petPanel() ?: return
        panel.world.pets.lastOrNull()?.let { panel.world.remove(it.id) }
        panel.repaint()
    }
}

/** Rescans the sprite pack directory without restarting the IDE. */
class ReloadSpritesAction : PetAction() {

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = PokedexBundle.message("action.reloadSprites.text")
        e.presentation.description = PokedexBundle.message("action.reloadSprites.description")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val dir = File(PetSettings.getInstance().state.spritePackDir)
        SpritePackRegistry.reload(dir)
        val found = SpritePackRegistry.all()

        val msg = if (found.isEmpty()) {
            PokedexBundle.message("sprites.none", dir.absolutePath)
        } else {
            PokedexBundle.message("sprites.loaded") + "\n" +
                found.joinToString("\n") { "- ${it.displayName} (${it.frameSize}px)" }
        }
        Messages.showInfoMessage(e.project, msg, PokedexBundle.message("sprites.title"))
    }
}

/** Opens the sprite pack directory, creating it with a readme if absent. */
class OpenSpritesFolderAction : PetAction() {

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = PokedexBundle.message("action.openSprites.text")
        e.presentation.description = PokedexBundle.message("action.openSprites.description")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val dir = File(PetSettings.getInstance().state.spritePackDir)
        if (!dir.exists()) {
            dir.mkdirs()
            File(dir, "README.txt").writeText(PokedexBundle.message("sprites.readme"))
        }
        BrowserUtil.browse(dir)
    }
}

class TogglePomodoroAction : PetAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val pomodoro = project.service<PomodoroService>()
        val settings = PetSettings.getInstance().state
        pomodoro.focusMinutes = settings.pomodoroFocusMinutes
        pomodoro.breakMinutes = settings.pomodoroBreakMinutes
        pomodoro.toggle()
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val running = e.project?.service<PomodoroService>()?.isRunning ?: false
        e.presentation.text = PokedexBundle.message(
            if (running) "action.pomodoro.stop" else "action.pomodoro.start"
        )
    }
}


/** Switches between floor and free-roam movement from the tool window. */
class ToggleMovementModeAction : PetAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val settings = PetSettings.getInstance().state
        val next = if (PetSettings.getInstance().movementMode() == MovementMode.GROUND)
            MovementMode.ROAM_2D else MovementMode.GROUND
        settings.movementMode = next.name
        e.petPanel()?.repaint()
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val ground = PetSettings.getInstance().movementMode() == MovementMode.GROUND
        e.presentation.text = PokedexBundle.message(
            if (ground) "action.mode.ground" else "action.mode.roam"
        )
        e.presentation.icon = if (ground) AllIcons.Actions.MoveDown else AllIcons.Actions.Expandall
    }
}

/** Wakes every pet and redistributes them along the floor. */
class ShuffleAction : PetAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val panel = e.petPanel() ?: return
        val now = System.currentTimeMillis()
        panel.world.pets.forEach { pet ->
            pet.wakeUp(now)
            pet.pos = com.pokedex.world.Vec2(
                (Math.random() * panel.world.bounds.maxXFor(pet.size)).toFloat(),
                panel.world.bounds.maxYFor(pet.size),
            )
        }
        panel.repaint()
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = PokedexBundle.message("action.shuffle.text")
        e.presentation.icon = AllIcons.Actions.Refresh
    }
}
