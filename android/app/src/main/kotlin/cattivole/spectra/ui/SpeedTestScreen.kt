package cattivole.spectra.ui

import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import cattivole.spectra.SpectraModel
import cattivole.spectra.platform.SpeedTester
import cattivole.spectra.core.SpeedResult
import cattivole.spectra.core.SpeedServerChoice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import java.util.Locale

/** The Internet tab's way in to the full speed test. */
fun LazyListScope.speedTestRow(model: SpectraModel, nav: Navigator): @Composable () -> Unit = {
    val tester = model.speed
    val result = tester.result
    ListRow(
        title = tr("Speed test"),
        subtitle = when {
            tester.running -> tr("Measuring…")
            result != null -> listOfNotNull(result.downBps?.let { "↓ " + speedRate(it) }, result.upBps?.let { "↑ " + speedRate(it) }).joinToString("  ")
            else -> tr("Eight seconds down and eight up, to Cloudflare or a Measurement Lab server you pick, with latency under load.")
        },
        leading = { IconTile(Icons.Rounded.Speed, MaterialTheme.colorScheme.primary) },
        onClick = { nav.speedTest() },
    )
}

internal fun speedRate(bps: Double): String = when {
    bps >= 1_000_000 -> tr("%.1f Mbit/s", bps / 1_000_000.0)
    bps >= 1_000 -> String.format(Locale.getDefault(), tr("%.0f kbit/s"), bps / 1_000.0)
    else -> String.format(Locale.getDefault(), tr("%.0f bit/s"), bps)
}

/** The picked server, as the row shows it. */
private fun serverLabel(tester: SpeedTester): String = when (val choice = tester.serverChoice) {
    SpeedServerChoice.Cloudflare -> tr("Cloudflare, nearest")
    is SpeedServerChoice.MLab -> {
        val machine = choice.machine
        if (machine == null) {
            tr("M-Lab, nearest")
        } else {
            val found = tester.mlabServers.firstOrNull { it.machine == machine }
            if (found != null && found.city.isNotEmpty()) "M-Lab · ${found.city}" else "M-Lab · ${machine.substringBefore('.')}"
        }
    }
}

/** What the latency under load means for calls and games. */
private fun verdict(result: SpeedResult): String {
    val grade = result.bufferbloatGrade
    val idle = result.idleLatencyMs
    if (grade == null || idle == null) return tr("Throughput is the steady part of each transfer, after the first second of ramp-up.")
    val loaded = listOfNotNull(result.loadedDownLatencyMs, result.loadedUpLatencyMs).maxOrNull() ?: idle
    return when (grade) {
        "A", "B" -> tr("Latency stays near %d ms even with the line full: calls and games will not suffer while something downloads.", loaded)
        "C" -> tr("Latency grows to %d ms under load. Noticeable in calls while someone else streams or downloads.", loaded)
        else -> tr("Latency climbs to %d ms under load: a full queue in the router or modem. Smart queue management (SQM, QoS) on the router fixes this.", loaded)
    }
}

