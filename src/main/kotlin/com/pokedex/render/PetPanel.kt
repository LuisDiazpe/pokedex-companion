package com.pokedex.render

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.pokedex.events.PetEvent
import com.pokedex.events.PetEventBus
import com.pokedex.ui.PetSettings
import com.pokedex.world.MovementMode
import com.pokedex.world.Pet
import com.pokedex.world.PetState
import com.pokedex.world.PetWorld
import com.pokedex.world.Vec2
import com.pokedex.world.WorldBounds
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Point
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import javax.swing.Timer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class PetPanel(private val project: Project) : JPanel(), Disposable {

    val world = PetWorld()

    private val targetFps = 30
    private val timer = Timer(1000 / targetFps) { onFrame() }
    private var lastFrameNs = System.nanoTime()

    private var draggedPet: Pet? = null
    private var dragOffset = Vec2(0f, 0f)

    /** Pet under the cursor on press; only grabbed once a drag begins. */
    private var pressCandidate: Pet? = null
    private var pressPoint: Point? = null
    private val dragThreshold = JBUI.scale(4)

    /** Distance from the bottom edge to the floor line, in scaled pixels. */
    private val floorInset = 4

    init {
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(220), JBUI.scale(140))
        installMouse()

        project.service<PetEventBus>().subscribe(this) { event ->
            // Events arrive off the EDT; mutate the world on the EDT only.
            UIUtil.invokeLaterIfNeeded {
                if (PetSettings.getInstance().state.ideEventsEnabled) {
                    world.apply(event, System.currentTimeMillis())
                    repaint()
                }
            }
        }

        timer.isCoalesce = true
        timer.start()
    }

    // --- frame loop ---

    private fun onFrame() {
        // Skip work entirely while the tool window is hidden or collapsed.
        if (!isShowing || width <= 0 || height <= 0) {
            lastFrameNs = System.nanoTime()
            return
        }

        val now = System.nanoTime()
        // Clamped so a long freeze cannot teleport a pet across the panel
        // in a single frame.
        val dt = ((now - lastFrameNs) / 1_000_000_000.0f).coerceIn(0f, 0.1f)
        lastFrameNs = now

        world.bounds = currentBounds()
        world.mode = PetSettings.getInstance().movementMode()
        world.tick(dt, System.currentTimeMillis())
        repaint()
    }

    /**
     * Derives the world bounds from the component's current size.
     *
     * Called on every frame and never cached, which is what keeps the
     * simulation correct across resizes and tool window collapses.
     */
    private fun currentBounds(): WorldBounds {
        val floor = if (PetSettings.getInstance().movementMode() == MovementMode.GROUND)
            (height - JBUI.scale(floorInset)).toFloat()
        else
            height.toFloat()
        return WorldBounds(minX = 0f, maxX = width.toFloat(), minY = 0f, groundY = floor)
    }

    // --- rendering ---

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            // Keep pixel art crisp when scaled.
            g2.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
            )

            if (PetSettings.getInstance().movementMode() == MovementMode.GROUND) drawFloor(g2)

            // Lower pets paint last, which reads as depth.
            val ordered = world.pets.sortedBy { it.pos.y }
            ordered.forEach { drawPet(g2, it) }
            if (PetSettings.getInstance().state.bubblesEnabled) {
                ordered.forEach { drawBubble(g2, it) }
            }
        } finally {
            g2.dispose()
        }
    }

    private fun drawFloor(g2: Graphics2D) {
        val y = height - JBUI.scale(floorInset)
        g2.color = JBColor.namedColor("Separator.separatorColor", JBColor.border())
        g2.stroke = BasicStroke(1f)
        g2.drawLine(0, y, width, y)
    }

    private fun drawPet(g2: Graphics2D, pet: Pet) {
        val size = pet.size
        val x = pet.pos.x.roundToInt()
        val y = pet.pos.y.roundToInt()

        val pack = SpritePackRegistry.get(pet.packId)
        val anim = pack?.animation(pet.state.animation)

        if (anim != null) {
            val frame = anim.frameAt(pet.animTime)
            val tx = AffineTransform()
            if (pet.facingRight) {
                tx.translate(x.toDouble(), y.toDouble())
                tx.scale(size.toDouble() / frame.width, size.toDouble() / frame.height)
            } else {
                // Mirrored horizontally, so packs need only one facing.
                tx.translate((x + size).toDouble(), y.toDouble())
                tx.scale(-size.toDouble() / frame.width, size.toDouble() / frame.height)
            }
            g2.drawImage(frame, tx, null)
        } else {
            drawPlaceholder(g2, pet, x, y, size)
        }
    }

    /**
     * Fallback drawing used when no sprite pack resolves.
     *
     * Colour and motion vary per animation so that behaviour remains legible
     * without artwork, which is also useful while authoring a new pack.
     */
    private fun drawPlaceholder(g2: Graphics2D, pet: Pet, x: Int, y: Int, size: Int) {
        val anim = pet.state.animation

        val body = when (anim) {
            "happy" -> Color(0x6E, 0xC2, 0x7A)
            "sad" -> Color(0x7A, 0x8A, 0xA8)
            "scared" -> Color(0xD2, 0x6A, 0x5E)
            "surprised" -> Color(0xE0, 0xB3, 0x4A)
            "dance" -> Color(0xB8, 0x6E, 0xD2)
            "sleep" -> Color(0x55, 0x5F, 0x70)
            "fall" -> Color(0xC8, 0x8A, 0x4A)
            else -> Color(0x7C, 0xC4, 0x94)
        }

        val t = pet.animTime
        var offY = 0f
        var offX = 0f
        var squash = 1f

        when (anim) {
            "happy" -> offY = -abs(sin(t * 9f)) * size * 0.30f
            "dance" -> {
                offX = sin(t * 8f) * size * 0.28f
                offY = -abs(sin(t * 16f)) * size * 0.10f
            }
            "scared" -> {
                offX = sin(t * 40f) * size * 0.12f
                squash = 0.92f
            }
            "surprised" -> squash = 1f + abs(sin(t * 6f)) * 0.22f
            "sad" -> {
                offY = size * 0.10f
                squash = 0.85f
            }
            "sleep" -> squash = 0.80f + sin(t * 1.5f) * 0.04f
            "walk" -> offY = -abs(sin(t * 12f)) * size * 0.08f
        }

        val w = size * squash
        val h = size / squash
        val px = x + offX + (size - w) / 2f
        val py = y + offY + (size - h)

        g2.color = body
        g2.fill(RoundRectangle2D.Float(px, py, w, h, w * 0.4f, h * 0.4f))

        // Face
        val eyeR = (w * 0.10f).coerceAtLeast(2f)
        val eyeY = py + h * 0.36f
        val lean = if (pet.facingRight) w * 0.05f else -w * 0.05f
        val lx = px + w * 0.30f + lean
        val rx = px + w * 0.70f + lean
        g2.color = Color(0x1E, 0x1E, 0x26)
        g2.stroke = BasicStroke(1.6f)

        when (anim) {
            "sleep" -> {
                g2.drawLine((lx - eyeR).toInt(), eyeY.toInt(), (lx + eyeR).toInt(), eyeY.toInt())
                g2.drawLine((rx - eyeR).toInt(), eyeY.toInt(), (rx + eyeR).toInt(), eyeY.toInt())
            }
            "happy", "dance" -> {
                g2.drawArc((lx - eyeR).toInt(), eyeY.toInt(), (eyeR * 2).toInt(), (eyeR * 2).toInt(), 0, 180)
                g2.drawArc((rx - eyeR).toInt(), eyeY.toInt(), (eyeR * 2).toInt(), (eyeR * 2).toInt(), 0, 180)
            }
            "scared", "surprised" -> {
                val big = eyeR * 1.9f
                g2.color = Color.WHITE
                g2.fillOval((lx - big).toInt(), (eyeY - big).toInt(), (big * 2).toInt(), (big * 2).toInt())
                g2.fillOval((rx - big).toInt(), (eyeY - big).toInt(), (big * 2).toInt(), (big * 2).toInt())
                g2.color = Color(0x1E, 0x1E, 0x26)
                g2.fillOval((lx - eyeR * 0.7f).toInt(), (eyeY - eyeR * 0.7f).toInt(), (eyeR * 1.4f).toInt(), (eyeR * 1.4f).toInt())
                g2.fillOval((rx - eyeR * 0.7f).toInt(), (eyeY - eyeR * 0.7f).toInt(), (eyeR * 1.4f).toInt(), (eyeR * 1.4f).toInt())
            }
            else -> {
                g2.fillOval((lx - eyeR).toInt(), (eyeY - eyeR).toInt(), (eyeR * 2).toInt(), (eyeR * 2).toInt())
                g2.fillOval((rx - eyeR).toInt(), (eyeY - eyeR).toInt(), (eyeR * 2).toInt(), (eyeR * 2).toInt())
            }
        }

        // Mouth
        val mouthY = py + h * 0.62f
        val mw = w * 0.30f
        g2.color = Color(0x1E, 0x1E, 0x26)
        when (anim) {
            "happy", "dance" ->
                g2.drawArc((px + w / 2 - mw / 2).toInt(), (mouthY - mw / 2).toInt(), mw.toInt(), mw.toInt(), 180, 180)
            "sad", "scared" ->
                g2.drawArc((px + w / 2 - mw / 2).toInt(), mouthY.toInt(), mw.toInt(), mw.toInt(), 0, 180)
            "surprised" ->
                g2.fillOval((px + w / 2 - mw / 4).toInt(), mouthY.toInt(), (mw / 2).toInt(), (mw / 2).toInt())
        }
    }

    private fun drawBubble(g2: Graphics2D, pet: Pet) {
        val bubble = pet.bubble ?: return
        val now = System.currentTimeMillis()
        val alpha = bubble.alpha(now)
        if (alpha <= 0f) return

        g2.font = JBUI.Fonts.smallFont()
        val fm = g2.fontMetrics
        val padX = JBUI.scale(7)
        val padY = JBUI.scale(4)
        val tailH = JBUI.scale(5)

        val textW = fm.stringWidth(bubble.text)
        val boxW = textW + padX * 2
        val boxH = fm.height + padY * 2

        val size = pet.size
        val petCenterX = pet.pos.x + size / 2f

        // Kept inside the panel when the pet stands near an edge.
        val boxX = (petCenterX - boxW / 2f).coerceIn(
            JBUI.scale(2).toFloat(),
            (width - boxW - JBUI.scale(2)).coerceAtLeast(JBUI.scale(2)).toFloat(),
        )
        // Tier offsets the bubbles of two pets talking to each other.
        val tierOffset = pet.bubbleTier * (boxH + JBUI.scale(3))

        // Flipped below the pet when there is no room above.
        var boxY = pet.pos.y - boxH - tailH - tierOffset
        val below = boxY < JBUI.scale(2)
        if (below) boxY = pet.pos.y + size + tailH + tierOffset

        val old = g2.composite
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)

        g2.color = JBColor.namedColor("ToolTip.background", JBColor(0xF2F2F2, 0x3C3F41))
        g2.fill(RoundRectangle2D.Float(boxX, boxY, boxW.toFloat(), boxH.toFloat(),
                                       JBUI.scale(8).toFloat(), JBUI.scale(8).toFloat()))

        // Tail pointing at the pet.
        val tailX = petCenterX.coerceIn(boxX + JBUI.scale(8), boxX + boxW - JBUI.scale(8))
        val tail = Path2D.Float().apply {
            if (below) {
                moveTo(tailX - JBUI.scale(4), boxY)
                lineTo(tailX + JBUI.scale(4), boxY)
                lineTo(tailX, boxY - tailH)
            } else {
                moveTo(tailX - JBUI.scale(4), boxY + boxH)
                lineTo(tailX + JBUI.scale(4), boxY + boxH)
                lineTo(tailX, boxY + boxH + tailH)
            }
            closePath()
        }
        g2.fill(tail)

        g2.color = JBColor.namedColor("ToolTip.foreground", JBColor.foreground())
        g2.drawString(bubble.text, boxX + padX, boxY + padY + fm.ascent)

        g2.composite = old
    }

    // --- mouse ---

    private fun installMouse() {
        val handler = object : MouseAdapter() {

            override fun mousePressed(e: MouseEvent) {
                // Nothing is grabbed yet. A plain click is press plus
                // release, so grabbing here would drop the pet into free
                // fall on every click; only the candidate is recorded.
                pressCandidate = petAt(e.x, e.y)
                pressPoint = Point(e.x, e.y)
            }

            override fun mouseDragged(e: MouseEvent) {
                val candidate = pressCandidate ?: return

                // The drag starts only once the threshold is exceeded.
                if (draggedPet == null) {
                    val p = pressPoint ?: return
                    if (abs(e.x - p.x) < dragThreshold && abs(e.y - p.y) < dragThreshold) return
                    draggedPet = candidate
                    dragOffset = Vec2(e.x - candidate.pos.x, e.y - candidate.pos.y)
                    // Suspends physics; otherwise the frame timer fights
                    // the mouse thirty times a second.
                    candidate.grab(System.currentTimeMillis())
                }

                val pet = draggedPet ?: return
                pet.pos = Vec2(e.x - dragOffset.x, e.y - dragOffset.y)
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                val pet = draggedPet
                draggedPet = null
                pressCandidate = null
                pressPoint = null
                pet?.drop(System.currentTimeMillis(), world.mode)
            }

            override fun mouseClicked(e: MouseEvent) {
                // Addressed event, so only the clicked pet reacts.
                val pet = petAt(e.x, e.y) ?: return
                project.service<PetEventBus>().emit(PetEvent.Petted(pet.id))
            }

            override fun mouseMoved(e: MouseEvent) {
                if (!PetSettings.getInstance().state.followMouse) return
                val now = System.currentTimeMillis()
                val target = when (world.mode) {
                    MovementMode.GROUND -> Vec2(e.x.toFloat(), world.bounds.groundY)
                    MovementMode.ROAM_2D -> Vec2(e.x.toFloat(), e.y.toFloat())
                }
                world.pets.forEach { pet ->
                    if (pet.activeReactionPriority == 0 && pet.state !is PetState.Sleep && !pet.isHeld) {
                        pet.moveToward(target, running = false, now = now)
                    }
                }
            }
        }
        addMouseListener(handler)
        addMouseMotionListener(handler)
    }

    private fun petAt(x: Int, y: Int): Pet? {
        val hit = JBUI.scale(4)
        return world.pets.lastOrNull { pet ->
            val s = pet.size
            x >= pet.pos.x - hit && x <= pet.pos.x + s + hit &&
                y >= pet.pos.y - hit && y <= pet.pos.y + s + hit
        }
    }

    override fun dispose() {
        timer.stop()
    }
}
