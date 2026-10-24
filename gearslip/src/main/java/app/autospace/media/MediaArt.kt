package app.autospace.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/** Cover art arrives as a content:// or https:// URI; this fetches and remembers it. */
object MediaArt {

    private val cache = LruCache<String, Bitmap>(48)

    suspend fun load(context: Context, uri: Uri?): Bitmap? {
        if (uri == null) return null
        val key = uri.toString()
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val stream = when (uri.scheme) {
                    "http", "https" -> URL(key).openConnection().apply {
                        connectTimeout = 5_000
                        readTimeout = 8_000
                    }.getInputStream()
                    else -> context.contentResolver.openInputStream(uri)
                } ?: return@runCatching null
                stream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()?.also { cache.put(key, it) }
        }
    }
}
