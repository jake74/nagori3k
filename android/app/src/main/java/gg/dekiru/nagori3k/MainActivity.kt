package gg.dekiru.nagori3k

import android.content.Intent
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The whole app: a library, a settings screen, and the game. One activity,
 * because the game needs the activity's key and controller events and its
 * pause and resume.
 *
 * Intent extras for testing: "game" (a disc image to start straight away),
 * "perf" (boolean: the core's performance log, in logcat under "nagori3k"),
 * "script" (a NUANCE_INPUT_SCRIPT), "screen" ("settings" opens that screen),
 * "menu" (boolean: with "game", opens the in-game menu after five seconds),
 * "updateUrl" (where to look for a newer release instead of GitHub, whatever
 * the time since the last look).
 */
class MainActivity : ComponentActivity() {
    lateinit var settings: Settings
    lateinit var boxArt: BoxArt
    lateinit var updates: Updates

    var screen by mutableStateOf("library")
    var games by mutableStateOf(listOf<Game>())
    var playing by mutableStateOf<Game?>(null)
    /** Over a running game: "none", "menu" (resume, options, library) or "settings". The game is paused for either. */
    var overlay by mutableStateOf("none")
    var message by mutableStateOf<String?>(null)
    var hasFileAccess by mutableStateOf(false)
    /** The mapping screen is waiting for a controller button for this NUON button; -1 when not. */
    var capturing by mutableStateOf(-1)

    private var coreStarted = false
    private var gameView: GLSurfaceView? = null
    private var gameLoaded = false
    private val hat = BooleanArray(4)
    private val triggerAsButton = BooleanArray(2)
    private var startHeld = false
    private var selectHeld = false

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { pathFor(it) }?.let { settings.libraryPath = it; refreshLibrary() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        boxArt = BoxArt(this, lifecycleScope)
        updates = Updates(this, settings, lifecycleScope)

        if (settings.libraryPath.isEmpty()) {
            val usual = File(Environment.getExternalStorageDirectory(), "NUON")
            if (usual.isDirectory) settings.libraryPath = usual.path
        }
        intent.getStringExtra("script")?.let { Native.setEnvironment("NUANCE_INPUT_SCRIPT", it) }
        if (intent.getStringExtra("screen") == "settings") screen = "settings"

        setContent { AppUi(this) }
        intent.getStringExtra("game")?.let { play(Game(File(it))) }
        // "updateUrl" (a file path, file: or https: address) is looked at instead of GitHub, and forces a check.
        updates.addressOverride = intent.getStringExtra("updateUrl")
        updates.checkAtLaunch(playing != null)
        if (intent.getBooleanExtra("menu", false)) window.decorView.postDelayed({ openMenu() }, 5000)
    }

    override fun onResume() {
        super.onResume()
        hasFileAccess = Environment.isExternalStorageManager()
        refreshLibrary()
        gameView?.onResume()
        if (gameLoaded && overlay == "none") Native.startAudio()
        applyChrome()
    }

    override fun onPause() {
        // With no frames asked for, the core's emulation thread goes idle by itself.
        Native.stopAudio()
        gameView?.onPause()
        super.onPause()
    }

    fun refreshLibrary() {
        games = if (settings.libraryPath.isEmpty()) emptyList() else scanLibrary(File(settings.libraryPath))
    }

    fun chooseFolder() = pickFolder.launch(null)

    fun askForFileAccess() {
        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
    }

    /** The folder picker gives a document URI; the core needs a real path. */
    private fun pathFor(tree: Uri): String? {
        val id = DocumentsContract.getTreeDocumentId(tree) ?: return null
        val volume = id.substringBefore(':')
        val rest = id.substringAfter(':', "")
        val base = if (volume == "primary") Environment.getExternalStorageDirectory().path else "/storage/$volume"
        return if (rest.isEmpty()) base else "$base/$rest"
    }

    // --- Playing

    private fun startCore(): Boolean {
        if (coreStarted) return true
        val system = File(filesDir, "system").also { copyBios(it) }
        val saves = File(filesDir, "saves").also { it.mkdirs() }
        val core = File(applicationInfo.nativeLibraryDir, "libnuance_libretro.so")
        coreStarted = Native.init(core.path, system.path, saves.path, cacheDir.path, intent.getBooleanExtra("perf", false))
        if (!coreStarted) message = "The emulator core could not be loaded."
        return coreStarted
    }

    private fun copyBios(system: File) {
        val dir = File(system, "nuance").also { it.mkdirs() }
        for (name in assets.list("system/nuance") ?: emptyArray()) {
            assets.open("system/nuance/$name").use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
        }
    }

    fun play(game: Game) {
        if (playing != null || !startCore()) return
        settings.apply()
        // The core outlives this screen. If Android made the screen afresh while
        // a game was loaded, that game is still there and refuses a second one:
        // let it go and try once more.
        if (!Native.loadGame(game.file.path) && !run { Native.unloadGame(); Native.loadGame(game.file.path) }) {
            message = "${game.title} could not be started."
            Log.e(TAG, "could not load ${game.file.path}")
            return
        }
        message = null
        gameLoaded = true
        playing = game
        overlay = "none"
        Native.setPaused(false)
        Native.startAudio()
        applyChrome()
    }

    /** Pauses the game and puts the menu over it. */
    fun openMenu() {
        if (playing == null || overlay != "none") return
        Native.setPaused(true)
        Native.stopAudio()
        releaseAll()
        overlay = "menu"
        applyChrome()
    }

    fun resumeGame() {
        if (playing == null) return
        overlay = "none"
        capturing = -1
        settings.apply()
        Native.setPaused(false)
        Native.startAudio()
        applyChrome()
    }

    private fun releaseAll() {
        for (b in 0 until nuonButtonNames.size) Native.setButton(b, false)
        Native.setStick(0f, 0f)
        startHeld = false; selectHeld = false
    }

    /** Called by the game screen to get its view. */
    fun makeGameView(): GLSurfaceView {
        val view = GLSurfaceView(this)
        view.setEGLContextClientVersion(3)
        view.preserveEGLContextOnPause = true
        view.setRenderer(object : GLSurfaceView.Renderer {
            private var width = 0
            private var height = 0
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) { Native.contextReady() }
            override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { width = w; height = h }
            override fun onDrawFrame(gl: GL10?) { Native.drawFrame(width, height) }
        })
        gameView = view
        return view
    }

    fun stopGame() {
        val view = gameView
        if (gameLoaded) {
            gameLoaded = false
            Native.stopAudio()
            // On the GL thread, where the core's graphics objects live; then the view can go.
            if (view != null) view.queueEvent { Native.unloadGame() } else Native.unloadGame()
        }
        releaseAll()
        Native.setPaused(false)
        overlay = "none"
        gameView = null
        playing = null
        screen = "library"
        applyChrome()
    }

    /** Removes the kept high scores. With a game running, the core also puts the game's own table back. */
    fun deleteHighScores() {
        if (gameLoaded) {
            Native.setOption("nuance_t3k_high_scores", "erase")
            window.decorView.postDelayed({ settings.apply() }, 500)
        }
        File(filesDir, "saves").listFiles()?.filter { it.extension == "hiscores" }?.forEach { it.delete() }
    }

    /** The system's bars are hidden while a game is on screen. */
    private fun applyChrome() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val controller = window.insetsController ?: return
        if (playing != null && overlay == "none") {
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // Ask for a 60 Hz mode if the panel has one: the game steps at 60.
            window.attributes = window.attributes.also { it.preferredRefreshRate = 60f }
        } else {
            controller.show(WindowInsets.Type.systemBars())
            window.attributes = window.attributes.also { it.preferredRefreshRate = 0f }
        }
    }

    // --- Controller

    private fun isPad(event: KeyEvent) =
        event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
        event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
        event.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The mapping screen takes the next controller button for itself.
        if (capturing >= 0 && padKeyNames.containsKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_UP) { settings.map(capturing, event.keyCode); capturing = -1 }
            return true
        }
        if (playing != null && overlay == "none" && event.keyCode != KeyEvent.KEYCODE_BACK) {
            // Start and Select together, or the controller's home button, open the menu.
            val down = event.action == KeyEvent.ACTION_DOWN
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_START -> startHeld = down
                KeyEvent.KEYCODE_BUTTON_SELECT -> selectHeld = down
                KeyEvent.KEYCODE_BUTTON_MODE -> { if (down) openMenu(); return true }
            }
            if (startHeld && selectHeld) { openMenu(); return true }
            if (press(event.keyCode, down, event.action == KeyEvent.ACTION_UP)) return true
            if (isPad(event)) return true // nothing else a controller does should leave the game
        }
        // With the menu or the options up, the controller moves around them
        // the way Android does it: d-pad to move, A to press, B to go back.
        return super.dispatchKeyEvent(event)
    }

    /** Presses or releases every NUON button mapped to this controller button. */
    private fun press(keyCode: Int, down: Boolean, up: Boolean): Boolean {
        // With trigger steering on, the triggers turn the claw and are not buttons.
        if (settings.triggersSteer && (keyCode == KeyEvent.KEYCODE_BUTTON_L2 || keyCode == KeyEvent.KEYCODE_BUTTON_R2)) return true
        var used = false
        settings.padMapping.forEachIndexed { button, mapped ->
            if (mapped == keyCode) {
                used = true
                if (down) Native.setButton(button, true) else if (up) Native.setButton(button, false)
            }
        }
        return used
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (playing != null && overlay == "none" && event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && event.action == MotionEvent.ACTION_MOVE) {
            // Triggers report on one pair of axes or the other, depending on the controller.
            val left = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
            val right = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
            var turn = if (settings.stickEnabled) event.getAxisValue(MotionEvent.AXIS_X) else 0f
            if (settings.triggersSteer) turn += right - left
            Native.setStick(turn.coerceIn(-1f, 1f), event.getAxisValue(MotionEvent.AXIS_Y))

            // Triggers that only report as axes still work as buttons when they are not steering.
            if (!settings.triggersSteer) {
                setTriggerButton(0, KeyEvent.KEYCODE_BUTTON_L2, left > 0.5f)
                setTriggerButton(1, KeyEvent.KEYCODE_BUTTON_R2, right > 0.5f)
            }
            // A d-pad that reports as a hat.
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            setHat(0, KeyEvent.KEYCODE_DPAD_LEFT, hx < -0.5f); setHat(1, KeyEvent.KEYCODE_DPAD_RIGHT, hx > 0.5f)
            setHat(2, KeyEvent.KEYCODE_DPAD_UP, hy < -0.5f); setHat(3, KeyEvent.KEYCODE_DPAD_DOWN, hy > 0.5f)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun setHat(index: Int, keyCode: Int, down: Boolean) {
        if (hat[index] != down) { hat[index] = down; press(keyCode, down, !down) }
    }

    private fun setTriggerButton(index: Int, keyCode: Int, down: Boolean) {
        if (triggerAsButton[index] != down) { triggerAsButton[index] = down; press(keyCode, down, !down) }
    }

    companion object { const val TAG = "nagori3k" }
}
