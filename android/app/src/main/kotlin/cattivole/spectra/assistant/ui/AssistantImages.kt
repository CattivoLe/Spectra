package cattivole.spectra.assistant.ui

import alexcattivo.localchat.kit.web.ImageResult
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cattivole.spectra.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Pictures a «как выглядит…» question brought, in a row above the answer. A tap shows one larger. */
@Composable
internal fun ImageStrip(images: List<ImageResult>) {
    var open by remember { mutableStateOf<ImageResult?>(null) }
    open?.let { image -> ImageViewer(image) { open = null } }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(images, key = { it.image }) { image ->
            RemoteImage(
                url = image.thumbnail,
                description = image.title,
                modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)).clickable { open = image },
            )
        }
    }
}

@Composable
private fun ImageViewer(image: ImageResult, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The full picture when it loads; the preview stands in meanwhile and if it does not.
                RemoteImage(
                    url = image.image,
                    fallback = image.thumbnail,
                    description = image.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp).clip(RoundedCornerShape(12.dp)),
                    maxSide = 1280,
                )
                if (image.title.isNotEmpty()) Text(image.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(image.host, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(image.page))) }
                    }) { Text(tr("Open the page")) }
                    TextButton(onClick = onDismiss) { Text(tr("Done")) }
                }
            }
        }
    }
}

/**
 * A picture from the web. Loaded without cookies, cut down to [maxSide] pixels and kept in a small memory cache: only
 * the address is requested, nothing about the phone or the chat goes with it.
 */
@Composable
private fun RemoteImage(
    url: String,
    description: String,
    modifier: Modifier = Modifier,
    fallback: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    maxSide: Int = 360,
) {
    val state by produceState<LoadState>(LoadState.Loading, url) {
        value = RemoteImages.load(url, maxSide)?.let(LoadState::Loaded)
            ?: fallback?.let { RemoteImages.load(it, maxSide) }?.let(LoadState::Loaded)
            ?: LoadState.Failed
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        when (val current = state) {
            LoadState.Loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            LoadState.Failed -> Icon(Icons.Rounded.BrokenImage, description, tint = MaterialTheme.colorScheme.outline)
            is LoadState.Loaded -> Image(current.bitmap, description, Modifier.matchParentSize(), contentScale = contentScale)
        }
    }
}

private sealed interface LoadState {
    data object Loading : LoadState
    data object Failed : LoadState
    data class Loaded(val bitmap: ImageBitmap) : LoadState
}

internal object RemoteImages {
    private const val MAX_BYTES = 6 * 1024 * 1024
    private val cache = LruCache<String, ImageBitmap>(24)

    suspend fun load(url: String, maxSide: Int): ImageBitmap? {
        val key = "$maxSide|$url"
        cache.get(key)?.let { return it }
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    useCaches = false
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    setRequestProperty("Accept", "image/*")
                }
                try {
                    if (connection.responseCode !in 200..299) return@runCatching null
                    val bytes = connection.inputStream.use { input ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(chunk)
                            if (read < 0) break
                            buffer.write(chunk, 0, read)
                            if (buffer.size() > MAX_BYTES) return@runCatching null
                        }
                        buffer.toByteArray()
                    }
                    decode(bytes, maxSide)
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        } ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun decode(bytes: ByteArray, maxSide: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }
}
