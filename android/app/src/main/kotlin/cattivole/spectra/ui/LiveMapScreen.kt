package cattivole.spectra.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import cattivole.spectra.SpectraModel
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * A live, follow-the-walk map for war-driving: the phone's position, a
 * breadcrumb track of where it has been, and the located scans already in
 * History as coverage dots. The track is kept only for the session.
 */
@Composable
fun LiveMapScreen(model: SpectraModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val dark = Spectra.colors.isDark
    val pins = remember(model.generation) { mapPins(model.history.entries) }
    val pinColors = pins.associate { pin ->
        val kinds = pin.entries.map { it.kind }.distinct()
        pin.id to (if (kinds.size == 1) kindTint(kinds.first()) else MaterialTheme.colorScheme.primary).toArgb()
    }
    val track = remember { mutableStateListOf<GeoPoint>() }
    var recording by remember { mutableStateOf(false) }
    val recordingState = rememberUpdatedState(recording)
    val trackColor = MaterialTheme.colorScheme.primary.toArgb()

    val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    // Feed the breadcrumb track from system location updates while recording.
    DisposableEffect(hasLocation) {
        val manager = context.getSystemService(LocationManager::class.java)
        val listener = LocationListener { location: Location ->
            if (!recordingState.value) return@LocationListener
            val point = GeoPoint(location.latitude, location.longitude)
            val last = track.lastOrNull()
            if (last == null || last.distanceToAsDouble(point) >= 8.0) {
                track.add(point)
            }
        }
        if (hasLocation && manager != null) {
            runCatching {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)
            }
            runCatching {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener)
            }
        }
        onDispose { manager?.removeUpdates(listener) }
    }

    val polyline = remember {
        Polyline().apply {
            outlinePaint.strokeWidth = 10f
            outlinePaint.color = trackColor
        }
    }

    SpectraScreen(
        title = tr("Live map"),
        onBack = onClose,
        actions = {},
        overlay = {
            if (!hasLocation) {
                Text(
                    tr("Location is needed to show your position."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
        },
        fill = {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    prepareOsm(ctx)
                    MapView(ctx).apply {
                        setMultiTouchControls(true)
                        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                        setTileSource(TileSourceFactory.MAPNIK)
                        overlayManager.tilesOverlay.setColorFilter(if (dark) darkMapFilter else null)
                        controller.setZoom(16.0)

                        for (pin in pins) {
                            val marker = Marker(this)
                            marker.position = GeoPoint(pin.latitude, pin.longitude)
                            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            marker.icon = BitmapDrawable(
                                resources,
                                liveDot(ctx, pinColors[pin.id] ?: trackColor),
                            )
                            marker.setInfoWindow(null)
                            overlays.add(marker)
                        }
                        overlays.add(polyline)

                        if (hasLocation) {
                            val myLocation = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this)
                            myLocation.enableMyLocation()
                            myLocation.enableFollowLocation()
                            myLocation.runOnFirstFix {
                                post { controller.animateTo(myLocation.myLocation) }
                            }
                            overlays.add(myLocation)
                        }
                        onResume()
                    }
                },
                onRelease = { map ->
                    map.onPause()
                    map.onDetach()
                },
                update = { map ->
                    map.overlayManager.tilesOverlay.setColorFilter(if (dark) darkMapFilter else null)
                    polyline.setPoints(track.toList())
                    map.invalidate()
                },
            )
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { recording = !recording },
                    modifier = Modifier.weight(1f),
                    colors = if (recording) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Icon(if (recording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord, null)
                    Text(if (recording) tr("Recording") else tr("Record track"), Modifier.padding(start = 8.dp))
                }
                if (track.isNotEmpty()) {
                    OutlinedButton(onClick = { track.clear() }) {
                        Icon(Icons.Rounded.Delete, contentDescription = tr("Clear"))
                    }
                }
            }
        },
    )
}

private fun liveDot(context: Context, color: Int): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (20 * density).toInt().coerceAtLeast(20)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        this.color = android.graphics.Color.WHITE
    }
    val radius = size / 2f - ring.strokeWidth
    canvas.drawCircle(size / 2f, size / 2f, radius, fill)
    canvas.drawCircle(size / 2f, size / 2f, radius, ring)
    return bitmap
}
