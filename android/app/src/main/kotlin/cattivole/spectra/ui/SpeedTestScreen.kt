package cattivole.spectra.ui

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
import java.util.Locale

/** The LAN tab's way in to the full speed test. */
fun LazyListScope.speedTestRow(model: SpectraModel, nav: Navigator): @Composable () -> Unit = {
    val tester = model.speed
    val result = tester.result
    ListRow(
        title = tr("Speed test"),
        subtitle = when {
            tester.running -> tr("Measuring…")
            result != null -> listOfNotNull(result.downBps?.let { "↓ " + speedRate(it) }, result.upBps?.let { "↑ " + speedRate(it) }).joinToString("  ")
            else -> tr("Eight seconds down and eight up to Cloudflare, with latency under load.")
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

@Composable
fun SpeedTestScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    val tester = model.speed
    val result = tester.result
    SpectraScreen(title = tr("Speed test"), onBack = nav::back) {
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
                SpeedChart(tester.downSamples, tester.upSamples, Modifier.fillMaxWidth().height(120.dp).padding(top = 16.dp))
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
        if (result != null && tester.throttled) {
            card(key = "throttled", rows = listOf {
                NoteRow(tr("Cloudflare limited this test after several in a row from your address, so the figures may be lower than the line can do."), warning = true)
            })
        }
        if (result != null) {
            card(
                header = tr("Result"),
                key = "result",
                rows = buildList {
                    result.idleLatencyMs?.let { add { InfoRow(tr("Latency"), tr("%d ms", it)) } }
                    result.jitterMs?.let { add { InfoRow(tr("Jitter"), tr("%d ms", it)) } }
                    listOfNotNull(result.loadedDownLatencyMs, result.loadedUpLatencyMs).maxOrNull()?.let {
                        add { InfoRow(tr("Latency under load"), tr("%d ms", it)) }
                    }
                    result.bufferbloatGrade?.let { add { InfoRow(tr("Bufferbloat"), it) } }
                    result.server?.let { add { InfoRow(tr("Edge"), "Cloudflare $it", mono = true) } }
                },
            )
        }
        card(
            key = "start",
            footer = tr("Several streams for eight seconds each way to Cloudflare, which sees your public IP address. On mobile data this can use a few hundred megabytes."),
            rows = listOf {
                ActionRow(
                    when {
                        tester.running -> tr("Stop")
                        result != null -> tr("Run again")
                        else -> tr("Start")
                    },
                    if (tester.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    onClick = { if (tester.running) tester.stop() else tester.start() },
                )
            },
        )
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

/** Quarter-second throughput of both directions on one scale. */
@Composable
private fun SpeedChart(down: List<Double>, up: List<Double>, modifier: Modifier = Modifier) {
    val downColor = Spectra.colors.info
    val upColor = Spectra.colors.success
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val peak = (down + up).maxOrNull()?.takeIf { it > 0 } ?: return@Canvas
        val slots = (SpeedTester.SECONDS * 4).toInt()
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        fun trace(samples: List<Double>, color: Color) {
            if (samples.size < 2) return
            val path = Path()
            samples.forEachIndexed { index, value ->
                val x = size.width * index / (slots - 1).coerceAtLeast(1)
                val y = size.height - (value / peak).toFloat() * size.height
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        trace(down, downColor)
        trace(up, upColor)
    }
}
