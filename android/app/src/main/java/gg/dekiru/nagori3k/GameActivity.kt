package gg.dekiru.nagori3k

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Phase 1 of the Android plan: the game, full screen, with sound and a
 * controller, and nothing else. The disc image is looked for at
 * /sdcard/NUON/Tempest 3000.iso unless the launching intent names another
 * with the extra "game". Extras for testing: "perf" (boolean) turns the
 * core's performance log on; "script" passes a NUANCE_INPUT_SCRIPT.
 */
class GameActivity : Activity() {
    private lateinit var view: GLSurfaceView
    private var loaded = false
    private var stickX = 0f
    private var stickY = 0f
    private var triggers = 0f
    private val hat = BooleanArray(4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val system = File(filesDir, "system").also { copyBios(it) }
        val saves = File(filesDir, "saves").also { it.mkdirs() }
        val core = File(applicationInfo.nativeLibraryDir, "libnuance_libretro.so")
        val perf = intent.getBooleanExtra("perf", false)
        intent.getStringExtra("script")?.let { Native.setEnvironment("NUANCE_INPUT_SCRIPT", it) }

        if (!Native.init(core.path, system.path, saves.path, cacheDir.path, perf)) {
            Log.e(TAG, "the emulator core could not be loaded from ${core.path}")
            finish(); return
        }
        // The same defaults as the Mac app.
        Native.setOption("nuance_controller", "analog stick")
        Native.setOption("nuance_spinner_inertia", "70%")
        Native.setOption("nuance_skip_controller_message", "enabled")
        Native.setOption("nuance_max_fps", intent.getStringExtra("fps") ?: "30")
        Native.setOption("nuance_t3k_high_scores", "keep")
        Native.setOption("nuance_scanlines", "disabled")

        val game = intent.getStringExtra("game")
            ?: File(Environment.getExternalStorageDirectory(), "NUON/Tempest 3000.iso").path
        if (!File(game).canRead()) Log.e(TAG, "cannot read $game (is all-files access granted?)")
        loaded = Native.loadGame(game)
        if (!loaded) Log.e(TAG, "the game could not be loaded: $game")

        view = GLSurfaceView(this)
        view.setEGLContextClientVersion(3)
        view.preserveEGLContextOnPause = true
        view.setRenderer(object : GLSurfaceView.Renderer {
            private var width = 0
            private var height = 0
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) { Native.contextReady() }
            override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { width = w; height = h }
            override fun onDrawFrame(gl: GL10?) { Native.drawFrame(width, height) }
        })
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        setContentView(view)
        view.requestFocus()
    }

    private fun copyBios(system: File) {
        val dir = File(system, "nuance").also { it.mkdirs() }
        for (name in assets.list("system/nuance") ?: emptyArray()) {
            assets.open("system/nuance/$name").use { input ->
                File(dir, name).outputStream().use { input.copyTo(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        view.onResume()
        if (loaded) Native.startAudio()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            // Ask for a 60 Hz mode if the panel has one: the game steps at 60.
            window.attributes = window.attributes.also { it.preferredRefreshRate = 60f }
        }
    }

    override fun onPause() {
        // With no frames asked for, the core's emulation thread goes idle by itself.
        Native.stopAudio()
        view.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (loaded) view.queueEvent { Native.unloadGame() }
        super.onDestroy()
    }

    // --- Controller. The layout follows the Mac app's defaults.

    private fun buttonFor(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> Native.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Native.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Native.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Native.RIGHT
        KeyEvent.KEYCODE_BUTTON_B -> Native.A          // right face button fires
        KeyEvent.KEYCODE_BUTTON_A -> Native.B          // bottom face button
        KeyEvent.KEYCODE_BUTTON_START -> Native.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> Native.NUON
        KeyEvent.KEYCODE_BUTTON_L1 -> Native.L
        KeyEvent.KEYCODE_BUTTON_R1 -> Native.R
        KeyEvent.KEYCODE_BUTTON_Y -> Native.C_UP
        KeyEvent.KEYCODE_BUTTON_X -> Native.C_LEFT
        else -> -1
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val button = buttonFor(event.keyCode)
        if (button >= 0) {
            if (event.action == KeyEvent.ACTION_DOWN) Native.setButton(button, true)
            else if (event.action == KeyEvent.ACTION_UP) Native.setButton(button, false)
            return true
        }
        // The triggers turn the claw (below); their key events are swallowed.
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R2) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && event.action == MotionEvent.ACTION_MOVE) {
            stickX = event.getAxisValue(MotionEvent.AXIS_X)
            stickY = event.getAxisValue(MotionEvent.AXIS_Y)
            // Triggers report on one pair of axes or the other, depending on the controller.
            val left = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
            val right = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
            triggers = right - left
            Native.setStick((stickX + triggers).coerceIn(-1f, 1f), stickY)

            // A d-pad that reports as a hat.
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            setHat(0, Native.LEFT, hx < -0.5f); setHat(1, Native.RIGHT, hx > 0.5f)
            setHat(2, Native.UP, hy < -0.5f); setHat(3, Native.DOWN, hy > 0.5f)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun setHat(index: Int, button: Int, down: Boolean) {
        if (hat[index] != down) { hat[index] = down; Native.setButton(button, down) }
    }

    companion object { private const val TAG = "nagori3k" }
}
