package gg.dekiru.nagori3k

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * How far, in dp, the thumb has to be from where it first touched for the
 * claw to turn at full speed. The first touch is the centre of an invisible
 * stick: holding the thumb off to one side keeps the claw turning that way,
 * and the further out, the faster. It goes to the core as the analogue stick
 * does, so Sensitivity and Weight apply exactly as they do on a controller.
 * (The first version turned the claw by the distance dragged, like a knob:
 * every stroke ended with the claw stopped, and playing took endless swipes.)
 * To be tuned by play.
 */
const val STICK_RANGE_DP = 90f

/**
 * Turning back. Once the thumb has come back this far, in dp, from the
 * furthest it went, the centre jumps to that furthest point: the claw starts
 * the other way at once, instead of waiting for the thumb to travel all the
 * way back past where it first touched. Small enough to feel immediate, large
 * enough that a trembling thumb does not flip the claw about.
 */
const val STICK_REVERSE_DP = 10f

/**
 * Which d-pad directions a touch at (dx, dy) from the pad's centre presses, as
 * a mask in NUON button order: bit 0 Up, 1 Down, 2 Left, 3 Right. Each
 * direction covers 120 degrees, so between two of them both are pressed;
 * the middle and anything beyond `radius` press nothing.
 */
fun dpadHit(dx: Float, dy: Float, radius: Float): Int {
    val r = hypot(dx, dy)
    if (r < radius * 0.18f || r > radius) return 0
    val a = Math.toDegrees(atan2(dy, dx).toDouble()) // 0 = right, 90 = down (y grows downwards)
    var mask = 0
    if (a >= -150 && a <= -30) mask = mask or 1
    if (a >= 30 && a <= 150) mask = mask or 2
    if (abs(a) >= 120) mask = mask or 4
    if (abs(a) <= 60) mask = mask or 8
    return mask
}

enum class Anchor { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** The code that stands for the app's own Menu button, after the 14 NUON buttons. */
private const val MENU = 14

/**
 * A button on screen. `code` is a NUON button index (see nuonButtonNames), or MENU.
 * (cx, cy) is the centre in dp from the anchor corner, towards the screen's inside.
 */
private class TouchButton(val code: Int, val label: String, val anchor: Anchor, val cx: Float, val cy: Float, val w: Float, val h: Float, val round: Boolean)

/** The one list to add buttons to. */
private val touchButtons = listOf(
    TouchButton(Native.A, "A", Anchor.BOTTOM_RIGHT, 100f, 100f, 96f, 96f, true),
    TouchButton(Native.B, "B", Anchor.BOTTOM_RIGHT, 196f, 164f, 72f, 72f, true),
    TouchButton(Native.START, "START", Anchor.TOP_RIGHT, 54f, 34f, 76f, 34f, false),
    TouchButton(MENU, "", Anchor.TOP_LEFT, 38f, 34f, 44f, 44f, false),
)
/** The share of the knob's width that its inner circle takes. */
private const val KNOB_INNER = 0.74f
/** The d-pad is as wide and as tall as the knob's inner circle. */
private val dpadSize get() = KNOB_SIZE_DP * KNOB_INNER
/** The d-pad sits above the knob, centred on it, with this much between them, in dp. */
private const val DPAD_GAP_DP = 40f

/** Spin touches start only below this strip, which the small buttons use. */
private const val TOP_STRIP_DP = 68f
/** Fingers may be this far outside a button and still be on it. */
private const val SLOP_DP = 6f
private val dpadNames = listOf("Up", "Down", "Left", "Right")

/**
 * The knob starts in the lower left: its centre from that corner, in dp. After
 * that it goes with the steering thumb and stays where the thumb lifted,
 * which is where this player likes to hold the device; the place is remembered.
 */
private val knobCentre = 104f to 104f
/**
 * How visible the knob stays under a steering thumb: faint, but still there
 * to be seen turning. Its lines are already drawn well under half strength,
 * so a tenth of that could not be seen at all on the device; this share
 * leaves them at about a tenth of full white, which can.
 */
private const val KNOB_HELD_OPACITY = 0.35f
private const val KNOB_SIZE_DP = 128f
/**
 * The share of its speed the drawn knob keeps each 60 Hz frame once the thumb
 * has lifted, from the Weight setting (0..200). The same curve as the core's
 * flywheel (ApplyAnalogControls in the core's libretro.cpp), so the picture
 * runs down as the claw does: nothing at 0, 91% at the default 100, and
 * tapering to 97% at 200. Always below 1, so it always stops.
 */
fun knobKeep(weight: Int): Float {
    val i = weight / 100f * 0.85f // the core's own scale, where the option's 100% is 0.85
    return when {
        i <= 0f -> 0f
        i <= 0.7f -> 0.60f + 0.39f * i
        i <= 1f -> 0.873f + (i - 0.7f) * (0.94f - 0.873f) / 0.3f
        else -> { val t = (i - 1f).coerceAtMost(1f); 0.94f + 0.035f * (1f - (1f - t) * (1f - t)) }
    }
}

/** How fast the drawn knob turns with the stick hard over, in degrees a second. */
private const val KNOB_DEGREES_PER_SECOND = 400f
/** The d-pad stays away this long after the thumb leaves the steering area, so it does not flicker between strokes. */
private const val DPAD_RETURN_MS = 800L
/** Fading: out quickly as the thumb comes down, back a little more gently. */
private const val FADE_OUT_MS = 120f
private const val FADE_IN_MS = 220f
/** Everything is drawn at this share of the opacities given below. */
private const val OPACITY = 0.8f

/**
 * The on-screen controls: one view over the game that draws them and takes
 * every touch itself, each finger followed by its pointer id, so spinning,
 * holding A and pressing B can all happen at once. Input goes to Native
 * straight from here.
 */
@SuppressLint("ViewConstructor")
class TouchControlsView(context: Context, private val app: MainActivity) : View(context) {
    /** Hold A down for the player. Read by the screen when settings change. */
    var autoFire = false
        set(v) { field = v; updateHeld() }

