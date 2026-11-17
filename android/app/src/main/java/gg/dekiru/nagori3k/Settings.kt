package gg.dekiru.nagori3k

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

val nuonButtonNames = listOf("Up", "Down", "Left", "Right", "A", "B", "Start", "NUON", "L", "R", "C Up", "C Left", "C Down", "C Right")

/** The controller buttons the mapping screen knows by name. */
val padKeyNames = linkedMapOf(
    KeyEvent.KEYCODE_DPAD_UP to "D-pad Up", KeyEvent.KEYCODE_DPAD_DOWN to "D-pad Down",
    KeyEvent.KEYCODE_DPAD_LEFT to "D-pad Left", KeyEvent.KEYCODE_DPAD_RIGHT to "D-pad Right",
    KeyEvent.KEYCODE_BUTTON_A to "A / Cross", KeyEvent.KEYCODE_BUTTON_B to "B / Circle",
    KeyEvent.KEYCODE_BUTTON_X to "X / Square", KeyEvent.KEYCODE_BUTTON_Y to "Y / Triangle",
    KeyEvent.KEYCODE_BUTTON_L1 to "L1", KeyEvent.KEYCODE_BUTTON_R1 to "R1",
    KeyEvent.KEYCODE_BUTTON_L2 to "L2", KeyEvent.KEYCODE_BUTTON_R2 to "R2",
    KeyEvent.KEYCODE_BUTTON_START to "Start / Options", KeyEvent.KEYCODE_BUTTON_SELECT to "Select / Create",
    KeyEvent.KEYCODE_BUTTON_THUMBL to "L3", KeyEvent.KEYCODE_BUTTON_THUMBR to "R3",
)

// The same layout as the Mac app's defaults: NUON A is A / Cross and NUON B is B / Circle.
val defaultPadMapping = listOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
    KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
    KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
)

/**
 * Every setting, kept in the app's preferences and, where the core has an
 * option for it, passed on. Same names and defaults as the Mac app, except
 * that trigger steering starts switched on here.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun bool(key: String, default: Boolean) = mutableStateOf(prefs.getBoolean(key, default))
    private fun int(key: String, default: Int) = mutableStateOf(prefs.getInt(key, default))

    private val _libraryPath = mutableStateOf(prefs.getString("libraryPath", "") ?: "")
    var libraryPath: String
        get() = _libraryPath.value
        set(v) { _libraryPath.value = v; prefs.edit().putString("libraryPath", v).apply() }

    private val _stickEnabled = bool("stickEnabled", true)
    private val _triggersSteer = bool("triggersSteer", true)
    private val _stickSensitivity = int("stickSensitivity", 140)
    private val _stickWeight = int("stickWeight", 100)
    private val _stickReversed = bool("stickReversed", false)
    private val _scanlines = bool("scanlines", false)
    private val _stretch = bool("stretchToFill", false)
    private val _showFrameRate = bool("showFrameRate", false)
    private val _frameLimit = int("frameLimit", 30)
    private val _autoFire = bool("autoFire", false)
    private val _touchControls = mutableStateOf(prefs.getString("touchControls", "auto") ?: "auto")
    private val _skipControllerMessage = bool("skipControllerMessage", true)
    private val _keepHighScores = bool("keepHighScores", true)
    private val _checkForUpdates = bool("checkForUpdates", true)
    private val _skippedUpdateVersion = mutableStateOf(prefs.getString("skippedUpdateVersion", "") ?: "")

    var stickEnabled by _stickEnabled; private set
    var triggersSteer by _triggersSteer; private set
    var stickSensitivity by _stickSensitivity; private set
    var stickWeight by _stickWeight; private set
    var stickReversed by _stickReversed; private set
    var scanlines by _scanlines; private set
    var stretch by _stretch; private set
    var showFrameRate by _showFrameRate; private set
    var frameLimit by _frameLimit; private set
    var autoFire by _autoFire; private set
    /** When the on-screen controls show: "auto" (when no controller is in use), "always" or "never". */
    var touchControls by _touchControls; private set
    var skipControllerMessage by _skipControllerMessage; private set
    var keepHighScores by _keepHighScores; private set
    var checkForUpdates by _checkForUpdates; private set

    /** The release the person chose not to be told about again. See Updates.kt. */
    var skippedUpdateVersion: String
        get() = _skippedUpdateVersion.value
        set(v) { _skippedUpdateVersion.value = v; prefs.edit().putString("skippedUpdateVersion", v).apply() }

    /** When a check last got an answer, in milliseconds; 0 for never. */
    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0L)
        set(v) { prefs.edit().putLong("lastUpdateCheck", v).apply() }

    private val _padMapping = mutableStateOf(
        prefs.getString("padMapping", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == nuonButtonNames.size } ?: defaultPadMapping
    )
    val padMapping: List<Int> get() = _padMapping.value

    fun set(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        when (key) {
            "stickEnabled" -> stickEnabled = value
            "triggersSteer" -> triggersSteer = value
            "stickReversed" -> stickReversed = value
            "scanlines" -> scanlines = value
            "autoFire" -> autoFire = value
            "stretchToFill" -> stretch = value
            "showFrameRate" -> showFrameRate = value
            "skipControllerMessage" -> skipControllerMessage = value
            "keepHighScores" -> keepHighScores = value
            "checkForUpdates" -> checkForUpdates = value
        }
        apply()
    }

    fun set(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
        when (key) {
            "stickSensitivity" -> stickSensitivity = value
            "stickWeight" -> stickWeight = value
            "frameLimit" -> frameLimit = value
        }
        apply()
    }

    fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        if (key == "touchControls") { touchControls = value; apply() }
    }

    fun map(button: Int, keyCode: Int) {
        _padMapping.value = padMapping.toMutableList().also { it[button] = keyCode }
        prefs.edit().putString("padMapping", padMapping.joinToString(",")).apply()
    }

    fun resetMapping() {
        _padMapping.value = defaultPadMapping
        prefs.edit().remove("padMapping").apply()
    }

    /** Everything the core is told, in its own option names. */
    fun apply() {
        // The triggers go through the same flywheel as the stick, so either one needs it.
        // The on-screen stick goes the same way as a controller's, so it needs the analogue mode too.
        Native.setOption("nuance_controller", if (stickEnabled || triggersSteer || touchControls != "never") "analog stick" else "d-pad only")
        Native.setOption("nuance_spinner_sensitivity", "$stickSensitivity%")
        Native.setOption("nuance_spinner_inertia", "$stickWeight%")
        Native.setOption("nuance_spinner_invert", if (stickReversed) "reversed" else "normal")
        Native.setOption("nuance_scanlines", if (scanlines) "enabled" else "disabled")
        Native.setOption("nuance_max_fps", if (frameLimit > 0) "$frameLimit" else "unlimited")
        Native.setOption("nuance_skip_controller_message", if (skipControllerMessage) "enabled" else "disabled")
        Native.setOption("nuance_t3k_high_scores", if (keepHighScores) "keep" else "do not keep")
        Native.setStretch(stretch)
        Native.setShowFps(showFrameRate)
    }
}
