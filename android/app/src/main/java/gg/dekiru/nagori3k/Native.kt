package gg.dekiru.nagori3k

/** The host layer (host/nagori_host.h), through android/app/src/main/cpp/nagori_jni.cpp. */
object Native {
    init { System.loadLibrary("nagori") }

    // NUON buttons, in the host layer's order.
    const val UP = 0; const val DOWN = 1; const val LEFT = 2; const val RIGHT = 3
    const val A = 4; const val B = 5; const val START = 6; const val NUON = 7
    const val L = 8; const val R = 9
    const val C_UP = 10; const val C_LEFT = 11; const val C_DOWN = 12; const val C_RIGHT = 13

    @JvmStatic external fun init(corePath: String, systemDir: String, saveDir: String, cacheDir: String, perf: Boolean): Boolean
    @JvmStatic external fun setOption(key: String, value: String)
    @JvmStatic external fun setEnvironment(key: String, value: String)
    @JvmStatic external fun loadGame(path: String): Boolean
    @JvmStatic external fun contextReady()
    @JvmStatic external fun drawFrame(viewWidth: Int, viewHeight: Int)
    @JvmStatic external fun setButton(button: Int, down: Boolean)
    @JvmStatic external fun setStick(x: Float, y: Float)
    /** Not used at present (the touch controls steer through setStick). A relative turn, as from a mouse: the core scales it by 0.25 x sensitivity and applies "reverse direction" itself. */
    @JvmStatic external fun addSpinner(counts: Float)
    @JvmStatic external fun setStretch(on: Boolean)
    @JvmStatic external fun setShowFps(on: Boolean)
    @JvmStatic external fun setPaused(on: Boolean)
    @JvmStatic external fun startAudio()
    @JvmStatic external fun stopAudio()
    @JvmStatic external fun unloadGame()
}
