package cattivole.spectra.assistant.ui

import alexcattivo.localchat.kit.MarkdownBlock
import alexcattivo.localchat.kit.MarkdownParser
import cattivole.spectra.ui.tr
import cattivole.spectra.ui.Spectra
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Ответ модели с оформлением: абзацы, заголовки, списки, цитаты и блоки кода. Как MarkdownView.swift. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (block in blocks) Block(block)
        }
    }
}

@Composable
private fun Block(block: MarkdownBlock) {
    val type = MaterialTheme.typography
    val palette = NetworkPalette.current()
    when (block) {
        is MarkdownBlock.Paragraph -> Text(highlighted(block.text, palette), style = type.bodyLarge)
        is MarkdownBlock.Heading -> Text(
            highlighted(block.text, palette),
            style = when (block.level) { 1 -> type.titleLarge; 2 -> type.titleMedium; else -> type.titleSmall },
            fontWeight = FontWeight.SemiBold,
        )
        is MarkdownBlock.ListItem -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
            Text(block.marker, style = type.bodyLarge, modifier = Modifier.width(if (block.marker == "•") 16.dp else 28.dp))
            Text(highlighted(block.text, palette), style = type.bodyLarge)
        }
        is MarkdownBlock.Quote -> Row {
            Text(
                highlighted(block.text, palette),
                style = type.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            )
        }
        is MarkdownBlock.Code -> Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                .padding(10.dp),
        ) {
            Text(
                block.language ?: tr("Code"),
                style = type.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                block.text,
                fontFamily = FontFamily.Monospace,
                style = type.bodyMedium,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

/** Жирный, курсив, `код` и ссылки внутри строки. Сломанная разметка показывается как есть. */
private fun inline(source: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    fun closing(marker: String, from: Int) = source.indexOf(marker, from).takeIf { it > from }
    while (index < source.length) {
        when {
            source.startsWith("**", index) && closing("**", index + 2) != null -> {
                val end = closing("**", index + 2)!!
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(source.substring(index + 2, end))) }
                index = end + 2
            }
            source.startsWith("`", index) && closing("`", index + 1) != null -> {
                val end = closing("`", index + 1)!!
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(source.substring(index + 1, end)) }
                index = end + 1
            }
            (source[index] == '*' || source[index] == '_') && closing(source[index].toString(), index + 1) != null &&
                source.getOrNull(index + 1)?.isWhitespace() == false -> {
                val marker = source[index].toString()
                val end = closing(marker, index + 1)!!
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(source.substring(index + 1, end))) }
                index = end + 1
            }
            source[index] == '[' -> {
                val close = source.indexOf("](", index)
                val end = if (close > 0) source.indexOf(')', close) else -1
                if (close > 0 && end > close) {
                    val url = source.substring(close + 2, end)
                    withLink(LinkAnnotation.Url(url)) { append(source.substring(index + 1, close)) }
                    index = end + 1
                } else {
                    append(source[index]); index++
                }
            }
            else -> { append(source[index]); index++ }
        }
    }
}


/** The colours network data is drawn in, from the app's theme so they read in dark mode too. Mirrors iOS. */
internal class NetworkPalette(
    val address: Color,
    val hardware: Color,
    val port: Color,
    val protocol: Color,
    val strong: Color,
    val fair: Color,
    val weak: Color,
) {
    companion object {
        @Composable
        fun current() = NetworkPalette(
            address = Spectra.colors.info,
            hardware = Spectra.colors.warning,
            port = Spectra.colors.revealed,
            protocol = MaterialTheme.colorScheme.primary,
            strong = Spectra.colors.success,
            fair = Spectra.colors.warning,
            weak = Spectra.colors.danger,
        )
    }
}

private val addressPattern = Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(?::\d{1,5})?(?:/\d{1,2})?\b""")
private val hardwarePattern = Regex("""\b[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}\b""")
private val portPattern = Regex("""\b\d{1,5}/(?:tcp|udp)\b""")
private val protocolPattern = Regex(
    """\b(?:HTTPS?|SSH|RTSP|RTMP|SFTP|FTP|DNS|DHCP|SNMP|NTP|SMB|AFP|MQTT|WPA[23]?|WEP|WPS|ARP|ICMP|TCP|UDP|TLS|SSL|mDNS|UPnP|SSDP)\b""",
)
private val signalPattern = Regex("""-(\d{2,3})\s*(?:dBm|дБм)""")

/**
 * The inline markup plus colour for what a network answer is made of: IP addresses (with a port or mask), MAC
 * addresses and BSSIDs, `port/tcp`, protocol names, and signal levels coloured by how strong they are.
 */
private fun highlighted(source: String, palette: NetworkPalette): AnnotatedString {
    val base = inline(source)
    val text = base.text
    return buildAnnotatedString {
        append(base)
        for ((pattern, color) in listOf(
            addressPattern to palette.address,
            hardwarePattern to palette.hardware,
            portPattern to palette.port,
            protocolPattern to palette.protocol,
        )) {
            for (match in pattern.findAll(text)) addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
        }
        for (match in signalPattern.findAll(text)) {
            val dBm = match.groupValues[1].toIntOrNull() ?: continue
            val color = when {
                dBm < 50 -> palette.strong
                dBm < 70 -> palette.fair
                else -> palette.weak
            }
            addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
        }
    }
}