@Composable
fun SpeedTestScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val tester = model.speed
    val result = tester.result
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        SpeedServerDialog(tester) { picking = false }
    }
    SpectraScreen(
        title = tr("Speed test"),
        onBack = nav::back,
        actions = { AskAssistantButton(model, nav, subject = { cattivole.spectra.assistant.CardSubject.Internet }) },
    ) {
        freeCard("live") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SpeedFigure(
                    tr("Download"),
                    when {
                        tester.phase == SpeedTester.Phase.DOWNLOAD -> tester.liveBps
                        else -> result?.downBps ?: cattivole.spectra.core.SpeedResult.settled(tester.downSamples)
                    },
                    Spectra.colors.info,
                    Modifier.weight(1f),
                )
                SpeedFigure(
                    tr("Upload"),
                    when {
                        tester.phase == SpeedTester.Phase.UPLOAD -> tester.liveBps
                        else -> result?.upBps
                    },
                    Spectra.colors.success,
                    Modifier.weight(1f),
                )
            }
            if (tester.downSamples.isNotEmpty() || tester.upSamples.isNotEmpty()) {
                SpeedChart(tester.downSamples, tester.upSamples, Modifier.fillMaxWidth().padding(top = 16.dp))
            }
        }
        if (tester.running) {
            card(key = "progress", rows = listOf {
                ProgressRow(
                    when (tester.phase) {
                        SpeedTester.Phase.DOWNLOAD -> tr("Download")
                        SpeedTester.Phase.UPLOAD -> tr("Upload")
                        else -> tr("Latency")
                    },
                    tester.progress,
                )
            })
        }
        if (tester.phase == SpeedTester.Phase.FAILED) {
            val why = tester.failure
            card(key = "failed", rows = listOf {
                NoteRow(
                    listOfNotNull(
                        tr("Could not measure the speed."),
                        why?.let { (reason, detail) -> if (detail == null) tr(reason) else tr(reason, detail) },
                    ).joinToString(" "),
                    warning = true,
                )
            })
        }
        tester.substituted?.let { (wanted, stand) ->
            card(key = "substituted", rows = listOf {
                NoteRow(tr("M-Lab does not offer %s right now, so %s ran the test.", wanted, stand), warning = false)
            })
        }
        if (result != null && tester.throttled && !result.mlab) {
            card(key = "throttled", rows = listOf {
                NoteRow(tr("Cloudflare limited this test after several in a row from your address, so the figures may be lower than the line can do."), warning = true)
            })
        }
        if (result != null) {
            card(
                header = tr("Result"),
                key = "result",
                footer = verdict(result),
                rows = buildList {
                    result.idleLatencyMs?.let { add { InfoRow(tr("Latency"), tr("%d ms", it)) } }
                    result.jitterMs?.let { add { InfoRow(tr("Jitter"), tr("%d ms", it)) } }
                    listOfNotNull(result.loadedDownLatencyMs, result.loadedUpLatencyMs).maxOrNull()?.let {
                        add { InfoRow(tr("Latency under load"), tr("%d ms", it)) }
                    }
                    result.bufferbloatGrade?.let { add { InfoRow(tr("Bufferbloat"), it) } }
                    add { InfoRow(tr("Streams"), if (result.mlab) "1 ↓ · 1 ↑" else "${result.streams} ↓ · ${SpeedTester.UPLOAD_STREAMS} ↑") }
                    result.server?.let { add { InfoRow(tr("Server"), if (result.mlab) "M-Lab · $it" else "Cloudflare · $it") } }
                },
            )
        }
        card(
            key = "start",
            footer = if (tester.serverChoice is SpeedServerChoice.MLab) {
                tr("Eight seconds down and eight up over one stream each way to a Measurement Lab server. M-Lab publishes every measurement, your IP address included, as open data. Latency is measured before and during the transfers.")
            } else {
                tr("Several streams for eight seconds each way to Cloudflare, which sees your public IP address. On mobile data this can use a few hundred megabytes.")
            },
            rows = listOf({
                InfoRow(
                    tr("Server"),
                    serverLabel(tester),
                    valueColor = MaterialTheme.colorScheme.primary,
                    onClick = { if (!tester.running) picking = true },
                )
            }, {
                ActionRow(
                    when {
                        tester.running -> tr("Stop")
                        result != null -> tr("Run again")
                        else -> tr("Start")
                    },
                    if (tester.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    onClick = { if (tester.running) tester.stop() else tester.start() },
                )
            }),
        )
    }
}

