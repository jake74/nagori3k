package gg.dekiru.nagori3k

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer release, as GitHub describes it. */
data class UpdateInfo(val version: String, val pageUrl: String, val notes: String)

/**
 * Looking for a newer release. One request to GitHub, a quiet banner in the
 * library if there is one, and nothing downloaded or installed: the person
 * follows the link and decides. Nothing about the user is sent, only the
 * app's name and version in the User-Agent, which GitHub insists on.
 */
class Updates(private val context: Context, private val settings: Settings, private val scope: CoroutineScope) {
    /** The newest release found, if it is newer than this one. */
    var available by mutableStateOf<UpdateInfo?>(null)
        private set
    /** What "Check now" found, in words, for Options. */
    var status by mutableStateOf<String?>(null)
        private set
    var checking by mutableStateOf(false)
        private set

    /** For testing: the intent extra "updateUrl" replaces the address for one run and forces a check at launch. */
    var addressOverride: String? = null

    val currentVersion: String by lazy {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"
    }

    /** The banner shows this: a newer version that has not been skipped. */
    val banner: UpdateInfo? get() = available?.takeIf { it.version != settings.skippedUpdateVersion }

    fun skip() { available?.let { settings.skippedUpdateVersion = it.version } }

    /** When the app opens. Not while a game is running, not if the person has switched it off, and not more than once a day, unless a test forces it. */
    fun checkAtLaunch(playing: Boolean) {
        if (playing) return
        if (addressOverride == null) {
            if (!settings.checkForUpdates) return
            val last = settings.lastUpdateCheck
            if (last > 0 && System.currentTimeMillis() - last in 0 until INTERVAL) return
        }
        run(manual = false)
    }

    fun checkNow() {
        if (checking) return
        status = "Checking…"
        run(manual = true)
    }

    private fun run(manual: Boolean) {
        checking = true
        scope.launch {
            val current = currentVersion
            val (outcome, answered) = withContext(Dispatchers.IO) { fetch(addressOverride ?: ADDRESS, current) }
            if (answered) settings.lastUpdateCheck = System.currentTimeMillis()
            when (outcome) {
                is Outcome.Newer -> {
                    val info = outcome.info
                    available = info
                    // Asking by hand brings back a banner that was dismissed earlier.
                    if (manual) settings.skippedUpdateVersion = ""
                    if (!manual && info.version == settings.skippedUpdateVersion) log("skipped ${info.version}")
                    else log("available ${info.version} (this is $current)")
                    if (manual) status = "nagori3k ${info.version} is available"
                }
                Outcome.UpToDate -> {
                    available = null
                    log("up to date ($current)")
                    if (manual) status = "You have the latest version ($current)"
                }
                is Outcome.Failed -> {
                    log("check failed (${outcome.reason})")
                    if (manual) status = "Could not check for updates"
                }
            }
            checking = false
        }
    }

    private fun log(line: String) { Log.i(MainActivity.TAG, "nagori3k: update: $line") }

    sealed class Outcome {
        class Newer(val info: UpdateInfo) : Outcome()
        object UpToDate : Outcome()
        class Failed(val reason: String) : Outcome()
    }

    companion object {
        const val ADDRESS = "https://api.github.com/repos/jake74/nagori3k/releases/latest"
        const val RELEASES_PAGE = "https://github.com/jake74/nagori3k/releases"
        const val INTERVAL = 24L * 3600 * 1000

        /**
         * "v0.10.1" gives [0, 10, 1]. Reading stops at the first character that
         * is not a digit or a dot, so "0.2-beta" is 0.2. Empty when there is no number at all.
         */
        fun versionParts(text: String): List<Int> {
            val numeric = text.trim().removePrefix("v").removePrefix("V").takeWhile { it in '0'..'9' || it == '.' }
            if (numeric.none { it in '0'..'9' }) return emptyList()
            return numeric.split('.').map { it.toIntOrNull() ?: 0 }
        }

        /** The version as it is shown and remembered: no "v", no suffix. */
        fun cleanVersion(text: String) = versionParts(text).joinToString(".")

        /** Component by component, a missing one counting as 0: 0.10.0 is newer than 0.9.2, 0.1 is not newer than 0.1.0. */
        fun isNewer(latest: String, current: String): Boolean {
            val a = versionParts(latest)
            val b = versionParts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /**
         * `answered` is true when something came back at all, even an error
         * page: that is what counts as an attempt for the 24 hours.
         */
        fun fetch(address: String, current: String): Pair<Outcome, Boolean> {
            val text: String
            try {
                if (address.startsWith("/") || address.startsWith("file:")) {
                    // Only for testing. A file is not an HTTP connection, so it is read directly.
                    val file = if (address.startsWith("/")) File(address) else File(Uri.parse(address).path ?: "")
                    text = file.readText()
                } else {
                    val connection = URL(address).openConnection() as HttpURLConnection
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    connection.setRequestProperty("User-Agent", "nagori3k/$current")
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000
                    try {
                        val code = connection.responseCode
                        if (code != 200) return Pair(Outcome.Failed("HTTP $code"), true)
                        text = connection.inputStream.use { String(it.readBytes()) }
                    } finally { connection.disconnect() }
                }
            } catch (e: Exception) {
                // The kind of failure goes in the log only; the person is told nothing either way.
                return Pair(Outcome.Failed("no answer: ${e.javaClass.simpleName}"), false)
            }
            val json = try { JSONObject(text) } catch (e: Exception) { return Pair(Outcome.Failed("unreadable answer"), true) }
            val tag = json.optString("tag_name")
            if (tag.isEmpty()) return Pair(Outcome.Failed("unreadable answer"), true)
            if (versionParts(tag).isEmpty()) return Pair(Outcome.Failed("no version in tag"), true)
            if (!isNewer(tag, current)) return Pair(Outcome.UpToDate, true)
            // Only a web address is ever opened; anything else falls back to the releases page.
            val page = json.optString("html_url").takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: RELEASES_PAGE
            return Pair(Outcome.Newer(UpdateInfo(cleanVersion(tag), page, json.optString("body"))), true)
        }
    }
}
