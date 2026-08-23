package com.pokedex.ui

import com.intellij.openapi.application.ApplicationManager
import com.pokedex.PokedexBundle
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.pokedex.render.SpritePackRegistry
import com.pokedex.world.MovementMode
import java.io.File

@Service(Service.Level.APP)
@State(name = "PokedexSettings", storages = [Storage("pokedex.xml")])
class PetSettings : PersistentStateComponent<PetSettings.State> {

    data class State(
        /** Empty follows the IDE; otherwise an ISO code such as en or es. */
        var language: String = "",
        var petSize: Int = 32,
        var idleTimeoutMinutes: Int = 5,
        var movementMode: String = MovementMode.GROUND.name,
        var followMouse: Boolean = false,
        var musicReactionsEnabled: Boolean = false,
        var ideEventsEnabled: Boolean = true,
        var codeAnalysisEnabled: Boolean = true,
        var bubblesEnabled: Boolean = true,
        var spritePackDir: String = defaultPackDir(),
        var activePackId: String = "",
        var pomodoroFocusMinutes: Int = 25,
        var pomodoroBreakMinutes: Int = 5,
    )

    private var state = State()

    override fun getState() = state

    override fun loadState(s: State) {
        state = s
        PokedexBundle.forcedLanguage = s.language
    }

    override fun initializeComponent() {
        PokedexBundle.forcedLanguage = state.language
    }

    fun movementMode(): MovementMode = try {
        MovementMode.valueOf(state.movementMode)
    } catch (t: Throwable) {
        MovementMode.GROUND
    }

    companion object {
        fun getInstance(): PetSettings =
            ApplicationManager.getApplication().getService(PetSettings::class.java)

        fun defaultPackDir(): String =
            File(System.getProperty("user.home"), ".pokedex-sprites").absolutePath
    }
}

/** Settings page under Tools. */
class PetConfigurable : BoundConfigurable(PokedexBundle.message("settings.title")) {

    /** Language codes paired with their localised labels for the combo box. */
    private val languages = listOf(
        "" to PokedexBundle.message("settings.language.auto"),
        "en" to PokedexBundle.message("settings.language.en"),
        "es" to PokedexBundle.message("settings.language.es"),
    )

    override fun createPanel(): DialogPanel {
        val s = PetSettings.getInstance().state

        return panel {
            group(PokedexBundle.message("settings.group.language")) {
                row(PokedexBundle.message("settings.language")) {
                    comboBox(languages.map { it.second })
                        .bindItem(
                            { languages.firstOrNull { it.first == s.language }?.second },
                            { label ->
                                s.language = languages.firstOrNull { it.second == label }?.first ?: ""
                                // Applied immediately so the rest of the page
                                // and every bubble switch on the next lookup.
                                PokedexBundle.forcedLanguage = s.language
                            },
                        )
                }
            }

            group(PokedexBundle.message("settings.group.movement")) {
                row(PokedexBundle.message("settings.movement.mode")) {
                    comboBox(MovementMode.entries.toList())
                        .bindItem(
                            { PetSettings.getInstance().movementMode() },
                            { s.movementMode = (it ?: MovementMode.GROUND).name },
                        )
                    comment(PokedexBundle.message("settings.movement.comment"))
                }
                row {
                    checkBox(PokedexBundle.message("settings.movement.follow"))
                        .bindSelected(s::followMouse)
                }
            }

            group(PokedexBundle.message("settings.group.appearance")) {
                row(PokedexBundle.message("settings.appearance.size")) {
                    intTextField(16..128).bindIntText(s::petSize)
                }
                row(PokedexBundle.message("settings.appearance.dir")) {
                    textFieldWithBrowseButton().bindText(s::spritePackDir)
                }
                row(PokedexBundle.message("settings.appearance.pack")) {
                    val ids = listOf("") + SpritePackRegistry.all().map { it.id }
                    comboBox(ids).bindItem({ s.activePackId }, { s.activePackId = it ?: "" })
                    comment(PokedexBundle.message("settings.appearance.packComment"))
                }
                row {
                    checkBox(PokedexBundle.message("settings.appearance.bubbles"))
                        .bindSelected(s::bubblesEnabled)
                }
            }

            group(PokedexBundle.message("settings.group.reactions")) {
                row {
                    checkBox(PokedexBundle.message("settings.reactions.ide"))
                        .bindSelected(s::ideEventsEnabled)
                }
                row {
                    checkBox(PokedexBundle.message("settings.reactions.code"))
                        .bindSelected(s::codeAnalysisEnabled)
                }
                row {
                    checkBox(PokedexBundle.message("settings.reactions.music"))
                        .bindSelected(s::musicReactionsEnabled)
                    comment(PokedexBundle.message("settings.reactions.musicComment"))
                }
                row(PokedexBundle.message("settings.reactions.idle")) {
                    intTextField(1..120).bindIntText(s::idleTimeoutMinutes)
                }
            }

            group(PokedexBundle.message("settings.group.pomodoro")) {
                row(PokedexBundle.message("settings.pomodoro.focus")) {
                    intTextField(1..120).bindIntText(s::pomodoroFocusMinutes)
                }
                row(PokedexBundle.message("settings.pomodoro.break")) {
                    intTextField(1..60).bindIntText(s::pomodoroBreakMinutes)
                }
            }
        }
    }
}
