package com.pokedex.world

import com.pokedex.events.PetEvent
import com.pokedex.events.Reactions
import kotlin.random.Random

/**
 * Holds every pet and drives the simulation.
 *
 * Like [Pet], this class stays free of Swing and IntelliJ Platform types so
 * the behaviour can be unit tested without launching an IDE.
 */
class PetWorld {

    val pets = mutableListOf<Pet>()

    /** Written by the panel on every frame. */
    var bounds: WorldBounds = WorldBounds.EMPTY

    var mode: MovementMode = MovementMode.GROUND

    private val greetCooldownMs = 18_000L
    private val chaseIntervalMs = 6_000L
    private var lastChaseCheckMs = 0L

    fun add(pet: Pet) {
        pet.pos = if (bounds.isUsable) {
            Vec2(
                Random.nextFloat() * bounds.maxXFor(pet.size),
                when (mode) {
                    MovementMode.GROUND -> bounds.maxYFor(pet.size)
                    MovementMode.ROAM_2D -> Random.nextFloat() * bounds.maxYFor(pet.size)
                },
            )
        } else Vec2(0f, 0f)
        pets += pet
    }

    fun remove(id: String) {
        pets.removeIf { it.id == id }
    }

    fun clear() {
        pets.clear()
    }

    fun tick(dt: Float, now: Long) {
        pets.forEach { it.tick(dt, now, bounds, mode) }
        maybeSeekCompany(now)
        checkEncounters(now)
    }

    /**
     * Occasionally sends an idle pet toward one of its neighbours.
     *
     * Without this nudge, encounters depend entirely on random paths
     * intersecting and almost never happen.
     */
    private fun maybeSeekCompany(now: Long) {
        if (pets.size < 2) return
        if (now - lastChaseCheckMs < chaseIntervalMs) return
        lastChaseCheckMs = now
        if (Random.nextInt(100) >= 30) return

        val seeker = pets.filter { it.isAvailable() }.randomOrNull() ?: return
        val other = pets.filter { it !== seeker && !it.isHeld }.randomOrNull() ?: return
        if (now - seeker.lastGreetMs < greetCooldownMs) return

        // Stop beside the other pet rather than on top of it.
        val side = if (other.pos.x > seeker.pos.x) -1 else 1
        val targetX = (other.pos.x + side * other.size * 0.8f)
            .coerceIn(bounds.minX, bounds.maxXFor(seeker.size))
        val targetY = when (mode) {
            MovementMode.GROUND -> bounds.maxYFor(seeker.size)
            MovementMode.ROAM_2D -> other.pos.y
        }
        seeker.moveToward(Vec2(targetX, targetY), running = Random.nextInt(100) < 30, now = now)
    }

    /** Two pets that come within range greet each other. */
    private fun checkEncounters(now: Long) {
        if (pets.size < 2) return

        for (i in pets.indices) {
            for (j in i + 1 until pets.size) {
                val a = pets[i]
                val b = pets[j]
                if (a.isHeld || b.isHeld) continue

                val threshold = (a.size + b.size) * 0.6f
                if (a.pos.distanceTo(b.pos) > threshold) continue
                if (now - a.lastGreetMs < greetCooldownMs) continue
                if (now - b.lastGreetMs < greetCooldownMs) continue

                a.lastGreetMs = now
                b.lastGreetMs = now

                val (greeting, reply) = Reactions.GREETING_PAIRS.random()

                // The reply is delayed so the exchange reads as a
                // conversation instead of both bubbles appearing at once.
                a.startReaction("happy", 4, 2000, now)
                a.say(greeting, 2400, now, tier = 0)

                b.startReaction("happy", 4, 3400, now)
                b.sayLater(reply, delayMs = 1000, durationMs = 2400, now = now, tier = 1)
            }
        }
    }

    /**
     * Turns an IDE event into a visible reaction.
     *
     * Event priority prevents animations from cutting each other short: a
     * failing build outranks an idle dance, while a keystroke burst is
     * discarded while a test result is still playing.
     */
    fun apply(event: PetEvent, now: Long) {
        // Some events address a single pet, such as the one that was clicked.
        val targetId = (event as? PetEvent.Petted)?.petId

        pets.forEach { pet ->
            if (targetId != null && pet.id != targetId) return@forEach
            if (event.priority < pet.activeReactionPriority) return@forEach

            val reaction = Reactions.forEvent(event, pet.personality) ?: return@forEach

            pet.wakeUp(now)
            pet.startReaction(reaction.animation, event.priority, reaction.durationMs, now)
            reaction.messages.randomOrNull()?.let { pet.say(it, reaction.bubbleMs, now) }
        }
    }
}
