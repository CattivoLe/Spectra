package cattivole.spectra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.BodyText
import cattivole.spectra.core.JsonSyntax
import cattivole.spectra.core.OpenText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val PREVIEW = 3_000

/** A body as text: its start, coloured when it is JSON, and a way to the whole of it, where it can be searched. */
@Composable
fun BodyTextPreview(text: String, title: String, model: SpectraModel, nav: Navigator) {
    val copy = rememberCopier()
    val cut = text.length > PREVIEW
    val shown = if (cut) text.take(PREVIEW) else text
    val dark = Spectra.colors.isDark
    val styled = remember(shown, dark) {
        val spans = if (JsonSyntax.looksLikeJson(shown)) JsonSyntax.spans(shown) else emptyList()
        coloured(shown, 0, spans, dark)
    }
    SelectionContainer {
        Text(styled, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            model.traffic.openText = OpenText(title, text)
            nav.trafficText()
        }) { Text(if (cut) tr("Show all and search") else tr("Open full screen")) }
        TextButton(onClick = { copy(tr("Body"), text) }) { Text(tr("Copy")) }
    }
}

/** The whole text on its own screen, with a search over all of it: matches are counted, highlighted and stepped through. */
@Composable
fun BodyTextScreen(model: SpectraModel, nav: Navigator) {
    // Kept for as long as the screen is, whatever is opened next.
    val opened = remember { model.traffic.openText }
    if (opened == null) {
        SpectraScreen(tr("Body"), onBack = nav::back, overlay = {
            EmptyState(Icons.Rounded.DataObject, tr("Gone"), tr("This connection is no longer in the list."))
        }) {}
        return
    }
    val copy = rememberCopier()
    val body by produceState<BodyText?>(null, opened) {
        value = withContext(Dispatchers.Default) { BodyText(opened.text.take(BodyText.LIMIT)) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var matches by remember { mutableStateOf(IntArray(0)) }
    var current by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    LaunchedEffect(body, query) {
        val text = body ?: return@LaunchedEffect
        delay(150) // while it is being typed
        val found = withContext(Dispatchers.Default) { text.find(query) }
        matches = found
        // From what is on screen on, as a reader expects.
        val here = text.rows.getOrNull(list.firstVisibleItemIndex)?.start ?: 0
        current = found.indexOfFirst { it >= here }.coerceAtLeast(0)
    }
    LaunchedEffect(matches, current) {
        val text = body ?: return@LaunchedEffect
        val at = matches.getOrNull(current) ?: return@LaunchedEffect
        val row = text.rowOf(at)
        val visible = list.layoutInfo.visibleItemsInfo
        val inside = visible.drop(1).dropLast(1).any { it.index == row }
        if (!inside) list.scrollToItem((row - 3).coerceAtLeast(0))
    }
    val focus = LocalFocusManager.current
    fun step(by: Int) {
        // The keyboard would cover half of what is stepped through.
        focus.clearFocus()
        if (matches.isNotEmpty()) current = (current + by).mod(matches.size)
    }
    SpectraScreen(
        title = opened.title,
        onBack = nav::back,
        actions = {
            IconButton(onClick = { copy(tr("Body"), opened.text) }) { Icon(Icons.Rounded.ContentCopy, contentDescription = tr("Copy")) }
        },
        searchQuery = query,
        onSearchChange = { query = it },
        searchPlaceholder = tr("Find in text"),
        fill = {
            Column(Modifier.fillMaxSize()) {
                if (query.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when {
                                matches.isEmpty() -> tr("No matches")
                                matches.size >= BodyText.MAX_MATCHES -> tr("%d of more than %d", current + 1, matches.size)
                                else -> tr("%d of %d", current + 1, matches.size)
                            },
                            style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { step(-1) }, enabled = matches.isNotEmpty()) {
                            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = tr("Previous match"))
                        }
                        IconButton(onClick = { step(1) }, enabled = matches.isNotEmpty()) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = tr("Next match"))
                        }
                    }
                }
                if (opened.text.length > BodyText.LIMIT) {
                    Text(
                        tr("The first %s are shown.", tr("%d characters", BodyText.LIMIT)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
                val text = body
                if (text == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    val dark = Spectra.colors.isDark
                    val mark = Spectra.colors.warning.copy(alpha = 0.35f)
                    val strong = Spectra.colors.warning.copy(alpha = 0.85f)
                    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    SelectionContainer {
                        LazyColumn(
                            state = list,
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp + bottom),
                        ) {
                            itemsIndexed(text.rows, key = { index, _ -> index }) { _, row ->
                                val at = matches.getOrNull(current) ?: -1
                                val length = query.length
                                val near = hitsIn(matches, row, length)
                                val styled = remember(text, row, dark, near, if (at in near) at else -1) {
                                    coloured(text.text.substring(row.start, row.end), row.start, text.syntax(row), dark, near, length, at, mark, strong)
                                }
                                Text(styled, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        },
    ) {}
}

/** The matches that touch [row]; one may run over from the row before. */
private fun hitsIn(matches: IntArray, row: BodyText.Row, length: Int): List<Int> {
    if (matches.isEmpty()) return emptyList()
    var low = 0
    var high = matches.size
    while (low < high) {
        val middle = (low + high) / 2
        if (matches[middle] + length <= row.start) low = middle + 1 else high = middle
    }
    val hits = ArrayList<Int>()
    while (low < matches.size && matches[low] < row.end) hits.add(matches[low++])
    return hits
}

/** [text], which starts at [offset] of the whole, in its colours and with the [hits] of a search marked. */
private fun coloured(
    text: String,
    offset: Int,
    spans: List<JsonSyntax.Span>,
    dark: Boolean,
    hits: List<Int> = emptyList(),
    hitLength: Int = 0,
    current: Int = -1,
    mark: Color = Color.Unspecified,
    strong: Color = Color.Unspecified,
): AnnotatedString = buildAnnotatedString {
    append(text)
    fun style(style: SpanStyle, from: Int, to: Int) {
        val start = (from - offset).coerceIn(0, text.length)
        val end = (to - offset).coerceIn(0, text.length)
        if (start < end) addStyle(style, start, end)
    }
    for (span in spans) style(SpanStyle(color = span.kind.color(dark)), span.start, span.end)
    for (hit in hits) {
        val style = if (hit == current) SpanStyle(background = strong, color = Color.Black) else SpanStyle(background = mark)
        style(style, hit, hit + hitLength)
    }
}

/** The same colours as on iOS. */
private fun JsonSyntax.Kind.color(dark: Boolean): Color = when (this) {
    JsonSyntax.Kind.KEY -> if (dark) Color(0xFF79C0FF) else Color(0xFF0550AE)
    JsonSyntax.Kind.STRING -> if (dark) Color(0xFF7EE787) else Color(0xFF116329)
    JsonSyntax.Kind.NUMBER -> if (dark) Color(0xFFFFA657) else Color(0xFF953800)
    JsonSyntax.Kind.LITERAL -> if (dark) Color(0xFFD2A8FF) else Color(0xFF8250DF)
}