/** Cloudflare, or one of the Measurement Lab machines near this network. */
@Composable
private fun SpeedServerDialog(tester: SpeedTester, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { if (tester.mlabServers.isEmpty()) tester.loadServers() }
    val pick = { choice: SpeedServerChoice ->
        tester.serverChoice = choice
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Server")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ServerOption(tr("Cloudflare"), tr("Nearest edge, chosen by the network"), tester.serverChoice == SpeedServerChoice.Cloudflare) {
                    pick(SpeedServerChoice.Cloudflare)
                }
                Text(
                    tr("Cloudflare answers on one address everywhere and the network routes you to the nearest of its sites, so a particular one cannot be picked. Several parallel streams fill fast lines."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                Text("Measurement Lab", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                ServerOption(tr("Nearest"), tr("Picked by M-Lab at test time"), tester.serverChoice == SpeedServerChoice.MLab(null)) {
                    pick(SpeedServerChoice.MLab(null))
                }
                tester.mlabServers.forEach { server ->
                    val place = listOf(server.city, java.util.Locale("", server.country).displayCountry)
                        .filter { it.isNotEmpty() }.joinToString(", ")
                    ServerOption(place.ifEmpty { server.shortName }, server.shortName, tester.serverChoice == SpeedServerChoice.MLab(server.machine)) {
                        pick(SpeedServerChoice.MLab(server.machine))
                    }
                }
                if (tester.loadingServers) {
                    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(tr("Asking M-Lab for servers…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (tester.mlabServers.isEmpty()) {
                    TextButton(onClick = tester::loadServers) { Text(tr("Load servers")) }
                }
                Text(
                    tr("M-Lab offers the few machines nearest to this network. It measures over one stream each way and publishes every result, your IP address included, as open data."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } },
    )
}

@Composable
private fun ServerOption(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SpeedFigure(label: String, bps: Double?, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
        Text(
            bps?.let { String.format(Locale.getDefault(), "%.1f", it / 1_000_000.0) } ?: "—",
            style = MaterialTheme.typography.displaySmall.merge(Tabular),
        )
        Text(tr("Mbit/s"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Quarter-second throughput of both directions on one scale: smoothed lines over a soft fill, a light grid with Mbit/s marks. */
@Composable
private fun SpeedChart(down: List<Double>, up: List<Double>, modifier: Modifier = Modifier) {
    val downColor = Spectra.colors.info
    val upColor = Spectra.colors.success
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.merge(Tabular).copy(color = labelColor)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(132.dp)) {
            val peakBps = (down + up).maxOrNull()?.takeIf { it > 0 } ?: return@Canvas
            // A round top for the scale: 10, 20, 50, 100… Mbit/s, so the marks read as plain numbers.
            val top = niceCeiling(peakBps / 1_000_000.0)
            val slots = (SpeedTester.SECONDS * 4).toInt()
            val labelWidth = measurer.measure("000", labelStyle).size.width.toFloat() + 6.dp.toPx()
            val chartLeft = 0f
            val chartRight = size.width - labelWidth
            val chartTop = 6.dp.toPx()
            val chartBottom = size.height - 2.dp.toPx()
            val height = chartBottom - chartTop
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
            // Grid: the baseline solid, halves and the top dashed, each with its value on the right.
            for (step in 0..2) {
                val value = top * step / 2
                val y = chartBottom - height * step / 2f
                drawLine(
                    grid,
                    Offset(chartLeft, y),
                    Offset(chartRight, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = if (step == 0) null else dash,
                )
                if (step > 0) {
                    val text = measurer.measure(formatMbit(value), labelStyle)
                    drawText(text, topLeft = Offset(chartRight + 6.dp.toPx(), (y - text.size.height / 2f).coerceAtLeast(0f)))
                }
            }
            fun point(index: Int, value: Double) = Offset(
                chartLeft + (chartRight - chartLeft) * index / (slots - 1).coerceAtLeast(1),
                chartBottom - (value / 1_000_000.0 / top).toFloat().coerceIn(0f, 1f) * height,
            )
            fun trace(samples: List<Double>, color: Color) {
                if (samples.size < 2) return
                val points = samples.mapIndexed(::point)
                // Smooth: a cubic through the midpoints, so quarter-second jitter reads as a line, not a saw.
                val line = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (index in 1 until points.size) {
                        val previous = points[index - 1]
                        val current = points[index]
                        val midX = (previous.x + current.x) / 2f
                        cubicTo(midX, previous.y, midX, current.y, current.x, current.y)
                    }
                }
                val fill = Path().apply {
                    addPath(line)
                    lineTo(points.last().x, chartBottom)
                    lineTo(points.first().x, chartBottom)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.02f)), startY = chartTop, endY = chartBottom))
                drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val last = points.last()
                drawCircle(color.copy(alpha = 0.25f), radius = 6.dp.toPx(), center = last)
                drawCircle(color, radius = 3.dp.toPx(), center = last)
            }
            trace(down, downColor)
            trace(up, upColor)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ChartLegend(tr("Download"), downColor)
            if (up.isNotEmpty()) ChartLegend(tr("Upload"), upColor)
            Spacer(Modifier.weight(1f))
            Text(tr("Mbit/s"), style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
    }
}

@Composable
private fun ChartLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(width = 14.dp, height = 3.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 1, 2, 5 × 10ⁿ at or above [value]: the top of the speed scale. */
internal fun niceCeiling(value: Double): Double {
    if (value <= 0) return 1.0
    val magnitude = Math.pow(10.0, Math.floor(Math.log10(value)))
    return listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= value - 1e-9 }
}

private fun formatMbit(value: Double): String =
    if (value >= 10 || value == Math.floor(value)) String.format(Locale.getDefault(), "%.0f", value) else String.format(Locale.getDefault(), "%.1f", value)
