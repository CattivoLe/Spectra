package cattivole.spectra.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cattivole.spectra.core.HistoryEntry
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** One dot on the history map: every saved scan within about 55 m. */
internal class HistoryPin(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val entries: List<HistoryEntry>,
)

internal fun mapPins(entries: List<HistoryEntry>): List<HistoryPin> {
    val located = entries.filter { it.latitude != null && it.longitude != null }
    return located.groupBy { pinKey(it.latitude!!, it.longitude!!) }.map { (key, rows) ->
        val newest = rows.maxBy { it.savedAt }
        HistoryPin(
            id = key,
            latitude = rows.map { it.latitude!! }.average(),
            longitude = rows.map { it.longitude!! }.average(),
            label = newest.placeLabel ?: String.format(Locale.US, "%.4f, %.4f", newest.latitude, newest.longitude),
            entries = rows.sortedByDescending { it.savedAt },
        )
    }.sortedBy { it.id }
}

/** Same ~55 m bucket the history store uses when it merges a place. */
private fun pinKey(latitude: Double, longitude: Double): String {
    val scale = 2000.0
    return "${(latitude * scale).roundToInt()}_${(longitude * scale).roundToInt()}"
}

/**
 * CARTO's dark raster tiles now answer with an "API KEY REQUIRED" picture.
 * The same OpenStreetMap tiles, passed through the usual night-mode matrix
 * (invert, then hue-rotate 180 so water stays blue and parks stay green).
 */
