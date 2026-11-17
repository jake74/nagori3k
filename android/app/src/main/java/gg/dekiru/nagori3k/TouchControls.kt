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
    TouchButton(Native.A, "A", Anchor.BOTTOM_RIGHT, 72f, 72f, 96f, 96f, true),
    TouchButton(Native.B, "B", Anchor.BOTTOM_RIGHT, 168f, 136f, 72f, 72f, true),
    TouchButton(Native.START, "START", Anchor.TOP_RIGHT, 54f, 34f, 76f, 34f, false),
    TouchButton(MENU, "", Anchor.TOP_LEFT, 38f, 34f, 44f, 44f, false),
)
private val dpadSize = 132f
private val dpadCentre = 82f to 142f // from the top left, dp

/** Spin touches start only below this strip, which the small buttons use. */
private const val TOP_STRIP_DP = 68f
/** Fingers may be this far outside a button and still be on it. */
private const val SLOP_DP = 6f
private val dpadNames = listOf("Up", "Down", "Left", "Right")

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

    /** `originX` is where a spin finger first touched; `x` is where it is now. */
    private class Track(var kind: Int, val originX: Float, var x: Float, var mask: Int)
    private val tracks = SparseArray<Track>()
    private var spinId = -1
    private var dead = false

    private val density = resources.displayMetrics.density
    private var insetL = 0f; private var insetT = 0f; private var insetR = 0f; private var insetB = 0f
    private val rects = Array(touchButtons.size) { RectF() }
    private val dpadRect = RectF()

    /** Every code held down now (NUON buttons and MENU), as sent. */
    private var sent = 0

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
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
        place(dpadRect, Anchor.TOP_LEFT, dpadCentre.first, dpadCentre.second, dpadSize, dpadSize)
        invalidate()
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

    private fun spinArea(x: Float, y: Float) = x < width / 2f && y > insetT + TOP_STRIP_DP * density

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
        mask = mask or dpadHit(x - dpadRect.centerX(), y - dpadRect.centerY(), dpadRect.width() / 2 + slop)
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
                        spinOriginY = y
                        tracks.put(id, Track(SPIN, x, x, 0))
                        setStick(0f)
                    } else tracks.put(id, Track(NONE, x, x, 0))
                } else tracks.put(id, Track(if (mask != 0) CTRL else NONE, x, x, mask))
                updateHeld()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val t = tracks.get(e.getPointerId(i)) ?: continue
                    val x = e.getX(i)
                    when (t.kind) {
                        SPIN -> { t.x = x; setStick(((x - t.originX) / density / STICK_RANGE_DP).coerceIn(-1f, 1f)) }
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
                        SPIN -> { spinId = -1; setStick(0f) }
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
    private var spinOriginY = 0f
    private var stickLogged = 0L

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
        outline.color = (if (pressed) 0xDD else strokeAlpha).shl(24) or 0xFFFFFF
        fill.color = (if (pressed) 0x66 else fillAlpha).shl(24) or 0xFFFFFF
    }

    override fun onDraw(canvas: Canvas) {
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
                text.alpha = if (pressed) 0xEE else 0x90
                text.textSize = (if (b.round) r.width() * 0.34f else r.height() * 0.42f)
                canvas.drawText(b.label, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2, text)
            }
        }
        drawDpad(canvas)
        drawStick(canvas)
    }

    /** While a thumb is steering: a mark where it first touched, the reach of full speed, and where it is now. */
    private fun drawStick(canvas: Canvas) {
        val t = if (spinId >= 0) tracks.get(spinId) else null
        if (t == null) return
        val reach = STICK_RANGE_DP * density
        shade(false, 0x40, 0x00)
        canvas.drawLine(t.originX - reach, spinOriginY, t.originX + reach, spinOriginY, outline)
        canvas.drawCircle(t.originX, spinOriginY, 6 * density, outline)
        shade(true, 0x70, 0x18)
        canvas.drawCircle(t.originX + stick * reach, spinOriginY, 22 * density, fill)
        canvas.drawCircle(t.originX + stick * reach, spinOriginY, 22 * density, outline)
    }

    private fun drawDpad(canvas: Canvas) {
        val cx = dpadRect.centerX(); val cy = dpadRect.centerY(); val rad = dpadRect.width() / 2
        shade(false, 0x50, 0x10)
        canvas.drawCircle(cx, cy, rad, fill)
        canvas.drawCircle(cx, cy, rad, outline)
        // An arrow for each direction, pointing outwards, at 0 Up, 1 Down, 2 Left, 3 Right.
        val dirs = arrayOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f)
        val path = Path()
        for (d in 0..3) {
            val (ux, uy) = dirs[d]
            val px = -uy; val py = ux // across the arrow
            val tip = rad * 0.82f; val base = rad * 0.42f; val half = rad * 0.22f
            path.reset()
            path.moveTo(cx + ux * tip, cy + uy * tip)
            path.lineTo(cx + ux * base + px * half, cy + uy * base + py * half)
            path.lineTo(cx + ux * base - px * half, cy + uy * base - py * half)
            path.close()
            val pressed = sent and (1 shl d) != 0
            shade(pressed, 0x70, 0x20)
            canvas.drawPath(path, fill)
            canvas.drawPath(path, outline)
        }
    }

    private companion object {
        const val NONE = 0; const val SPIN = 1; const val CTRL = 2
    }
}
