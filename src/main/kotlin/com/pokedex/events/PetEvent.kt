package com.pokedex.events

/**
 * Everything in the IDE that a pet can react to.
 *
 * [priority] resolves collisions when a reaction is already playing: a higher
 * value interrupts a lower one, and a lower one is dropped.
 */
sealed interface PetEvent {
    val priority: Int

    // Build
    data object BuildStarted : PetEvent { override val priority = 5 }
    data object BuildSucceeded : PetEvent { override val priority = 7 }
    data class BuildFailed(val errors: Int) : PetEvent { override val priority = 9 }

    // Tests
    data class TestsFinished(val passed: Int, val failed: Int) : PetEvent {
        override val priority = 8
        val allGreen: Boolean get() = failed == 0 && passed > 0
    }

    // Run and debug
    data object RunStarted : PetEvent { override val priority = 5 }
    data class ProcessExited(val exitCode: Int) : PetEvent { override val priority = 6 }
    data object BreakpointHit : PetEvent { override val priority = 6 }

    // Live code analysis
    /** The analyser finished and the open file has this many errors. */
    data class SyntaxErrors(val count: Int) : PetEvent { override val priority = 6 }
    /** The open file went from having errors to none. */
    data object FileClean : PetEvent { override val priority = 6 }

    // Version control
    data class CommitSucceeded(val files: Int) : PetEvent { override val priority = 8 }

    // Pomodoro
    data object PomodoroStarted : PetEvent { override val priority = 5 }
    data object PomodoroFinished : PetEvent { override val priority = 10 }
    data object BreakFinished : PetEvent { override val priority = 10 }

    // User activity
    data class TypingBurst(val charsPerMinute: Int) : PetEvent { override val priority = 2 }
    data object UserIdle : PetEvent { override val priority = 1 }
    data object UserReturned : PetEvent { override val priority = 3 }

    // Direct interaction
    /** A non-null id restricts the reaction to that single pet. */
    data class Petted(val petId: String? = null) : PetEvent { override val priority = 4 }

    // Music
    data class MusicStarted(val track: String?, val artist: String?) : PetEvent {
        override val priority = 4
    }
    data object MusicStopped : PetEvent { override val priority = 3 }
}
