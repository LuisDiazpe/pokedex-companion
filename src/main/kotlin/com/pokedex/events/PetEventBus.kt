package com.pokedex.events

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Central dispatch point. IDE listeners publish here and the rendering panel
 * subscribes, which keeps the listeners free of any UI dependency.
 */
@Service(Service.Level.PROJECT)
class PetEventBus {

    private val subscribers = CopyOnWriteArrayList<(PetEvent) -> Unit>()

    /**
     * Subscribers are removed automatically when [parent] is disposed, for
     * example when the tool window closes, so no listeners are leaked.
     */
    fun subscribe(parent: Disposable, handler: (PetEvent) -> Unit) {
        subscribers += handler
        Disposer.register(parent) { subscribers -= handler }
    }

    fun emit(event: PetEvent) {
        thisLogger().debug("PetEvent: $event")
        subscribers.forEach { sub ->
            try {
                sub(event)
            } catch (t: Throwable) {
                thisLogger().warn("Subscriber failed while handling $event", t)
            }
        }
    }
}
