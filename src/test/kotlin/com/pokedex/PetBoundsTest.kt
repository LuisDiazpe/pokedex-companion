package com.pokedex

import com.pokedex.world.MovementMode
import com.pokedex.world.Personality
import com.pokedex.world.Pet
import com.pokedex.world.PetState
import com.pokedex.world.Vec2
import com.pokedex.world.WorldBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounds invariants for the simulation.
 *
 * These run without an IDE instance because the world model has no Swing or
 * platform dependencies.
 */
class PetBoundsTest {

    private fun pet(p: Personality = Personality.CHEERFUL) =
        Pet(id = "t", name = "t", packId = "t", size = 32, personality = p)

    @Test
    fun `stays inside bounds over ten thousand frames on the floor`() {
        val bounds = WorldBounds(0f, 200f, 0f, 100f)
        val pet = pet().apply { pos = Vec2(50f, 50f) }

        var now = 0L
        repeat(10_000) {
            now += 33
            pet.tick(0.033f, now, bounds, MovementMode.GROUND)
            assertTrue("x=${pet.pos.x}", pet.pos.x in 0f..168f)
            assertTrue("y=${pet.pos.y}", pet.pos.y in 0f..68f)
        }
    }

    @Test
    fun `stays inside bounds while free roaming`() {
        val bounds = WorldBounds(0f, 200f, 0f, 120f)
        val pet = pet(Personality.HYPER).apply { pos = Vec2(50f, 50f) }

        var now = 0L
        repeat(10_000) {
            now += 33
            pet.tick(0.033f, now, bounds, MovementMode.ROAM_2D)
            assertTrue("x=${pet.pos.x}", pet.pos.x in 0f..168f)
            assertTrue("y=${pet.pos.y}", pet.pos.y in 0f..88f)
        }
    }

    @Test
    fun `dropping a pet ends in a landing rather than an endless descent`() {
        val bounds = WorldBounds(0f, 200f, 0f, 100f)
        val pet = pet().apply { pos = Vec2(50f, 0f) }

        var now = 0L
        pet.drop(now, MovementMode.GROUND)
        repeat(200) {
            now += 33
            pet.tick(0.033f, now, bounds, MovementMode.GROUND)
        }
        assertTrue("still falling after six seconds", pet.state !is PetState.Falling)
        assertEquals(68f, pet.pos.y, 1f)
    }

    @Test
    fun `a shrinking panel pulls the pet down to the new floor`() {
        val pet = pet().apply { pos = Vec2(50f, 250f) }
        // The panel shrinks: the floor moves from 300 up to 80.
        val small = WorldBounds(0f, 200f, 0f, 80f)
        pet.tick(0.033f, 33L, small, MovementMode.GROUND)
        assertTrue("not pulled to the new floor: ${pet.pos.y}", pet.pos.y <= 48f)
    }

    @Test
    fun `physics leaves state untouched while a pet is held`() {
        val bounds = WorldBounds(0f, 200f, 0f, 100f)
        val pet = pet()
        var now = 0L
        pet.grab(now)

        repeat(100) {
            now += 33
            pet.pos = Vec2(80f, 10f)
            pet.tick(0.033f, now, bounds, MovementMode.GROUND)
            // Neither the state nor the position may drift.
            assertTrue("state changed while held", pet.state is PetState.Held)
            assertEquals(10f, pet.pos.y, 0.01f)
        }
    }

    @Test
    fun `dragging beyond the panel still clamps the pet inside`() {
        val bounds = WorldBounds(0f, 200f, 0f, 100f)
        val pet = pet()
        pet.grab(0L)
        pet.pos = Vec2(9999f, -9999f)
        pet.tick(0.033f, 33L, bounds, MovementMode.GROUND)
        assertTrue(pet.pos.x in 0f..168f)
        assertTrue(pet.pos.y in 0f..68f)
    }

    @Test
    fun `degenerate bounds are a no-op`() {
        val pet = pet().apply { pos = Vec2(5f, 5f) }
        // A zero-sized panel occurs while the tool window initialises.
        pet.tick(0.033f, 33L, WorldBounds.EMPTY, MovementMode.GROUND)
        assertEquals(Vec2(5f, 5f), pet.pos)
    }
}

/** Jumping introduces vertical motion without escaping the bounds. */
class PetJumpTest {

    private fun pet() = Pet(id = "j", name = "j", packId = "j", size = 32,
                           personality = Personality.HYPER)

    @Test
    fun `a jump rises, falls and lands`() {
        val bounds = WorldBounds(0f, 200f, 0f, 120f)
        val p = pet().apply { pos = Vec2(80f, 88f) }

        var now = 0L
        p.setState(PetState.Jump(1), 4000, now)

        var minY = p.pos.y
        repeat(120) {
            now += 33
            p.tick(0.033f, now, bounds, MovementMode.GROUND)
            minY = minOf(minY, p.pos.y)
            assertTrue("out of bounds: ${p.pos}", p.pos.y in 0f..88f && p.pos.x in 0f..168f)
        }
        assertTrue("never left the floor (minY=$minY)", minY < 78f)
        assertTrue("did not land: ${p.state}", p.state !is PetState.Jump)
        assertEquals(88f, p.pos.y, 1f)
    }

    @Test
    fun `jumping into a wall keeps the pet on screen`() {
        val bounds = WorldBounds(0f, 200f, 0f, 120f)
        val p = pet().apply { pos = Vec2(160f, 88f) }
        var now = 0L
        p.setState(PetState.Jump(1), 4000, now)
        repeat(150) {
            now += 33
            p.tick(0.033f, now, bounds, MovementMode.GROUND)
            assertTrue(p.pos.x in 0f..168f)
        }
    }
}
