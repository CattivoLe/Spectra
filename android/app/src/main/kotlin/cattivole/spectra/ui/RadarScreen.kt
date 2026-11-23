package cattivole.spectra.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.BleDevice
import cattivole.spectra.core.DistanceEstimate
import cattivole.spectra.core.Proximity
import cattivole.spectra.core.SignalQuality

/**
 * Walk-toward-it finder for a Bluetooth device: a ring that fills as the signal
 * strengthens, with a "warmer / colder" read from the recent trend. Useful for
 * locating a hidden tracker. Updates live while the device keeps advertising.
 */
@Composable
fun RadarScreen(model: SpectraModel, device: BleDevice, onClose: () -> Unit) {
    model.generation
    val rssi = device.rssi
    val nearby = device.isNearby(model.ble.now)
    val level = Proximity.level(rssi)
    val trend = Proximity.trend(device.samples.takeLast(12).map { it.rssi })
    val color = Spectra.colors.of(SignalQuality.of(rssi))
    val target = if (nearby) Proximity.closeness(rssi).toFloat() else 0f
    val fill by animateFloatAsState(target, label = "closeness")

    SpectraScreen(title = tr("Find"), subtitle = device.shownName(), onBack = onClose) {
        freeCard("radar") {
            Column(
                Modifier.fillMaxSize().padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
                    val ringColor = color
                    val trackColor = MaterialTheme.colorScheme.surfaceVariant
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = 20.dp.toPx()
                        val inset = stroke / 2
                        val arcSize = Size(size.width - stroke, size.height - stroke)
                        val topLeft = Offset(inset, inset)
                        drawArc(trackColor, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                        drawArc(
                            ringColor, -90f, 360f * fill, false, topLeft, arcSize,
                            style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$rssi", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                        Text(tr("dBm"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (nearby) {
                    Text(levelLabel(level), style = MaterialTheme.typography.headlineSmall, color = color)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val (icon, tint) = when (trend) {
                            Proximity.Trend.WARMER -> Icons.Rounded.LocalFireDepartment to Spectra.colors.warning
                            Proximity.Trend.COLDER -> Icons.Rounded.AcUnit to Spectra.colors.fair
                            Proximity.Trend.STEADY -> Icons.Rounded.DragHandle to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Icon(icon, contentDescription = null, tint = tint)
                        Text(trendLabel(trend), style = MaterialTheme.typography.titleMedium, color = tint)
                    }
                    Text(
                        distanceLabel(DistanceEstimate.metres(rssi)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        tr("Signal lost — it may be out of range."),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    tr("Move around. The ring fills and reads “Warmer” as you get closer."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

private fun distanceLabel(metres: Double): String = when {
    metres < 1 -> tr("≈ under 1 m away")
    metres < 10 -> tr("≈ %.1f m away", metres)
    else -> tr("≈ %d m away", metres.toInt())
}

private fun levelLabel(level: Proximity.Level): String = when (level) {
    Proximity.Level.HERE -> tr("Right here")
    Proximity.Level.CLOSE -> tr("Very close")
    Proximity.Level.NEAR -> tr("Close by")
    Proximity.Level.FAR -> tr("Nearby")
    Proximity.Level.VERY_FAR -> tr("Far")
}

private fun trendLabel(trend: Proximity.Trend): String = when (trend) {
    Proximity.Trend.WARMER -> tr("Warmer")
    Proximity.Trend.COLDER -> tr("Colder")
    Proximity.Trend.STEADY -> tr("Steady")
}
