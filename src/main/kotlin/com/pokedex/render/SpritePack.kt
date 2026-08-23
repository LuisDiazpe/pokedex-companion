package com.pokedex.render

import com.intellij.openapi.diagnostic.thisLogger
import java.awt.image.BufferedImage
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO

/**
 * A sprite pack is a directory of PNG files, one per animation.
 *
 * The format is deliberately convention driven so that adding artwork needs
 * no manifest:
 *
 *  - Each file holds its frames in a single horizontal strip.
 *  - The file name is the animation name: `idle.png`, `walk.png`, and so on.
 *  - Image height defines the frame size, so width divided by height is the
 *    frame count. A 128x32 `walk.png` is four 32x32 frames.
 *
 * Recognised animations are listed in [KNOWN]. Missing ones fall back to
 * `idle`, which means a pack containing only `idle.png` and `walk.png` is
 * already usable.
 *
 * An optional `pack.properties` sitting beside the images can override the
 * display name, grouping and per-animation timing:
 *
 * ```
 * name=Sprig
 * gen=bosque
 * fps.walk=8
 * loop.happy=false
 * ```
 */
class SpritePack(
    val id: String,
    val displayName: String,
    val frameSize: Int,
    /** Optional grouping shown as a filter in the spawn dialog. */
    val group: String? = null,
    private val animations: Map<String, Animation>,
) {

    /** Scaled first idle frame, used as a list icon in the spawn dialog. */
    fun thumbnail(size: Int = 24): javax.swing.Icon? {
        val first = animations["idle"]?.frames?.firstOrNull()
            ?: animations.values.firstOrNull()?.frames?.firstOrNull()
            ?: return null
        val scaled = first.getScaledInstance(size, size, java.awt.Image.SCALE_FAST)
        return javax.swing.ImageIcon(scaled)
    }


    class Animation(
        val frames: List<BufferedImage>,
        val fps: Int,
        val loop: Boolean,
    ) {
        fun frameAt(timeSec: Float): BufferedImage {
            if (frames.isEmpty()) error("Animation has no frames")
            val idx = (timeSec * fps).toInt()
            return if (loop) frames[idx % frames.size]
            else frames[idx.coerceAtMost(frames.size - 1)]
        }
    }

    fun has(name: String) = animations.containsKey(name)

    /** The requested animation, falling back to `idle`, or null if empty. */
    fun animation(name: String): Animation? = animations[name] ?: animations["idle"]

    companion object {
        val KNOWN = listOf(
            "idle", "walk", "sleep", "happy", "sad",
            "scared", "surprised", "dance", "fall",
        )

        private val DEFAULT_FPS = mapOf(
            "idle" to 4, "walk" to 8, "sleep" to 2, "happy" to 10,
            "sad" to 4, "scared" to 10, "surprised" to 8, "dance" to 10, "fall" to 6,
        )

        private val DEFAULT_LOOP = setOf("idle", "walk", "sleep", "dance", "fall")

        /** Loads a pack from a directory on disk. */
        fun loadFromDirectory(dir: File): SpritePack? {
            if (!dir.isDirectory) return null
            return loadPack(dir.name) { rel ->
                File(dir, rel).takeIf { it.isFile }?.inputStream()
            }
        }

        /**
         * Loads a pack shipped inside the plugin jar, so a fresh install has
         * usable artwork without the user sourcing any first.
         */
        fun loadBundled(packId: String): SpritePack? =
            loadPack(packId) { rel ->
                SpritePack::class.java.getResourceAsStream("/sprites/$packId/$rel")
            }

        /** Lista de packs empaquetados. El jar no permite listar carpetas. */
        fun bundledIds(): List<String> = try {
            SpritePack::class.java.getResourceAsStream("/sprites/index.txt")
                ?.bufferedReader()
                ?.use { it.readLines() }
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && !it.startsWith("#") }
                ?: emptyList()
        } catch (t: Throwable) {
            thisLogger().warn("Could not read /sprites/index.txt", t)
            emptyList()
        }

        /**
         * Shared loader. Disk and classpath packs differ only in how a stream
         * is opened, so parsing lives in one place.
         */
        private fun loadPack(id: String, open: (String) -> java.io.InputStream?): SpritePack? {
            val props = Properties().apply {
                open("pack.properties")?.use { load(it) }
            }

            val anims = mutableMapOf<String, Animation>()
            var frameSize = 32

            KNOWN.forEach { name ->
                val stream = open("$name.png") ?: return@forEach
                try {
                    val sheet = stream.use { ImageIO.read(it) } ?: return@forEach
                    val h = sheet.height
                    val count = (sheet.width / h).coerceAtLeast(1)
                    frameSize = h

                    val frames = (0 until count).map { i ->
                        sheet.getSubimage(i * h, 0, h, h)
                    }

                    anims[name] = Animation(
                        frames = frames,
                        fps = props.getProperty("fps.$name")?.toIntOrNull()
                            ?: DEFAULT_FPS[name] ?: 6,
                        loop = props.getProperty("loop.$name")?.toBooleanStrictOrNull()
                            ?: (name in DEFAULT_LOOP),
                    )
                } catch (t: Throwable) {
                    thisLogger().warn("Could not read $id/$name.png", t)
                }
            }

            if (anims.isEmpty()) return null

            return SpritePack(
                id = id,
                displayName = props.getProperty("name") ?: id,
                frameSize = frameSize,
                group = props.getProperty("gen")?.takeIf { it.isNotBlank() },
                animations = anims,
            )
        }

        /** Scans the user's sprite pack directory. */
        fun scan(root: File): List<SpritePack> =
            root.takeIf { it.isDirectory }
                ?.listFiles { f -> f.isDirectory }
                ?.mapNotNull { loadFromDirectory(it) }
                ?.sortedBy { it.displayName }
                ?: emptyList()
    }
}

/** In-memory registry of loaded packs, refreshable without restarting. */
object SpritePackRegistry {
    private val packs = mutableMapOf<String, SpritePack>()

    fun reload(root: File) {
        packs.clear()

        // Bundled packs first, so a fresh install already has artwork.
        SpritePack.bundledIds().forEach { id ->
            SpritePack.loadBundled(id)?.let { packs[it.id] = it }
        }
        val bundled = packs.size

        // User packs second; a matching id overrides the bundled pack.
        SpritePack.scan(root).forEach { packs[it.id] = it }

        thisLogger().info("Sprite packs loaded: $bundled bundled, ${packs.size} total")
    }

    fun bundledCount() = SpritePack.bundledIds().size

    fun get(id: String): SpritePack? = packs[id]
    fun all(): List<SpritePack> = packs.values.toList()
    fun isEmpty() = packs.isEmpty()
}