    /**
     * Steer with the spinner. It is how Tempest 3000 wants to be played, and
     * no use to most other games: switched off, the knob goes, the left of
     * the screen stops steering, and the d-pad takes the knob's place, full size.
     */
    var spinner = true
        set(v) { if (field != v) { field = v; if (!v) cancelSteering(); layoutControls() } }

    private fun cancelSteering() {
        if (spinId >= 0) { tracks.remove(spinId); spinId = -1 }
        setStick(0f)
        knobRate = 0f
        dpadBackAt = 0L
    }

    /** `originX` is where a spin finger first touched; `x` is where it is now. */
    private class Track(var kind: Int, var originX: Float, var x: Float, var mask: Int) {
        /** The furthest the thumb has gone from the centre, on the side it is on. */
        var extremeX = originX
    }
    private val tracks = SparseArray<Track>()
    private var spinId = -1
    private var dead = false

    private val density = resources.displayMetrics.density
    private var insetL = 0f; private var insetT = 0f; private var insetR = 0f; private var insetB = 0f
    private val rects = Array(touchButtons.size) { RectF() }
    private val dpadRect = RectF()
    private val knobRect = RectF()

    /** Every code held down now (NUON buttons and MENU), as sent. */
    private var sent = 0

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = 0xFFFFFFFF.toInt() }

    // --- Layout

    private fun refreshInsets(w: WindowInsets?) {
        if (w == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = w.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insetL = i.left.toFloat(); insetT = i.top.toFloat(); insetR = i.right.toFloat(); insetB = i.bottom.toFloat()
        } else {
            @Suppress("DEPRECATION")
            run {
                insetL = w.systemWindowInsetLeft.toFloat(); insetT = w.systemWindowInsetTop.toFloat()
                insetR = w.systemWindowInsetRight.toFloat(); insetB = w.systemWindowInsetBottom.toFloat()
            }
            w.displayCutout?.let {
                insetL = max(insetL, it.safeInsetLeft.toFloat()); insetT = max(insetT, it.safeInsetTop.toFloat())
                insetR = max(insetR, it.safeInsetRight.toFloat()); insetB = max(insetB, it.safeInsetBottom.toFloat())
            }
        }
        layoutControls()
    }

