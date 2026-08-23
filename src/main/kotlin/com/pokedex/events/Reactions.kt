package com.pokedex.events

import com.pokedex.PokedexBundle
import com.pokedex.world.Personality

/**
 * Maps events to visible behaviour.
 *
 * This is the only place where an event's animation and dialogue are decided,
 * which makes it the file to edit when tuning how the pets feel. Wording lives
 * in the message bundle rather than here, so a new language needs no code
 * change.
 */
object Reactions {

    data class Reaction(
        val animation: String,
        val messages: List<String> = emptyList(),
        val durationMs: Long = 3000,
        val bubbleMs: Long = 3000,
    )

    /**
     * Greeting and reply used when two pets meet.
     *
     * Pairs are read from the bundle so both halves of the exchange stay in
     * the same language.
     */
    val GREETING_PAIRS: List<Pair<String, String>>
        get() {
            val greetings = PokedexBundle.messages("greet")
            return greetings.mapIndexed { i, greeting ->
                greeting to PokedexBundle.message("greet.reply.${i + 1}")
            }
        }

    private fun lines(key: String, p: Personality, vararg params: Any) =
        PokedexBundle.messages(key, variant = p.name, params = params)

    fun forEvent(e: PetEvent, p: Personality = Personality.CHEERFUL): Reaction? = when (e) {

        // Build
        is PetEvent.BuildStarted -> Reaction(
            "idle", lines("react.build.started", p), 1500,
        )

        is PetEvent.BuildSucceeded -> Reaction(
            "happy", lines("react.build.ok", p), 3000,
        )

        is PetEvent.BuildFailed -> Reaction(
            "scared",
            when (e.errors) {
                0 -> lines("react.build.fail.none", p)
                1 -> lines("react.build.fail.one", p)
                else -> lines("react.build.fail.many", p, e.errors)
            },
            4000,
        )

        // Tests
        is PetEvent.TestsFinished -> if (e.allGreen) {
            Reaction("happy", lines("react.tests.ok", p, e.passed), 4000)
        } else {
            Reaction("sad", lines("react.tests.fail", p, e.failed), 4000)
        }

        // Run and debug
        is PetEvent.RunStarted -> Reaction("idle", lines("react.run.started", p), 1500)

        is PetEvent.ProcessExited -> if (e.exitCode == 0) {
            Reaction("happy", lines("react.exit.ok", p), 2500)
        } else {
            Reaction("sad", lines("react.exit.fail", p, e.exitCode), 3000)
        }

        is PetEvent.BreakpointHit -> Reaction(
            "surprised", lines("react.breakpoint", p), 2500,
        )

        // Live code analysis
        is PetEvent.SyntaxErrors -> when {
            e.count >= 10 -> Reaction("scared", lines("react.syntax.many", p, e.count), 2500)
            e.count >= 3 -> Reaction("sad", lines("react.syntax.some", p, e.count), 2200)
            else -> Reaction("sad", lines("react.syntax.few", p, e.count), 1800)
        }

        is PetEvent.FileClean -> Reaction("happy", lines("react.clean", p), 2000)

        // Version control
        is PetEvent.CommitSucceeded -> Reaction(
            "happy", lines("react.commit", p, e.files), 3500,
        )

        // Pomodoro
        is PetEvent.PomodoroStarted -> Reaction(
            "idle", lines("react.pomodoro.start", p), 2000,
        )

        is PetEvent.PomodoroFinished -> Reaction(
            "dance", lines("react.pomodoro.end", p), 8000, 7000,
        )

        is PetEvent.BreakFinished -> Reaction(
            "surprised", lines("react.break.end", p), 4000,
        )

        // User activity
        is PetEvent.TypingBurst -> Reaction(
            "happy",
            if (e.charsPerMinute > 400) lines("react.typing", p) else emptyList(),
            2000,
        )

        is PetEvent.UserIdle -> Reaction(
            "sleep", lines("react.idle", p), Long.MAX_VALUE, 4000,
        )

        is PetEvent.UserReturned -> Reaction(
            "happy", lines("react.returned", p), 2500,
        )

        is PetEvent.Petted -> Reaction("happy", lines("react.pet", p), 2000)

        // Music
        is PetEvent.MusicStarted -> Reaction(
            "dance",
            buildList {
                val track = e.track
                val artist = e.artist
                when {
                    track != null && artist != null ->
                        addAll(PokedexBundle.messages("react.music.trackArtist", params = arrayOf(track, artist)))
                    track != null ->
                        addAll(PokedexBundle.messages("react.music.track", params = arrayOf(track)))
                }
                addAll(PokedexBundle.messages("react.music.generic"))
            },
            6000, 5000,
        )

        is PetEvent.MusicStopped -> Reaction("idle", durationMs = 500)
    }
}