internal val darkMapFilter = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ).apply {
        postConcat(
            ColorMatrix(
                floatArrayOf(
                    -0.574f, 1.430f, 0.144f, 0f, 0f,
                    0.426f, 0.430f, 0.144f, 0f, 0f,
                    0.426f, 1.430f, -0.856f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        postConcat(
            ColorMatrix(
                floatArrayOf(
                    0.95f, 0f, 0f, 0f, 0f,
                    0f, 0.95f, 0f, 0f, 0f,
                    0f, 0f, 0.95f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        postConcat(
            ColorMatrix(
                floatArrayOf(
                    0.9f, 0f, 0f, 0f, 12.75f,
                    0f, 0.9f, 0f, 0f, 12.75f,
                    0f, 0f, 0.9f, 0f, 12.75f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    },
)

/**
 * Saved scans that have a fix, one point per place. Scans without a fix stay
 * reachable from the button, since a map has nowhere to put them.
 */
@Composable
internal fun HistoryPlaceMap(entries: List<HistoryEntry>, onOpen: (String) -> Unit) {
    val pins = mapPins(entries)
    val unlocated = entries.filter { it.latitude == null || it.longitude == null }
        .sortedByDescending { it.savedAt }
    val dark = Spectra.colors.isDark
    val pinColors = pins.associate { it.id to pinColor(it).toArgb() }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showUnlocated by remember { mutableStateOf(false) }
    val selected = pins.firstOrNull { it.id == selectedId }
    val open = rememberUpdatedState(onOpen)
    var drawn by remember { mutableStateOf("") }
    var fitted by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                prepareOsm(context)
                MapView(context).apply {
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    setTileSource(TileSourceFactory.MAPNIK)
                    overlayManager.tilesOverlay.setColorFilter(if (dark) darkMapFilter else null)
                    controller.setZoom(3.0)
                    onResume()
                }
            },
            onRelease = { map ->
                map.onPause()
                map.onDetach()
            },
            update = { map ->
                map.overlayManager.tilesOverlay.setColorFilter(if (dark) darkMapFilter else null)
                val ids = pins.joinToString { it.id }
                val stamp = ids + ":" + dark
                if (drawn != stamp) {
                    map.overlays.clear()
                    map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(point: GeoPoint?): Boolean {
                            selectedId = null
                            showUnlocated = false
                            return true
                        }

                        override fun longPressHelper(point: GeoPoint?) = false
                    }))
                    for (pin in pins) {
                        val marker = Marker(map)
                        marker.position = GeoPoint(pin.latitude, pin.longitude)
                        marker.relatedObject = pin
                        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        marker.icon = android.graphics.drawable.BitmapDrawable(
                            map.resources,
                            pinBitmap(map.context, pinColors[pin.id] ?: pinColorFallback, pin.entries.size),
                        )
                        marker.title = pin.label
                        marker.setOnMarkerClickListener { _, _ ->
                            if (pin.entries.size == 1) open.value(pin.entries.first().id)
                            else selectedId = pin.id
                            true
                        }
                        map.overlays.add(marker)
                    }
                    map.invalidate()
                    drawn = stamp
                }
                if (fitted != ids && pins.isNotEmpty()) {
                    val snapshot = pins.toList()
                    map.post { fit(map, snapshot) }
                    fitted = ids
                }
            },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selected != null) {
                PinCard(selected, onOpen = {
                    selectedId = null
                    onOpen(it)
                })
            }
            if (!showUnlocated && unlocated.isNotEmpty()) {
                Surface(
                    onClick = { showUnlocated = true },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 4.dp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        tr("%d without a location", unlocated.size),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            if (showUnlocated && unlocated.isNotEmpty()) {
                PinCard(
                    title = tr("No location"),
                    entries = unlocated,
                    onOpen = {
                        showUnlocated = false
                        onOpen(it)
                    },
                )
            }
            Text(
                "© OpenStreetMap",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.Start)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun pinColor(pin: HistoryPin): Color {
    val kinds = pin.entries.map { it.kind }.distinct()
    return if (kinds.size == 1) kindTint(kinds.first()) else MaterialTheme.colorScheme.primary
}

private val pinColorFallback = android.graphics.Color.parseColor("#5ED3F4")

@Composable
private fun PinCard(pin: HistoryPin, onOpen: (String) -> Unit) {
    PinCard(pin.label, pin.entries, onOpen)
}

@Composable
private fun PinCard(title: String, entries: List<HistoryEntry>, onOpen: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            for (entry in entries) {
                val kind = entry.shownKinds().first()
                ListRow(
                    title = tr(entry.rowTitle),
                    subtitle = "${ago(entry.savedAt)} · ${sourceName(entry.source)}",
                    subtitleLines = 1,
                    leading = { IconTile(kindIcon(kind), kindTint(kind)) },
                    trailing = { Pill("${entry.itemCount}", kindTint(kind)) },
                    onClick = { onOpen(entry.id) },
                )
            }
        }
    }
}

internal fun prepareOsm(context: Context) {
    val config = Configuration.getInstance()
    val base = File(context.cacheDir, "osmdroid")
    config.osmdroidBasePath = base
    config.osmdroidTileCache = File(base, "tiles")
    config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    config.userAgentValue = context.packageName
}

private fun fit(map: MapView, pins: List<HistoryPin>) {
    if (map.width == 0 || map.height == 0) {
        map.post { fit(map, pins) }
        return
    }
    val lats = pins.map { it.latitude }
    val lons = pins.map { it.longitude }
    val latSpan = (lats.max() - lats.min())
    val lonSpan = (lons.max() - lons.min())
    if (latSpan < 0.002 && lonSpan < 0.002) {
        map.controller.setZoom(16.0)
        map.controller.setCenter(GeoPoint(lats.average(), lons.average()))
        return
    }
    val box = BoundingBox(lats.max(), lons.max(), lats.min(), lons.min())
    map.zoomToBoundingBox(box, true, (map.width * 0.15f).toInt().coerceAtLeast(48))
}

private fun pinBitmap(context: Context, color: Int, count: Int): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (36 * density).toInt().coerceAtLeast(36)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        this.color = android.graphics.Color.WHITE
    }
    val radius = size / 2f - ring.strokeWidth
    canvas.drawCircle(size / 2f, size / 2f, radius, fill)
    canvas.drawCircle(size / 2f, size / 2f, radius, ring)
    if (count > 1) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 13f * density
            typeface = Typeface.DEFAULT_BOLD
        }
        val label = if (count > 99) "99+" else count.toString()
        val baseline = size / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, size / 2f, baseline, text)
    } else {
        canvas.drawCircle(size / 2f, size / 2f, radius * 0.28f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
        })
    }
    return bitmap
}
