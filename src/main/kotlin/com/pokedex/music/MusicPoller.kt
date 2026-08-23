package com.pokedex.music

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.pokedex.events.PetEvent
import com.pokedex.events.PetEventBus
import com.pokedex.ui.PetSettings
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Polls the active media player on a background executor.
 *
 * This never runs on the EDT because [MusicProvider.poll] spawns external
 * processes. Events are emitted only when the track actually changes.
 */
class MusicPoller(private val project: Project) : Disposable {

    private var future: ScheduledFuture<*>? = null
    private var last: NowPlaying? = null

    fun start() {
        if (future != null) return
        future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            ::tick, 3, 3, TimeUnit.SECONDS,
        )
    }

    private fun tick() {
        if (!PetSettings.getInstance().state.musicReactionsEnabled) return

        val provider = MusicProvider.default()
        val current = try {
            provider.poll()
        } catch (t: Throwable) {
            null
        }

        val bus = project.service<PetEventBus>()
        when {
            current != null && current != last ->
                bus.emit(PetEvent.MusicStarted(current.track, current.artist))
            current == null && last != null ->
                bus.emit(PetEvent.MusicStopped)
        }
        last = current
    }

    override fun dispose() {
        future?.cancel(true)
        future = null
    }
}
