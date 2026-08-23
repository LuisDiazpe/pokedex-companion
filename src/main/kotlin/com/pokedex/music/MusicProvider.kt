package com.pokedex.music

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo

data class NowPlaying(val track: String?, val artist: String?)

interface MusicProvider {
    /** null = no hay nada sonando (o no lo pudimos saber). */
    fun poll(): NowPlaying?

    companion object {
        /**
         * Defaults to the no-op provider. Music reactions are opt-in because
         * every platform implementation shells out to an external process.
         * Switch this to [detect] to enable them.
         */
        fun default(): MusicProvider = Noop

        fun detect(): MusicProvider = when {
            SystemInfo.isLinux -> LinuxMpris
            SystemInfo.isMac -> MacOsaScript
            SystemInfo.isWindows -> WindowsSmtc
            else -> Noop
        }
    }

    object Noop : MusicProvider {
        override fun poll(): NowPlaying? = null
    }
}

private val LOG = Logger.getInstance("com.pokedex.music")

/** Runs a command and returns stdout, or null when it fails. */
private fun run(vararg cmd: String, timeoutMs: Int = 2000): String? = try {
    val output = ExecUtil.execAndGetOutput(GeneralCommandLine(*cmd), timeoutMs)
    if (output.exitCode == 0) output.stdout.trim().ifEmpty { null } else null
} catch (t: Throwable) {
    LOG.debug("Command failed: ${cmd.joinToString(" ")}", t)
    null
}

/** Linux, through MPRIS over D-Bus. Requires `playerctl` on the PATH. */
object LinuxMpris : MusicProvider {
    override fun poll(): NowPlaying? {
        val status = run("playerctl", "status") ?: return null
        if (!status.equals("Playing", ignoreCase = true)) return null
        return NowPlaying(
            track = run("playerctl", "metadata", "title"),
            artist = run("playerctl", "metadata", "artist"),
        )
    }
}

/** macOS, through AppleScript against Spotify and Music. */
object MacOsaScript : MusicProvider {

    private fun query(app: String): NowPlaying? {
        val script = """
            if application "$app" is running then
              tell application "$app"
                if player state is playing then
                  return (name of current track) & "|" & (artist of current track)
                end if
              end tell
            end if
            return ""
        """.trimIndent()

        val out = run("osascript", "-e", script) ?: return null
        if (out.isBlank()) return null
        val parts = out.split("|")
        return NowPlaying(
            track = parts.getOrNull(0)?.trim()?.ifEmpty { null },
            artist = parts.getOrNull(1)?.trim()?.ifEmpty { null },
        )
    }

    override fun poll(): NowPlaying? = query("Spotify") ?: query("Music")
}

/**
 * Windows, through the system media transport controls exposed by WinRT.
 *
 * Covers any application that reports to the OS, including browsers. Each
 * call costs a few hundred milliseconds, which is why polling is infrequent.
 */
object WindowsSmtc : MusicProvider {

    private val script = """
        ${'$'}ErrorActionPreference = 'SilentlyContinue'
        Add-Type -AssemblyName System.Runtime.WindowsRuntime
        ${'$'}await = [System.WindowsRuntimeSystemExtensions].GetMethods() |
            Where-Object { ${'$'}_.Name -eq 'GetAwaiter' -and ${'$'}_.GetParameters().Count -eq 1 } |
            Select-Object -First 1
        ${'$'}mgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]
        ${'$'}op = ${'$'}mgrType::RequestAsync()
        ${'$'}mgr = ${'$'}await.MakeGenericMethod(${'$'}mgrType).Invoke(${'$'}null, @(${'$'}op)).GetResult()
        ${'$'}session = ${'$'}mgr.GetCurrentSession()
        if (${'$'}session -eq ${'$'}null) { exit 0 }
        if (${'$'}session.GetPlaybackInfo().PlaybackStatus -ne 'Playing') { exit 0 }
        ${'$'}propType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType=WindowsRuntime]
        ${'$'}pop = ${'$'}session.TryGetMediaPropertiesAsync()
        ${'$'}props = ${'$'}await.MakeGenericMethod(${'$'}propType).Invoke(${'$'}null, @(${'$'}pop)).GetResult()
        Write-Output "${'$'}(${'$'}props.Title)|${'$'}(${'$'}props.Artist)"
    """.trimIndent()

    override fun poll(): NowPlaying? {
        val out = run("powershell", "-NoProfile", "-NonInteractive", "-Command", script, timeoutMs = 4000)
            ?: return null
        if (out.isBlank()) return null
        val parts = out.split("|")
        return NowPlaying(
            track = parts.getOrNull(0)?.trim()?.ifEmpty { null },
            artist = parts.getOrNull(1)?.trim()?.ifEmpty { null },
        )
    }
}