    private fun place(r: RectF, a: Anchor, cx: Float, cy: Float, w: Float, h: Float) {
        val x = if (a == Anchor.TOP_LEFT || a == Anchor.BOTTOM_LEFT) insetL + cx * density else width - insetR - cx * density
        val y = if (a == Anchor.TOP_LEFT || a == Anchor.TOP_RIGHT) insetT + cy * density else height - insetB - cy * density
        r.set(x - w * density / 2, y - h * density / 2, x + w * density / 2, y + h * density / 2)
    }

    private fun layoutControls() {
        touchButtons.forEachIndexed { i, b -> place(rects[i], b.anchor, b.cx, b.cy, b.w, b.h) }
        if (width > 0 && height > 0) {
            // Where the thumb last steered from, or the lower left the first time.
            val saved = app.settings.touchKnob
            val x = insetL + (saved?.first ?: knobCentre.first) * density
            val y = height - insetB - (saved?.second ?: knobCentre.second) * density
            placeKnob(x, y)
            placeDpad()
        }
        invalidate()
    }

    /** Puts the knob's centre at (x, y), kept whole on the left half of the screen and below the top strip. */
    private fun placeKnob(x: Float, y: Float) {
        val r = KNOB_SIZE_DP * density / 2
        val cx = x.coerceIn(insetL + r, max(insetL + r, width / 2f - r))
        val cy = y.coerceIn(insetT + TOP_STRIP_DP * density + r, max(insetT + TOP_STRIP_DP * density + r, height - insetB - r))
        knobRect.set(cx - r, cy - r, cx + r, cy + r)
    }

    /** Puts the d-pad centrally above the knob; below it if there is no room above. */
    private fun placeDpad() {
        if (!spinner) { dpadRect.set(knobRect); return } // no knob: the d-pad has its place and its size
        val r = dpadSize * density / 2
        val away = knobRect.height() / 2 + DPAD_GAP_DP * density + r
        val top = insetT + TOP_STRIP_DP * density
        var cy = knobRect.centerY() - away
        if (cy - r < top && knobRect.centerY() + away + r <= height - insetB) cy = knobRect.centerY() + away
        cy = cy.coerceAtLeast(top + r)
        val cx = knobRect.centerX().coerceIn(insetL + r, max(insetL + r, width - insetR - r))
        dpadRect.set(cx - r, cy - r, cx + r, cy + r)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        app.touchView = this
        refreshInsets(rootWindowInsets)
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        cancelAll(true)
        if (app.touchView === this) app.touchView = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshInsets(rootWindowInsets)
        layoutControls()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        refreshInsets(insets)
        return insets
    }

    // --- Touch

    private fun spinArea(x: Float, y: Float) = spinner && x < width / 2f && y > insetT + TOP_STRIP_DP * density

