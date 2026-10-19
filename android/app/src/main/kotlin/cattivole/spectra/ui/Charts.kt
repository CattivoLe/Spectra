package cattivole.spectra.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cattivole.spectra.core.AccessPointReading
import cattivole.spectra.core.SignalQuality
import java.text.DateFormat
import java.util.Date
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow

/**
 * Empty arcs and bars. A quieter shade of the icon's own colour, so the
 * unfilled part stays visible on the card without a flat grey.
 */
@Composable
private fun signalTrack(base: Color): Color =
    lerp(
        MaterialTheme.colorScheme.surfaceContainerHigh,
        base,
        if (Spectra.colors.isDark) 0.32f else 0.22f,
    )

/** The Wi-Fi fan, its arcs lit up to the signal level. */
@Composable
fun WifiGlyph(rssi: Int, inRange: Boolean = true, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val lit = if (inRange) signalColor(rssi) else MaterialTheme.colorScheme.outline
    val dim = signalTrack(lit)
    val fraction = if (inRange) SignalQuality.fraction(rssi) else 0f
    Canvas(modifier.size(size)) {
        val centre = Offset(this.size.width / 2, this.size.height * 0.84f)
        val stroke = this.size.width * 0.11f
        val arcs = 3
        // The dot counts as the first step, then each arc in turn.
        val steps = arcs + 1
        val shown = (fraction * steps + 0.25f).toInt().coerceIn(if (inRange) 1 else 0, steps)
        drawCircle(if (shown >= 1) lit else dim, radius = stroke * 0.85f, center = centre)
        for (index in 1..arcs) {
            val radius = this.size.width * (0.16f + 0.155f * index)
            drawArc(
                color = if (shown > index) lit else dim,
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(centre.x - radius, centre.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** Four bars for a signal that is not Wi-Fi: Bluetooth, a probing phone. */
@Composable
fun SignalBars(rssi: Int, inRange: Boolean = true, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val lit = if (inRange) signalColor(rssi) else MaterialTheme.colorScheme.outline
    val dim = signalTrack(lit)
    val fraction = if (inRange) SignalQuality.fraction(rssi) else 0f
    Canvas(modifier.size(size)) {
        val bars = 4
        val gap = this.size.width * 0.12f
        val width = (this.size.width - gap * (bars - 1)) / bars
        for (index in 0 until bars) {
            val height = this.size.height * (0.3f + 0.7f * (index + 1) / bars)
            drawRoundRect(
                color = if (fraction + 0.02f >= (index + 1f) / bars) lit else dim,
                topLeft = Offset(index * (width + gap), this.size.height - height),
                size = Size(width, height),
                cornerRadius = CornerRadius(width / 2, width / 2),
            )
        }
    }
}

/**
 * A small line of recent samples, scaled to the values themselves with at
 * least a 12 dB window, so a steady signal is a calm line and not noise.
 */
@Composable
fun Sparkline(values: List<Int>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lowest = values.min().toDouble()
        val highest = values.max().toDouble()
        val span = maxOf(highest - lowest, 12.0)
        val low = (lowest + highest - span) / 2
        val step = size.width / (values.size - 1)
        val line = Path()
        values.forEachIndexed { index, value ->
            val y = size.height * (1 - ((value - low) / span)).toFloat()
            if (index == 0) line.moveTo(0f, y) else line.lineTo(index * step, y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)))
        drawPath(line, color, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Signal over time with a dBm scale on the left and clock times under it.
 * [samples] are (epoch milliseconds, dBm).
 */
@Composable
fun SignalHistoryChart(
    samples: List<Pair<Long, Int>>,
    color: Color,
    modifier: Modifier = Modifier,
    floor: Int = -100,
    ceiling: Int = -20,
    placeholder: String = tr("The graph fills in as scans come in"),
) {
    val measurer = rememberTextMeasurer()
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = axis).merge(Tabular)
    val clock = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    Box(modifier.fillMaxWidth().height(150.dp)) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            val left = 30.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val top = 6.dp.toPx()
            val width = size.width - left
            fun y(value: Int): Float = top + (bottom - top) * (1 - (value.coerceIn(floor, ceiling) - floor).toFloat() / (ceiling - floor))
            for (mark in listOf(-90, -70, -50, -30).filter { it in floor..ceiling }) {
                val at = y(mark)
                drawLine(grid, Offset(left, at), Offset(size.width, at), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                val text = measurer.measure("$mark", labelStyle)
                drawText(text, topLeft = Offset(left - text.size.width - 6.dp.toPx(), at - text.size.height / 2))
            }
            if (samples.size < 2) return@Canvas
            val start = samples.first().first
            val end = samples.last().first
            val span = (end - start).coerceAtLeast(1)
            fun x(time: Long): Float = left + width * ((time - start).toFloat() / span)
            val line = Path()
            samples.forEachIndexed { index, (time, value) ->
                if (index == 0) line.moveTo(x(time), y(value)) else line.lineTo(x(time), y(value))
            }
            val area = Path().apply {
                addPath(line)
                lineTo(x(end), bottom)
                lineTo(x(start), bottom)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.02f)), startY = top, endY = bottom))
            drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(color, radius = 3.5.dp.toPx(), center = Offset(x(end), y(samples.last().second)))
            for ((index, time) in listOf(start, start + span / 2, end).withIndex()) {
                val text = measurer.measure(clock.format(Date(time)), labelStyle)
                val px = x(time)
                val shownX = when (index) {
                    0 -> px
                    2 -> px - text.size.width
                    else -> px - text.size.width / 2
                }
                drawText(text, topLeft = Offset(shownX, bottom + 4.dp.toPx()))
            }
        }
        if (samples.size < 2) {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodySmall,
                color = axis,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** Hues for the humps of the spectrum, picked so neighbours differ. */
private val SpectrumHues = listOf(
    Color(0xFF38BDF8), Color(0xFFA78BFA), Color(0xFF34D399), Color(0xFFF472B6),
    Color(0xFFFBBF24), Color(0xFF60A5FA), Color(0xFFFB923C), Color(0xFF2DD4BF),
    Color(0xFFE879F9), Color(0xFFA3E635),
)

fun spectrumHue(index: Int): Color = SpectrumHues[index % SpectrumHues.size]

/**
 * One hump per network: its width is the channels it covers, its height how
 * loud it is here. The loudest few carry their names, skipping any label that
 * would land on one already placed.
 */
@Composable
fun ChannelSpectrum(
    readings: List<AccessPointReading>,
    modifier: Modifier = Modifier,
    ticks: List<Int> = (1..13).toList(),
    domain: ClosedFloatingPointRange<Double>? = null,
    height: Dp = 230.dp,
) {
    val measurer = rememberTextMeasurer()
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val onSurface = MaterialTheme.colorScheme.onSurface
    val labelStyle = TextStyle(fontSize = 10.sp, color = axis).merge(Tabular)
    val nameStyle = TextStyle(fontSize = 10.sp, color = onSurface)
    val floor = -100.0
    val loudest = (readings.maxOfOrNull { it.rssi } ?: -70).toDouble()
    val ceiling = minOf(-20.0, maxOf(floor + 25, ceil((loudest + 5) / 5) * 5))
    val range = domain ?: run {
        val low = minOf(ticks.first().toDouble(), readings.minOfOrNull { it.occupiedChannels.start } ?: ticks.first().toDouble())
        val high = maxOf(ticks.last().toDouble(), readings.maxOfOrNull { it.occupiedChannels.endInclusive } ?: ticks.last().toDouble())
        (low - 0.5)..(high + 0.5)
    }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val left = 30.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val top = 4.dp.toPx()
        fun x(channel: Double): Float = left + (size.width - left) * ((channel - range.start) / (range.endInclusive - range.start)).toFloat()
        fun y(dbm: Double): Float = top + (bottom - top) * (1 - ((dbm - floor) / (ceiling - floor))).toFloat()
        val step = if (ceiling - floor > 50) 20 else 10
        var mark = -90
        while (mark <= ceiling) {
            val at = y(mark.toDouble())
            drawLine(grid, Offset(left, at), Offset(size.width, at), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val text = measurer.measure("$mark", labelStyle)
            drawText(text, topLeft = Offset(left - text.size.width - 6.dp.toPx(), at - text.size.height / 2))
            mark += step
        }
        for (tick in ticks) {
            val px = x(tick.toDouble())
            drawLine(grid, Offset(px, bottom), Offset(px, bottom + 3.dp.toPx()), strokeWidth = 1f)
            val text = measurer.measure("$tick", labelStyle)
            drawText(text, topLeft = Offset(px - text.size.width / 2, bottom + 4.dp.toPx()))
        }
        drawLine(grid, Offset(left, bottom), Offset(size.width, bottom), strokeWidth = 1.2f)
        // Quietest first, so a loud network is drawn over the faint ones.
        val ordered = readings.withIndex().sortedBy { it.value.rssi }
        for ((index, reading) in ordered) {
            drawHump(reading, spectrumHue(index), ::x, ::y, floor, bottom)
        }
        val placed = mutableListOf<Pair<Float, Float>>()
        for (reading in readings.sortedByDescending { it.rssi }) {
            if (placed.size >= 6) break
            val span = reading.occupiedChannels
            val px = x((span.start + span.endInclusive) / 2)
            val py = y(reading.rssi.toDouble())
            if (placed.any { abs(it.first - px) < 70.dp.toPx() && abs(it.second - py) < 18.dp.toPx() }) continue
            placed += px to py
            val text = measurer.measure(
                if (reading.ssid == null) tr(reading.displayName) else reading.displayName,
                nameStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = 110.dp.roundToPx()),
            )
            val boxLeft = (px - text.size.width / 2).coerceIn(left, size.width - text.size.width)
            val boxTop = (py - text.size.height - 6.dp.toPx()).coerceAtLeast(0f)
            drawRoundRect(
                surface.copy(alpha = 0.85f),
                topLeft = Offset(boxLeft - 3.dp.toPx(), boxTop - 1.dp.toPx()),
                size = Size(text.size.width + 6.dp.toPx(), text.size.height + 2.dp.toPx()),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            drawText(text, topLeft = Offset(boxLeft, boxTop))
        }
    }
}

private fun DrawScope.drawHump(
    reading: AccessPointReading,
    color: Color,
    x: (Double) -> Float,
    y: (Double) -> Float,
    floor: Double,
    bottom: Float,
) {
    val span = reading.occupiedChannels
    val centre = (span.start + span.endInclusive) / 2
    val half = (span.endInclusive - span.start) / 2
    val path = Path()
    val steps = 40
    path.moveTo(x(span.start), bottom)
    for (step in 0..steps) {
        val channel = span.start + (span.endInclusive - span.start) * step / steps
        val t = abs(channel - centre) / half
        // Flat on top, falling away at the edges, like a real spectral mask.
        val shape = if (t <= 0.6) 1.0 else cos((t - 0.6) / 0.4 * PI / 2).pow(2)
        path.lineTo(x(channel), y(floor + (reading.rssi - floor) * shape))
    }
    path.lineTo(x(span.endInclusive), bottom)
    path.close()
    drawPath(path, Brush.verticalGradient(listOf(color.copy(alpha = 0.38f), color.copy(alpha = 0.06f)), endY = bottom))
    drawPath(path, color, style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round))
}

/** How busy a channel is, as a filled bar. */
@Composable
fun LevelBar(level: Double, modifier: Modifier = Modifier) {
    val color = levelColor(level)
    Box(modifier.height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(level.toFloat().coerceIn(0.02f, 1f))
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color))),
        )
    }
}
