package gg.dekiru.nagori3k

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One playable file in the library. */
data class Game(val file: File) {
    val title: String get() = file.nameWithoutExtension
}

/**
 * Disc images and programs the core can load, in the folder and up to two
 * levels below it. One entry per title: an .iso wins over anything else of
 * the same name.
 */
fun scanLibrary(root: File): List<Game> {
    val rank = mapOf("iso" to 0, "chd" to 1, "cof" to 2, "run" to 3)
    val best = HashMap<String, Pair<Int, File>>()
    fun visit(dir: File, depth: Int) {
        for (item in dir.listFiles() ?: return) {
            if (item.name.startsWith(".")) continue
            if (item.isDirectory) { if (depth < 2) visit(item, depth + 1); continue }
            val r = rank[item.extension.lowercase()] ?: continue
            val key = item.nameWithoutExtension.lowercase()
            val existing = best[key]
            if (existing == null || existing.first > r) best[key] = r to item
        }
    }
    visit(root, 0)
    return best.values.map { Game(it.second) }.sortedBy { it.title.lowercase() }
}

/**
 * Cover art, by the same rule as the Mac app. In order: a picture beside the
 * game (same name, or cover/folder/boxart, .png or .jpg); a picture fetched
 * before; Wikipedia's cover for an article with exactly the game's title.
 * A game without a match gets a plain tile: no art is better than the wrong art.
 */
class BoxArt(context: Context, private val scope: CoroutineScope) {
    val images = mutableStateMapOf<String, Bitmap>()
    private val requested = HashSet<String>()
    private val cacheDir = File(context.filesDir, "boxart").also { it.mkdirs() }

    fun image(game: Game): Bitmap? {
        val key = game.file.path
        images[key]?.let { return it }
        if (requested.add(key)) scope.launch {
            val bitmap = withContext(Dispatchers.IO) { load(game) }
            if (bitmap != null) images[key] = bitmap
        }
        return null
    }

    private fun load(game: Game): Bitmap? {
        val folder = game.file.parentFile
        for (name in listOf(game.title, "cover", "folder", "boxart")) for (ext in listOf("png", "jpg", "jpeg")) {
            val candidate = File(folder, "$name.$ext")
            if (candidate.canRead()) BitmapFactory.decodeFile(candidate.path)?.let { return it }
        }
        val cached = File(cacheDir, key(game.title) + ".img")
        if (cached.exists()) BitmapFactory.decodeFile(cached.path)?.let { return it }
        // A title that had no match is asked about again after a week.
        val missing = File(cacheDir, key(game.title) + ".none")
        if (missing.exists() && System.currentTimeMillis() - missing.lastModified() < 7L * 86400 * 1000) return null

        val data = runCatching { fetchFromWikipedia(game.title) }.getOrNull()
        val bitmap = data?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bitmap != null) cached.writeBytes(data) else runCatching { missing.writeBytes(ByteArray(0)); missing.setLastModified(System.currentTimeMillis()) }
        return bitmap
    }

    companion object {
        private const val AGENT = "nagori3k/0.1 (https://github.com/jake74/nagori3k)"

        /** Letters and digits only, lower case: "FreeFall 3050 A.D" and "Freefall 3050 A.D." are the same title. */
        fun key(title: String) = title.lowercase().filter { it.isLetterOrDigit() }

        private fun get(url: String): ByteArray? {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", AGENT)
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            return try {
                if (connection.responseCode == 200) connection.inputStream.use { it.readBytes() } else null
            } finally { connection.disconnect() }
        }

        fun fetchFromWikipedia(title: String): ByteArray? {
            val search = URLEncoder.encode("$title Nuon video game", "UTF-8")
            val api = "https://en.wikipedia.org/w/api.php?action=query&format=json&prop=pageimages&piprop=thumbnail" +
                "&pithumbsize=600&pilicense=any&generator=search&gsrsearch=$search&gsrlimit=5"
            val json = JSONObject(String(get(api) ?: return null))
            val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return null
            val wanted = key(title)
            for (id in pages.keys()) {
                val page = pages.getJSONObject(id)
                if (key(page.optString("title")) != wanted) continue
                val source = page.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.isNotEmpty() } ?: continue
                return get(source)
            }
            return null
        }
    }
}
