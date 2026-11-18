package cattivole.spectra.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.HopPlace
import cattivole.spectra.core.RouteTrace
import cattivole.spectra.core.TraceHop
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

private val TracePresets = listOf("1.1.1.1", "8.8.8.8", "google.com", "ya.ru")

/** A traceroute from this phone to a target the person picks, drawn on a map. */
@Composable
fun TraceScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val link = model.connection
    val prefs = LocalContext.current.getSharedPreferences("spectra", Context.MODE_PRIVATE)
    var target by rememberSaveable { mutableStateOf(prefs.getString("traceTarget", null) ?: RouteTrace.DEFAULT_TARGET) }
    val focus = LocalFocusManager.current
    val start = {
        focus.clearFocus()
        prefs.edit().putString("traceTarget", target).apply()
        link.traceRoute(target)
    }
    SpectraScreen(title = tr("Traceroute"), onBack = nav::back) {
        freeCard("target", header = tr("Target"), footer = routeText(link.route)) {
            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                placeholder = { Text(tr("Site or IP address")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { start() }),
                modifier = Modifier.fillMaxWidth(),
            )
            ChipRow(contentPadding = PaddingValues(top = 10.dp)) {
                TracePresets.forEach { preset -> ToggleChip(preset, target == preset, { target = preset }) }
            }
        }
        card(key = "run", rows = buildList {
            if (link.routeRunning) {
                add { ProgressRow(tr(link.routePhase.ifEmpty { "Tracing the path" }), null) }
                add { ActionRow(tr("Stop"), Icons.Rounded.Stop, onClick = link::stopRoute) }
            } else {
                add {
                    ActionRow(
                        if (link.routeTrace == null) tr("Trace") else tr("Trace again"),
                        Icons.Rounded.Route,
                        onClick = start,
                        enabled = RouteTrace.target(target) != null,
                    )
                }
            }
            link.routeFailure?.let { failure -> add { NoteRow(tr(failure), warning = true) } }
        })
        val trace = link.routeTrace
        if (trace != null && trace.hops.isNotEmpty()) {
            val path = RouteTrace.path(trace.hops, link.routePlaces)
            if (path.isNotEmpty()) {
                freeCard(
                    "map",
                    footer = tr("Hops are placed by ipwho.is from their addresses, which it sees. A router's registered place can be far from where it stands, so the map is a rough picture."),
                    padding = PaddingValues(0.dp),
                ) {
                    RouteMap(path, Modifier.fillMaxWidth().height(300.dp))
                }
            }
            val address = link.routeAddress
            cardSection(
                trace.hops.withIndex().toList(),
                header = tr("Path to %s", link.routeTarget ?: trace.target),
                footer = if (address != null && address != link.routeTarget) {
                    tr("%s is %s. One probe per hop; a hop that does not answer shows as an asterisk.", link.routeTarget.orEmpty(), address)
                } else {
                    tr("One probe per hop; a hop that does not answer shows as an asterisk.")
                },
                key = { it.index },
                sectionKey = "hops",
            ) { (index, hop) -> HopLine(index + 1, hop, hop.address?.let { link.routePlaces[it] }) }
        }
    }
}

@Composable
private fun HopLine(ttl: Int, hop: TraceHop, place: HopPlace?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "$ttl",
            style = MaterialTheme.typography.titleSmall.merge(Tabular),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(hop.address ?: "*", style = MaterialTheme.typography.bodyLarge.merge(Tabular))
            hop.name?.let { name ->
                Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            val detail = when {
                place != null -> listOfNotNull(
                    listOfNotNull(place.city, place.countryCode?.let { Locale("", it).displayCountry.ifEmpty { it } }).joinToString(", ").ifEmpty { null },
                    place.isp,
                ).joinToString(" · ")
                hop.address != null && !RouteTrace.isPublic(hop.address) -> tr("Private network")
                else -> null
            }
            if (!detail.isNullOrEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        hop.millis?.let { ms ->
            Text(tr("%d ms", ms), style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The placed hops joined by a line, numbered by their place on the path. */
@Composable
private fun RouteMap(path: List<Pair<Int, HopPlace>>, modifier: Modifier) {
    val dark = Spectra.colors.isDark
    val line = MaterialTheme.colorScheme.primary.toArgb()
    val end = Spectra.colors.success.toArgb()
    var drawn by remember { mutableStateOf("") }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(300.dp),
            factory = { context ->
                prepareOsm(context)
                MapView(context).apply {
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    setTileSource(TileSourceFactory.MAPNIK)
                    overlayManager.tilesOverlay.setColorFilter(if (dark) darkMapFilter else null)
                    controller.setZoom(2.0)
                    onResume()
                }
            },
            onRelease = { map ->
                map.onPause()
                map.onDetach()
            },
            update = { map ->
                val stamp = path.joinToString { "${it.first}:${it.second.latitude},${it.second.longitude}" } + dark
                if (drawn == stamp) return@AndroidView
                drawn = stamp
                map.overlayManager.tilesOverlay.setColorFilter(if (dark) darkMapFilter else null)
                map.overlays.clear()
                val points = path.map { GeoPoint(it.second.latitude, it.second.longitude) }
                map.overlays.add(Polyline(map).apply {
                    setPoints(points)
                    outlinePaint.color = line
                    outlinePaint.strokeWidth = 4f * map.resources.displayMetrics.density
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                })
                path.forEachIndexed { index, (ttl, place) ->
                    map.overlays.add(Marker(map).apply {
                        position = GeoPoint(place.latitude, place.longitude)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = android.graphics.drawable.BitmapDrawable(
                            map.resources,
                            hopBitmap(map.context, if (index == path.lastIndex) end else line, ttl),
                        )
                        title = listOfNotNull("$ttl", place.city, place.isp).joinToString(" · ")
                    })
                }
                map.invalidate()
                map.post { fitRoute(map, points) }
            },
        )
        Text(
            "© OpenStreetMap",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun fitRoute(map: MapView, points: List<GeoPoint>) {
    if (map.width == 0 || map.height == 0) {
        map.post { fitRoute(map, points) }
        return
    }
    if (points.size == 1) {
        map.controller.setZoom(6.0)
        map.controller.setCenter(points.first())
        return
    }
    val box = BoundingBox.fromGeoPoints(points)
    // A route inside one city still gets some context around it.
    val padded = if (box.latitudeSpan < 1 && box.longitudeSpanWithDateLine < 1) box.increaseByScale(4f) else box
    map.zoomToBoundingBox(padded, false, (map.width * 0.12f).toInt().coerceAtLeast(40))
}

private fun hopBitmap(context: Context, color: Int, number: Int): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (26 * density).toInt().coerceAtLeast(26)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = size / 2f - 1.5f * density
    canvas.drawCircle(size / 2f, size / 2f, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    canvas.drawCircle(size / 2f, size / 2f, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        this.color = android.graphics.Color.WHITE
    })
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
        typeface = Typeface.DEFAULT_BOLD
    }
    canvas.drawText("$number", size / 2f, size / 2f - (text.descent() + text.ascent()) / 2f, text)
    return bitmap
}
