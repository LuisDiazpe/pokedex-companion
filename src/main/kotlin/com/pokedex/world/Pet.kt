package com.pokedex.world

import kotlin.math.abs
import kotlin.random.Random

/**
 * A single pet instance: position, physics and behaviour state machine.
 *
 * This class has no dependency on Swing or the IntelliJ Platform, so the
 * simulation can be exercised in plain unit tests.
 */
class Pet(
    val id: String,
    var name: String,
    var packId: String,
    /** Rendered edge length in device pixels. Physics use the same value. */
    var size: Int = 32,
    val personality: Personality = Personality.random(),
) {
    var pos: Vec2 = Vec2(0f, 0f)
    var vel: Vec2 = Vec2(0f, 0f)
    var facingRight: Boolean = true

    var state: PetState = PetState.Idle
        private set

    private var stateEndsAtMs: Long = 0L

    /** Priority of the reaction currently playing; 0 when none. */
    var activeReactionPriority: Int = 0
        private set

    /** True while the user drags this pet with the mouse. */
    var isHeld: Boolean = false
        private set

    var bubble: Bubble? = null

    /** Vertical slot for the bubble, so two nearby pets do not overlap. */
    var bubbleTier: Int = 0

    var animTime: Float = 0f
        private set

    private var lastAnimation: String = "idle"

    /** Timestamp of the last greeting, used to rate limit encounters. */
    var lastGreetMs: Long = 0L

    private var pendingText: String? = null
    private var pendingAtMs: Long = 0L
    private var pendingDurationMs: Long = 0L
    private var pendingTier: Int = 0

    private val walkSpeed get() = 26f * personality.speedMul
    private val runSpeed get() = 70f * personality.speedMul
    private val gravity = 460f
    private val jumpImpulse = 175f

    // --- state transitions -------------------------------------------------

    fun setState(newState: PetState, durationMs: Long, now: Long) {
        state = newState
        stateEndsAtMs = if (durationMs == Long.MAX_VALUE) Long.MAX_VALUE else now + durationMs

        when (newState) {
            // Moving recomputes velocity every tick from its target.
            is PetState.Moving -> {}
            is PetState.Jump -> {
                vel = Vec2(newState.dirX * walkSpeed * 1.5f, -jumpImpulse)
                facingRight = newState.dirX >= 0
            }
            else -> vel = Vec2(0f, 0f)
        }

        if (newState.animation != lastAnimation) {
            animTime = 0f
            lastAnimation = newState.animation
        }
    }

    fun startReaction(animation: String, priority: Int, durationMs: Long, now: Long) {
        if (isHeld) return
        activeReactionPriority = priority
        // Sleep is a real state rather than a timed reaction, otherwise
        // wakeUp() cannot detect it and the pet never gets up again.
        if (animation == "sleep") setState(PetState.Sleep, Long.MAX_VALUE, now)
        else setState(PetState.Reacting(animation), durationMs, now)
    }

    fun say(text: String, durationMs: Long, now: Long, tier: Int = 0) {
        bubble = Bubble(text, now, durationMs)
        bubbleTier = tier
    }

    /** Queues a line to be spoken after [delayMs], used for back-and-forth dialogue. */
    fun sayLater(text: String, delayMs: Long, durationMs: Long, now: Long, tier: Int = 0) {
        pendingText = text
        pendingAtMs = now + delayMs
        pendingDurationMs = durationMs
        pendingTier = tier
    }

    private fun flushPending(now: Long) {
        val text = pendingText ?: return
        if (now < pendingAtMs) return
        pendingText = null
        say(text, pendingDurationMs, now, pendingTier)
    }

    fun wakeUp(now: Long) {
        if (state is PetState.Sleep) {
            activeReactionPriority = 0
            setState(PetState.Idle, 500, now)
        }
    }

    // --- dragging ----------------------------------------------------------

    fun grab(now: Long) {
        isHeld = true
        vel = Vec2(0f, 0f)
        activeReactionPriority = 0
        setState(PetState.Held, Long.MAX_VALUE, now)
    }

    fun drop(now: Long, mode: MovementMode) {
        isHeld = false
        if (mode == MovementMode.GROUND) {
            setState(PetState.Falling, Long.MAX_VALUE, now)
            vel = Vec2(0f, 0f)
        } else {
            setState(PetState.Idle, 400, now)
        }
    }

    // --- simulation --------------------------------------------------------

    /**
     * Advances the pet by [dt] seconds.
     *
     * [bounds] must reflect the panel's current size; they are treated as
     * authoritative and re-applied after every integration step.
     */
    fun tick(dt: Float, now: Long, bounds: WorldBounds, mode: MovementMode) {
        if (!bounds.isUsable) return

        animTime += dt
        bubble?.let { if (!it.isAlive(now)) bubble = null }
        flushPending(now)

        // While dragged, only clamping runs. Letting the state machine keep
        // going would have it fight the mouse thirty times a second, which
        // shows up as the sprite flickering between states.
        if (isHeld) {
            pos = bounds.clamp(pos, size)
            return
        }

        if (now >= stateEndsAtMs) {
            activeReactionPriority = 0
            chooseNextState(now, bounds, mode)
        }

        when (val s = state) {
            is PetState.Moving -> {
                // Re-clamped in case the panel shrank since the target was set.
                val target = bounds.clamp(s.target, size)
                val delta = target - pos
                val dist = delta.length()
                if (dist < 2f) {
                    chooseNextState(now, bounds, mode)
                } else {
                    // Ease out near the destination; constant velocity reads
                    // as mechanical.
                    val ease = (dist / 26f).coerceIn(0.35f, 1f)
                    val speed = (if (s.running) runSpeed else walkSpeed) * ease
                    vel = Vec2(delta.x / dist * speed, delta.y / dist * speed)
                }
            }
            is PetState.Falling -> vel = Vec2(vel.x * 0.98f, vel.y + gravity * dt)
            is PetState.Jump -> vel = Vec2(vel.x, vel.y + gravity * dt)
            else -> vel = Vec2(0f, 0f)
        }

        if (abs(vel.x) > 1f) facingRight = vel.x > 0

        val next = pos + vel * dt
        val clamped = bounds.clamp(next, size)
        val hitWall = clamped.x != next.x
        val hitFloorOrCeiling = clamped.y != next.y
        pos = clamped

        // A clamp that changed the position is a collision. Resolving it here
        // prevents any state from pushing indefinitely against a boundary.
        if (hitWall || hitFloorOrCeiling) {
            val airborne = state is PetState.Falling || state is PetState.Jump
            when {
                airborne && hitFloorOrCeiling -> land(now, bounds)
                airborne && hitWall -> vel = Vec2(-vel.x * 0.5f, vel.y)
                state is PetState.Moving -> chooseNextState(now, bounds, mode)
                else -> vel = Vec2(0f, 0f)
            }
        }

        // Final guard: an airborne pet already resting on the floor lands,
        // which makes an unbounded descent unreachable.
        val airborne = state is PetState.Falling || state is PetState.Jump
        if (airborne && vel.y >= 0f && pos.y >= bounds.maxYFor(size) - 0.5f) {
            land(now, bounds)
        }
    }

    private fun land(now: Long, bounds: WorldBounds) {
        vel = Vec2(0f, 0f)
        pos = Vec2(pos.x, bounds.maxYFor(size))
        setState(PetState.Idle, randomDuration(600, 1500), now)
    }

    /**
     * Picks the next behaviour.
     *
     * Only targets inside the current bounds are generated, so a pet can
     * never be left walking toward a point outside the panel.
     */
    private fun chooseNextState(now: Long, bounds: WorldBounds, mode: MovementMode) {
        if (state is PetState.Sleep) {
            setState(PetState.Sleep, Long.MAX_VALUE, now)
            return
        }

        val maxX = bounds.maxXFor(size)
        val maxY = bounds.maxYFor(size)
        val roll = Random.nextInt(100)

        if (roll < personality.restBias) {
            if (Random.nextInt(100) < 35) facingRight = !facingRight
            setState(PetState.Idle, randomDuration(700, 2600), now)
            return
        }

        if (mode == MovementMode.GROUND && roll < personality.restBias + personality.jumpBias) {
            val dir = when {
                pos.x > maxX * 0.75f -> -1
                pos.x < maxX * 0.25f -> 1
                else -> if (Random.nextBoolean()) 1 else -1
            }
            setState(PetState.Jump(dir), 4_000, now)
            return
        }

        val targetX = Random.nextFloat() * (maxX - bounds.minX) + bounds.minX
        val targetY = when (mode) {
            MovementMode.GROUND -> maxY
            MovementMode.ROAM_2D -> Random.nextFloat() * (maxY - bounds.minY) + bounds.minY
        }

        val running = Random.nextInt(100) < personality.runBias
        // Generous timeout; the state normally ends on arrival instead.
        setState(PetState.Moving(Vec2(targetX, targetY), running), 12_000, now)
    }

    /** Sends the pet toward an explicit point (cursor following, encounters). */
    fun moveToward(target: Vec2, running: Boolean, now: Long) {
        if (isHeld) return
        setState(PetState.Moving(target, running), 6_000, now)
    }

    fun isMoving() = vel.length() > 1f

    /** True when the world may assign this pet something else to do. */
    fun isAvailable() = !isHeld && activeReactionPriority == 0 &&
        (state is PetState.Idle || state is PetState.Moving)

    private fun randomDuration(minMs: Int, maxMs: Int): Long =
        Random.nextInt(minMs, maxMs).toLong()
}
