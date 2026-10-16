package cattivole.spectra.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cattivole.spectra.core.SignalQuality
import kotlin.math.PI
import kotlin.math.sin

fun signalColor(rssi: Int): Color = when (SignalQuality.of(rssi)) {
    SignalQuality.EXCELLENT -> Color(0xFF2E7D32)
    SignalQuality.GOOD -> Color(0xFF00897B)
    SignalQuality.FAIR -> Color(0xFFF9A825)
    SignalQuality.WEAK -> Color(0xFFEF6C00)
    SignalQuality.POOR -> Color(0xFFC62828)
}

@Composable
fun SignalGlyph(rssi: Int, inRange: Boolean = true, modifier: Modifier = Modifier) {
    val color = if (inRange) signalColor(rssi) else MaterialTheme.colorScheme.outline
    val fraction = if (inRange) SignalQuality.fraction(rssi) else 0.05f
    Canvas(modifier.size(28.dp)) {
        val bars = 4
        val gap = size.width * 0.08f
        val barWidth = (size.width - gap * (bars - 1)) / bars
        for (index in 0 until bars) {
            val threshold = (index + 1) / bars.toFloat()
            val height = size.height * (0.35f + 0.65f * (index + 1) / bars)
            drawRoundRect(
                color = color.copy(alpha = if (fraction + 0.02f >= threshold) 1f else 0.25f),
                topLeft = androidx.compose.ui.geometry.Offset(index * (barWidth + gap), size.height - height),
                size = androidx.compose.ui.geometry.Size(barWidth, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
            )
        }
    }
}

@Composable
fun Sparkline(values: List<Int>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.height(28.dp).fillMaxWidth()) {
        if (values.size < 2) return@Canvas
        val lowest = values.min().toDouble()
        val highest = values.max().toDouble()
        val span = maxOf(highest - lowest, 12.0)
        val low = (lowest + highest - span) / 2
        val high = low + span
        val step = size.width / (values.size - 1)
        val path = androidx.compose.ui.graphics.Path()
        values.forEachIndexed { index, value ->
            val clamped = value.toDouble().coerceIn(low, high)
            val x = index * step
            val y = size.height * (1 - ((clamped - low) / (high - low))).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
    }
}

@Composable
fun InfoLine(label: String, value: String) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        },
    )
}

@Composable
fun Meter(label: String, fraction: Float, caption: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun EmptyText(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A hump per network: width is the channels it covers, height is how loud it is. */
@Composable
fun ChannelSpectrum(readings: List<cattivole.spectra.core.AccessPointReading>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(180.dp)) {
        val left = 1.0
        val right = 13.0
        fun xOf(channel: Double) = ((channel - left) / (right - left) * size.width).toFloat()
        readings.forEach { reading ->
            val range = reading.occupiedChannels
            val centre = (range.start + range.endInclusive) / 2
            val peak = SignalQuality.fraction(reading.rssi)
            val path = androidx.compose.ui.graphics.Path()
            val steps = 24
            for (step in 0..steps) {
                val t = step / steps.toDouble()
                val channel = range.start + (range.endInclusive - range.start) * t
                val bell = sin(t * PI).toFloat()
                val x = xOf(channel)
                val y = size.height - bell * peak * size.height * 0.9f
                if (step == 0) path.moveTo(x, size.height) 
                path.lineTo(x, y)
            }
            path.lineTo(xOf(range.endInclusive), size.height)
            path.close()
            drawPath(path, color.copy(alpha = 0.28f))
            val labelX = xOf(centre)
            drawCircle(color, radius = 4f, center = androidx.compose.ui.geometry.Offset(labelX, size.height * (1 - peak * 0.9f)))
        }
    }
}