    /** The codes pressed by a finger at (x, y). */
    private fun controlMask(x: Float, y: Float): Int {
        var mask = 0
        val slop = SLOP_DP * density
        touchButtons.forEachIndexed { i, b ->
            val r = rects[i]
            val hit = if (b.round) hypot(x - r.centerX(), y - r.centerY()) <= r.width() / 2 + slop
                else x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop
            if (hit) mask = mask or (1 shl b.code)
        }
        if (!dpadAway()) mask = mask or dpadHit(x - dpadRect.centerX(), y - dpadRect.centerY(), dpadRect.width() / 2 + slop)
        return mask
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (dead) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i); val y = e.getY(i)
                val mask = controlMask(x, y)
                if (mask == 0 && spinArea(x, y)) {
                    if (spinId < 0) {
                        spinId = id
                        tracks.put(id, Track(SPIN, x, x, 0))
                        setStick(0f)
                        // The knob goes under the thumb, fades to a ghost, and travels with it.
                        placeKnob(x, y)
                        invalidate()
                    } else tracks.put(id, Track(NONE, x, x, 0))
                } else tracks.put(id, Track(if (mask != 0) CTRL else NONE, x, x, mask))
                updateHeld()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val t = tracks.get(e.getPointerId(i)) ?: continue
                    val x = e.getX(i)
                    when (t.kind) {
                        SPIN -> { t.x = x; steer(t); placeKnob(x, e.getY(i)); invalidate() }
                        CTRL -> t.mask = controlMask(x, e.getY(i))
                    }
                }
                updateHeld()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val t = tracks.get(id)
                if (t != null) {
                    tracks.remove(id)
                    when (t.kind) {
                        // Letting go centres the stick; the core's flywheel carries the claw on, by the Weight.
                        SPIN -> {
                            spinId = -1; setStick(0f)
                            // Where the thumb lifted is where the knob stays, and is remembered.
                            placeKnob(e.getX(i), e.getY(i))
                            app.settings.touchKnob = Pair((knobRect.centerX() - insetL) / density, (height - insetB - knobRect.centerY()) / density)
                            dpadBackAt = android.os.SystemClock.uptimeMillis() + DPAD_RETURN_MS
                            postInvalidateDelayed(DPAD_RETURN_MS + 20)
                        }
                        // The menu opens when the finger lifts from its button.
                        CTRL -> if (t.mask and (1 shl MENU) != 0) { updateHeld(); app.openMenu(); return true }
                    }
                }
                updateHeld()
            }
            MotionEvent.ACTION_CANCEL -> cancelAll(false)
        }
        return true
    }

    // --- Buttons

    private fun updateHeld() {
        var want = 0
        for (i in 0 until tracks.size()) { val t = tracks.valueAt(i); if (t.kind == CTRL) want = want or t.mask }
        if (autoFire && !dead) want = want or (1 shl Native.A)
        val changed = want xor sent
        if (changed == 0) return
        for (code in 0..MENU) {
            val bit = 1 shl code
            if (changed and bit == 0) continue
            val down = want and bit != 0
            if (code != MENU) Native.setButton(code, down)
            Log.d(MainActivity.TAG, "touch: ${labelOf(code)} ${if (down) "down" else "up"}")
        }
        sent = want
        invalidate()
    }

    private fun labelOf(code: Int) = when {
        code == MENU -> "Menu"
        code < 4 -> dpadNames[code]
        else -> touchButtons.firstOrNull { it.code == code }?.label ?: nuonButtonNames[code]
    }

    /** Lets go of everything. `last` means this view is finished (gone, or the game paused or stopped). */
    fun cancelAll(last: Boolean) {
        tracks.clear()
        if (spinId >= 0 || stick != 0f) setStick(0f)
        spinId = -1
        if (last) dead = true
        updateHeld()
    }

    // --- The invisible stick

    private var stick = 0f
    /** The d-pad is for menus; while a thumb is steering, and for a moment after, it is put away. */
    private var dpadBackAt = 0L
    private fun dpadAway() = spinId >= 0 || android.os.SystemClock.uptimeMillis() < dpadBackAt
    private var knobAngle = 0f
    /** How fast the drawn knob is turning, in degrees a second. */
    private var knobRate = 0f
    /** How visible the knob and the d-pad are just now, 0 to 1; they fade rather than jump. */
    private var knobShown = 1f
    private var dpadShown = 1f
    private var fadedAt = 0L
    /** The share of full opacity for whatever is being drawn. */
    private var drawing = 1f
    private var knobDrawnAt = 0L
    private var stickLogged = 0L

    /** Works out where the stick is from where the thumb is, moving the centre when the thumb turns back or runs past the reach. */
    private fun steer(t: Track) {
        val reach = STICK_RANGE_DP * density
        val side = if (t.x >= t.originX) 1f else -1f
        // Further out on this side than before, or over on the other side of the centre: a new furthest point.
        if ((t.x - t.extremeX) * side > 0f || (t.extremeX - t.originX) * side < 0f) t.extremeX = t.x
        // Come back far enough from it: that point is the new centre, and the thumb is now on its other side.
        if ((t.extremeX - t.x) * side > STICK_REVERSE_DP * density && t.extremeX != t.originX) {
            t.originX = t.extremeX
            t.extremeX = t.x
        }
        // Past full speed the centre is dragged along, so it is never more than the reach away.
        if (t.x - t.originX > reach) t.originX = t.x - reach
        if (t.originX - t.x > reach) t.originX = t.x + reach
        setStick(((t.x - t.originX) / reach).coerceIn(-1f, 1f))
    }

    private fun setStick(x: Float) {
        if (x == stick) return
        stick = x
        Native.setStick(x, 0f)
        // For the reviewer's log: where the stick is, at most twice a second, and always when it centres.
        val now = android.os.SystemClock.uptimeMillis()
        if (x == 0f || now - stickLogged >= 500) { stickLogged = now; Log.d(MainActivity.TAG, "touch: stick ${"%.2f".format(x)}") }
        invalidate()
    }

    // --- Drawing

    /** White at low opacity; brighter when pressed. */
    private fun shade(pressed: Boolean, strokeAlpha: Int, fillAlpha: Int) {
        val k = OPACITY * drawing
        outline.color = ((if (pressed) 0xDD else strokeAlpha) * k).toInt().shl(24) or 0xFFFFFF
        fill.color = ((if (pressed) 0x66 else fillAlpha) * k).toInt().shl(24) or 0xFFFFFF
    }

    /** Moves the knob and the d-pad towards shown or hidden, by the time since the last frame. */
    private fun fade() {
        val now = android.os.SystemClock.uptimeMillis()
        val dt = if (fadedAt == 0L) 0f else (now - fadedAt).coerceAtMost(100).toFloat()
        fadedAt = now
        fun step(v: Float, to: Float) = if (v < to) (v + dt / FADE_IN_MS).coerceAtMost(to) else (v - dt / FADE_OUT_MS).coerceAtLeast(to)
        val knobTo = if (spinId < 0) 1f else KNOB_HELD_OPACITY
        val dpadTo = if (dpadAway()) 0f else 1f
        knobShown = step(knobShown, knobTo)
        // The d-pad follows the knob to its new place only while it cannot be seen.
        if (dpadShown == 0f) placeDpad()
        dpadShown = step(dpadShown, dpadTo)
        if (knobShown != knobTo || dpadShown != dpadTo) postInvalidateOnAnimation()
        else fadedAt = 0L
    }

    override fun onDraw(canvas: Canvas) {
        fade()
        drawing = 1f
        touchButtons.forEachIndexed { i, b ->
            val r = rects[i]
            val pressed = sent and (1 shl b.code) != 0
            shade(pressed, 0x70, 0x18)
            if (b.round) {
                canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2, fill)
                canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2, outline)
            } else {
                val rad = if (b.code == MENU) 8 * density else r.height() / 2
                canvas.drawRoundRect(r, rad, rad, fill)
                canvas.drawRoundRect(r, rad, rad, outline)
            }
            if (b.code == MENU) {
                // A pause glyph: two bars.
                fill.color = outline.color
                val bw = r.width() * 0.10f; val bh = r.height() * 0.40f
                canvas.drawRect(r.centerX() - bw * 2, r.centerY() - bh / 2, r.centerX() - bw, r.centerY() + bh / 2, fill)
                canvas.drawRect(r.centerX() + bw, r.centerY() - bh / 2, r.centerX() + bw * 2, r.centerY() + bh / 2, fill)
            } else {
                text.alpha = ((if (pressed) 0xEE else 0x90) * OPACITY).toInt()
                text.textSize = (if (b.round) r.width() * 0.34f else r.height() * 0.42f)
                canvas.drawText(b.label, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2, text)
            }
        }
        drawing = dpadShown
        if (dpadShown > 0f) drawDpad(canvas)
        drawing = knobShown
        if (spinner) drawKnob(canvas)
        drawing = 1f
    }

    /**
     * A spinner seen from above: where the thumb goes. It turns while the
     * claw is being steered, at a rate that follows the stick, and fades to a
     * ghost under the thumb that is covering it.
     */
    private fun drawKnob(canvas: Canvas) {
        val now = android.os.SystemClock.uptimeMillis()
        val dt = if (knobDrawnAt == 0L) 0f else (now - knobDrawnAt).coerceAtMost(100) / 1000f
        if (stick != 0f) {
            // Against the thumb: steering right turns the knob anticlockwise, which is the way that looked right in play.
            knobRate = -stick * KNOB_DEGREES_PER_SECOND
        } else if (knobRate != 0f) {
            // Let go, it runs down as the claw does: by the Weight, like the core's flywheel.
            knobRate *= Math.pow(knobKeep(app.settings.stickWeight).toDouble(), (dt * 60f).toDouble()).toFloat()
            if (abs(knobRate) < 4f) knobRate = 0f
        }
        if (knobRate != 0f) {
            knobAngle = (knobAngle + knobRate * dt) % 360f
            knobDrawnAt = now
            postInvalidateOnAnimation()
        } else knobDrawnAt = 0L

        if (knobShown <= 0f) return
        val cx = knobRect.centerX(); val cy = knobRect.centerY(); val rad = knobRect.width() / 2
        // The body, then the grip round its edge.
        shade(false, 0x60, 0x14)
        canvas.drawCircle(cx, cy, rad, fill)
        canvas.drawCircle(cx, cy, rad, outline)
        canvas.drawCircle(cx, cy, rad * KNOB_INNER, outline)
        canvas.save()
        canvas.rotate(knobAngle, cx, cy)
        for (i in 0 until 24) {
            canvas.drawLine(cx, cy - rad * 0.80f, cx, cy - rad * 0.94f, outline)
            canvas.rotate(15f, cx, cy)
        }
        // The finger dimple, off-centre, which is what shows the turning.
        shade(false, 0x80, 0x30)
        canvas.drawCircle(cx, cy - rad * 0.44f, rad * 0.16f, fill)
        canvas.drawCircle(cx, cy - rad * 0.44f, rad * 0.16f, outline)
        canvas.restore()
    }

    /**
     * Four separate arms, no ring: each a bar with a point on its inner end,
     * as on a PlayStation pad, with clear space between them in the middle.
     */
    private fun drawDpad(canvas: Canvas) {
        val cx = dpadRect.centerX(); val cy = dpadRect.centerY(); val rad = dpadRect.width() / 2
        // 0 Up, 1 Down, 2 Left, 3 Right: the way each arm points.
        val dirs = arrayOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f)
        val half = rad * 0.27f      // half the bar's width
        val outer = rad * 0.98f     // its outer end
        val tip = rad * 0.22f       // how close the point comes to the centre
        val path = Path()
        for (d in 0..3) {
            val (ux, uy) = dirs[d]
            val px = -uy; val py = ux // across the arm
            path.reset()
            path.moveTo(cx + ux * tip, cy + uy * tip)
            path.lineTo(cx + ux * (tip + half) + px * half, cy + uy * (tip + half) + py * half)
            path.lineTo(cx + ux * outer + px * half, cy + uy * outer + py * half)
            path.lineTo(cx + ux * outer - px * half, cy + uy * outer - py * half)
            path.lineTo(cx + ux * (tip + half) - px * half, cy + uy * (tip + half) - py * half)
            path.close()
            val pressed = sent and (1 shl d) != 0
            shade(pressed, 0x70, 0x18)
            canvas.drawPath(path, fill)
            canvas.drawPath(path, outline)
        }
    }

    private companion object {
        const val NONE = 0; const val SPIN = 1; const val CTRL = 2
    }
}
