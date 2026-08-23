package com.pokedex.world

import kotlin.math.hypot

/** Immutable 2D vector. */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    fun length() = hypot(x, y)
    fun distanceTo(o: Vec2) = (this - o).length()
}

/**
 * Playable area for the simulation.
 *
 * Instances are recomputed every frame from the panel's current size and are
 * never cached. Caching them is the classic source of pets drifting outside
 * the visible area after a resize, or falling toward a floor that no longer
 * exists once the tool window is collapsed and reopened.
 */
data class WorldBounds(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val groundY: Float,
) {
    val isUsable: Boolean get() = maxX > minX && groundY > minY

    fun maxXFor(size: Int) = (maxX - size).coerceAtLeast(minX)
    fun maxYFor(size: Int) = (groundY - size).coerceAtLeast(minY)

    fun clamp(p: Vec2, size: Int) = Vec2(
        p.x.coerceIn(minX, maxXFor(size)),
        p.y.coerceIn(minY, maxYFor(size)),
    )

    companion object {
        /** Used before the panel has been laid out; simulation is skipped. */
        val EMPTY = WorldBounds(0f, 0f, 0f, 0f)
    }
}

enum class MovementMode {
    /** Pets walk along the floor line. Gravity applies. */
    GROUND,

    /** Pets roam the full panel area. No gravity. */
    ROAM_2D,
}

/**
 * Behavioural profile assigned to each pet.
 *
 * Two pets sharing a sprite pack still feel distinct because their pacing,
 * energy and dialogue differ.
 */
enum class Personality(
    /** Multiplier applied to walking and running speed. */
    val speedMul: Float,
    /** Chance (0-100) of standing still when picking the next state. */
    val restBias: Int,
    /** Chance (0-100) of running rather than walking. */
    val runBias: Int,
    /** Chance (0-100) of jumping rather than walking. */
    val jumpBias: Int,
) {
    CHEERFUL(1.15f, 20, 18, 16),
    LAZY(0.65f, 58, 3, 4),
    HYPER(1.55f, 8, 42, 26),
    STOIC(0.95f, 38, 6, 7),
    ;

    companion object {
        fun random() = entries.random()
        fun byName(n: String) = entries.firstOrNull { it.name == n } ?: CHEERFUL
    }
}

sealed interface PetState {
    /** Sprite pack animation to play while in this state. */
    val animation: String

    data object Idle : PetState {
        override val animation = "idle"
    }

    /**
     * Travel toward a point.
     *
     * Both movement modes share this state; they differ only in how the
     * target is chosen, which keeps a single code path under the bounds
     * checks in [Pet.tick].
     */
    data class Moving(val target: Vec2, val running: Boolean) : PetState {
        override val animation = "walk"
    }

    data object Sleep : PetState {
        override val animation = "sleep"
    }

    /** Free fall. Always resolves on floor contact. */
    data object Falling : PetState {
        override val animation = "fall"
    }

    /** Parabolic hop; provides vertical motion in [MovementMode.GROUND]. */
    data class Jump(val dirX: Int) : PetState {
        override val animation = "fall"
    }

    /** Being dragged by the user. Physics are suspended. */
    data object Held : PetState {
        override val animation = "fall"
    }

    /** Temporary state driven by an IDE event. Expires on its own. */
    data class Reacting(override val animation: String) : PetState
}

/** Speech bubble rendered above a pet. */
data class Bubble(
    val text: String,
    val bornAtMs: Long,
    val durationMs: Long,
) {
    fun isAlive(now: Long) = now - bornAtMs < durationMs

    /** Opacity envelope: quick fade in, longer fade out. */
    fun alpha(now: Long): Float {
        val age = (now - bornAtMs).toFloat()
        val fadeIn = 150f
        val fadeOut = durationMs * 0.3f
        return when {
            age < fadeIn -> age / fadeIn
            age > durationMs - fadeOut -> ((durationMs - age) / fadeOut).coerceAtLeast(0f)
            else -> 1f
        }.coerceIn(0f, 1f)
    }
}
