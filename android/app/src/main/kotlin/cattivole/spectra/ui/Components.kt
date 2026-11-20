package cattivole.spectra.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cattivole.spectra.core.SignalQuality

// Ambient services

/** Shows a short message at the bottom of the window. */
val LocalNotify = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Asks for the runtime permissions the radios need, or opens the app's settings page. */
val LocalRequestPermissions = staticCompositionLocalOf<() -> Unit> { {} }

/** True while the tab bar is on screen; a screen without it pads for the gesture area itself. */
val LocalBottomBarShown = staticCompositionLocalOf { true }

/** Copies [text] and says so. */
@Composable
fun rememberCopier(): (String, String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val notify = LocalNotify.current
    return { label, text ->
        clipboard.setText(AnnotatedString(text))
        notify(tr("%s copied", label))
    }
}

// Signal colours

fun SpectraColors.of(quality: SignalQuality): Color = when (quality) {
    SignalQuality.EXCELLENT -> excellent
    SignalQuality.GOOD -> good
    SignalQuality.FAIR -> fair
    SignalQuality.WEAK -> weak
    SignalQuality.POOR -> poor
}

@Composable
fun signalColor(rssi: Int): Color = Spectra.colors.of(SignalQuality.of(rssi))

/** Busy (red) to quiet (green), for a 0..1 congestion level. */
@Composable
fun levelColor(level: Double): Color {
    val colors = Spectra.colors
    return when {
        level < 0.35 -> colors.excellent
        level < 0.6 -> colors.fair
        level < 0.8 -> colors.weak
        else -> colors.poor
    }
}

// Screen frame

/**
 * A tab or detail screen: a top bar that collapses as the list scrolls, an
 * optional pull to refresh, and a lazy list of card sections. [overlay] sits
 * over the list for empty and searching states.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpectraScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    overlay: @Composable BoxScope.() -> Unit = {},
    fill: (@Composable BoxScope.() -> Unit)? = null,
    searchQuery: String? = null,
    onSearchChange: ((String) -> Unit)? = null,
    searchPlaceholder: String = "",
    content: LazyListScope.() -> Unit = {},
) {
    val query = searchQuery
    val onQuery = onSearchChange
    val large = onBack == null
    val behavior = if (large) {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    } else {
        TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    }
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        scrolledContainerColor = Color.Transparent,
    )
    val barColor = lerp(
        MaterialTheme.colorScheme.background,
        MaterialTheme.colorScheme.surfaceContainerLow,
        behavior.state.collapsedFraction,
    )
    val navigation: @Composable () -> Unit = {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
            }
        }
    }
    Scaffold(
        modifier = modifier.nestedScroll(behavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(Modifier.background(barColor).fillMaxWidth()) {
            if (large) {
                MediumTopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (subtitle != null && behavior.state.collapsedFraction < 0.5f) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    actions = actions,
                    colors = colors,
                    scrollBehavior = behavior,
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                            if (subtitle != null) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    navigationIcon = navigation,
                    actions = actions,
                    colors = colors,
                    scrollBehavior = behavior,
                )
            }
            }
        },
    ) { padding ->
        val bottom = if (LocalBottomBarShown.current) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val list = @Composable {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp + bottom),
            ) {
                // Like the iOS search bar: it sits under the title, scrolls away with the list and is back at the top.
                if (query != null && onQuery != null) {
                    item(key = "spectra-search", contentType = "search") {
                        SearchField(query, onQuery, searchPlaceholder, Modifier.padding(horizontal = 8.dp), compact = true)
                    }
                }
                content()
                // Empty and no-results states sit under whatever the screen keeps on top.
                item(key = "spectra-overlay", contentType = "overlay") {
                    Box(Modifier.fillMaxWidth().padding(top = 36.dp)) { overlay() }
                }
            }
        }
        Box(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
            if (fill != null) {
                Column(Modifier.fillMaxSize()) {
                    if (query != null && onQuery != null) {
                        SearchField(query, onQuery, searchPlaceholder, Modifier.padding(horizontal = 8.dp), compact = true)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), content = fill)
                }
            } else if (onRefresh != null) {
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                    list()
                }
            } else {
                list()
            }
        }
    }
}

// Card sections

/** Where a row sits in its card, for the corners it rounds. */
enum class CardPosition { ONLY, FIRST, MIDDLE, LAST }

fun cardPosition(index: Int, count: Int): CardPosition = when {
    count <= 1 -> CardPosition.ONLY
    index == 0 -> CardPosition.FIRST
    index == count - 1 -> CardPosition.LAST
    else -> CardPosition.MIDDLE
}

private fun CardPosition.shape(radius: Dp = 20.dp): Shape = when (this) {
    CardPosition.ONLY -> RoundedCornerShape(radius)
    CardPosition.FIRST -> RoundedCornerShape(topStart = radius, topEnd = radius)
    CardPosition.LAST -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
    CardPosition.MIDDLE -> RoundedCornerShape(0.dp)
}

/**
 * One row of a grouped card. Rows after the first draw a hairline above them,
 * inset by [dividerInset] so it starts under the text rather than the icon.
 */
@Composable
fun CardCell(
    position: CardPosition,
    modifier: Modifier = Modifier,
    dividerInset: Dp = 16.dp,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(position.shape())
            .background(container),
    ) {
        if (position == CardPosition.MIDDLE || position == CardPosition.LAST) {
            HorizontalDivider(
                Modifier.padding(start = dividerInset),
                thickness = 0.6.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        content()
    }
}

/** A heading over a card, with an optional control on the right. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    icon: ImageVector? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 32.dp, end = 24.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke(this)
    }
}

/** Small print under a card. */
@Composable
fun SectionFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A titled card of rows built from a list. */
fun <T> LazyListScope.cardSection(
    items: List<T>,
    header: String? = null,
    footer: String? = null,
    key: ((T) -> Any)? = null,
    sectionKey: String? = header,
    headerTint: @Composable () -> Color = { MaterialTheme.colorScheme.onSurfaceVariant },
    headerIcon: ImageVector? = null,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
    onHeaderClick: (() -> Unit)? = null,
    dividerInset: Dp = 16.dp,
    row: @Composable LazyItemScope.(T) -> Unit,
) {
    if (items.isEmpty()) return
    if (header != null) {
        item(key = "header-$sectionKey", contentType = "header") {
            val click = if (onHeaderClick != null) Modifier.clickable(onClick = onHeaderClick) else Modifier
            SectionHeader(header, Modifier.animateItem().then(click), tint = headerTint(), icon = headerIcon, trailing = headerTrailing)
        }
    } else {
        item(key = "gap-$sectionKey", contentType = "gap") { Spacer(Modifier.height(12.dp)) }
    }
    itemsIndexed(items, key = key?.let { k -> { _, item -> k(item) } }, contentType = { _, _ -> "row" }) { index, item ->
        CardCell(cardPosition(index, items.size), Modifier.animateItem(), dividerInset = dividerInset) {
            row(item)
        }
    }
    if (footer != null) {
        item(key = "footer-$sectionKey", contentType = "footer") { SectionFooter(footer, Modifier.animateItem()) }
    }
}

/** A titled card of fixed rows, for detail screens. */
fun LazyListScope.card(
    header: String? = null,
    footer: String? = null,
    key: String? = header,
    dividerInset: Dp = 16.dp,
    rows: List<@Composable () -> Unit>,
) {
    cardSection(
        items = rows.indices.toList(),
        header = header,
        footer = footer,
        key = { "$key-$it" },
        sectionKey = key,
        dividerInset = dividerInset,
    ) { index -> rows[index]() }
}

/** One card with free-form content. */
fun LazyListScope.freeCard(
    key: String,
    header: String? = null,
    footer: String? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (header != null) item(key = "header-$key") { SectionHeader(header) } else item(key = "gap-$key") { Spacer(Modifier.height(12.dp)) }
    item(key = key) {
        CardCell(CardPosition.ONLY) {
            Column(Modifier.padding(padding), content = content)
        }
    }
    if (footer != null) item(key = "footer-$key") { SectionFooter(footer) }
}

// Rows

/**
 * The common list row: leading tile, a title with badges, a muted line under
 * it, and a trailing value. Long press is for copying.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    detail: String? = null,
    detailContent: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    badges: @Composable RowScope.() -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    dimmed: Boolean = false,
    italicTitle: Boolean = false,
    mutedTitle: Boolean = false,
    subtitleLines: Int = 2,
    monoSubtitle: Boolean = false,
) {
    val clickable = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
    } else {
        Modifier
    }
    Row(
        modifier
            .fillMaxWidth()
            .then(clickable)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp)
            .graphicsLayer { alpha = if (dimmed) 0.62f else 1f },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    fontStyle = if (italicTitle) FontStyle.Italic else FontStyle.Normal,
                    color = if (mutedTitle) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                badges()
            }
            if (subtitleContent != null) {
                subtitleContent()
            } else if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.merge(Tabular),
                    fontFamily = if (monoSubtitle) FontFamily.Monospace else null,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = subtitleLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (detailContent != null) {
                detailContent()
            } else if (!detail.isNullOrEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** A square of colour behind an icon, the leading mark of most rows. */
@Composable
fun IconTile(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    filled: Boolean = false,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(if (filled) tint else tint.copy(alpha = if (Spectra.colors.isDark) 0.16f else 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (filled) Color.White else tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** A tile holding any small drawing, such as the Wi-Fi glyph. */
@Composable
fun GlyphTile(tint: Color, modifier: Modifier = Modifier, size: Dp = 40.dp, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(tint.copy(alpha = if (Spectra.colors.isDark) 0.14f else 0.1f)),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** A capsule label: "Random", "Gateway", "ATTACK". */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Text(
        text,
        modifier = modifier
            .clip(CircleShape)
            .background(if (filled) color else color.copy(alpha = if (Spectra.colors.isDark) 0.2f else 0.13f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = if (filled) Color.White else color,
        maxLines = 1,
    )
}

/** Signal level and its recent trend, the trailing column of a radio row. */
@Composable
fun SignalTrailing(rssi: Int, inRange: Boolean, samples: List<Int>) {
    val color = if (inRange) signalColor(rssi) else MaterialTheme.colorScheme.outline
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            "$rssi",
            style = MaterialTheme.typography.titleMedium.merge(Tabular),
            color = if (inRange) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        )
        if (inRange && samples.size > 1) {
            Sparkline(samples.takeLast(24), color, Modifier.width(44.dp).height(14.dp))
        } else {
            Text(tr("dBm"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * A parameter and its value. A long value wraps under the right edge instead
 * of being cut, which long names and Russian labels need. Long press copies.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    copyable: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val copy = rememberCopier()
    val interaction = when {
        onClick != null -> Modifier.combinedClickable(onClick = onClick, onLongClick = { copy(label, value) })
        copyable -> Modifier.combinedClickable(onClick = {}, onLongClick = { copy(label, value) })
        else -> Modifier
    }
    Row(
        modifier.fillMaxWidth().then(interaction).heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(end = 16.dp).widthIn(max = 220.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge.merge(Tabular),
            letterSpacing = if (mono) 0.2.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A warning or an explanation inside a card. */
@Composable
fun NoteRow(text: String, warning: Boolean, modifier: Modifier = Modifier) {
    val colors = Spectra.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (warning) Icons.Rounded.Warning else Icons.Rounded.Info,
            contentDescription = null,
            tint = if (warning) colors.warning else colors.info,
            modifier = Modifier.size(20.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** A row that reads as a button inside a card. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionRow(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val shown = if (enabled) tint else MaterialTheme.colorScheme.outline
    Row(
        modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.combinedClickable(onClick = onClick) else Modifier)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = shown, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = shown, fontWeight = FontWeight.Medium)
    }
}

/** A labelled progress bar inside a card. */
@Composable
fun ProgressRow(label: String, fraction: Float?, detail: String? = null) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (fraction == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        } else {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().clip(CircleShape),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

// Status and search

/**
 * A capsule with an icon and two short lines: the sensor, the LAN subnet,
 * the Bluetooth scan. The icon pulses while something is running.
 */
@Composable
fun StatusChip(
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    busy: Boolean = false,
    indicator: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (busy) 0.35f else 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp).graphicsLayer { this.alpha = alpha })
                if (indicator != null) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(indicator)
                            .border(1.5.dp, MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                    )
                }
            }
            Column {
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall.merge(Tabular),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val focus = LocalFocusManager.current
    if (compact) {
        val interaction = remember { MutableInteractionSource() }
        val colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).height(38.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            interactionSource = interaction,
            decorationBox = { inner ->
                TextFieldDefaults.DecorationBox(
                    value = query,
                    innerTextField = inner,
                    enabled = true,
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = interaction,
                    placeholder = {
                        Text(placeholder, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = tr("Clear"),
                                modifier = Modifier.size(18.dp).clickable { onQueryChange("") },
                            )
                        }
                    } else {
                        null
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = colors,
                    contentPadding = TextFieldDefaults.contentPaddingWithoutLabel(
                        start = 0.dp,
                        top = 0.dp,
                        end = 0.dp,
                        bottom = 0.dp,
                    ),
                )
            },
        )
        return
    }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 8.dp else 16.dp, vertical = if (compact) 8.dp else 4.dp),
        textStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
        placeholder = { Text(placeholder, maxLines = 1) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, contentDescription = tr("Clear")) }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

// Empty states

/** Centred over the list when there is nothing to show yet. */
@Composable
fun BoxScope.EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    busy: Boolean = false,
) {
    val spectrum = Spectra.colors.spectrum
    val pulse = rememberInfiniteTransition(label = "empty")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (busy) 1.12f else 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "scale",
    )
    Column(
        Modifier.align(Alignment.TopCenter).padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(88.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(spectrum, alpha = 0.18f)
                .border(1.dp, spectrum, CircleShape, ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

/** Gradient text-free banner strip used at the top of hero cards. */
@Composable
fun SpectrumBar(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Spectra.colors.spectrum))
}

/** Wraps the app in the things screens reach for: notifications and the tab bar flag. */
@Composable
fun ProvideScreenServices(
    notify: (String) -> Unit,
    bottomBar: Boolean,
    requestPermissions: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalNotify provides notify,
        LocalBottomBarShown provides bottomBar,
        LocalRequestPermissions provides requestPermissions,
        content = content,
    )
}
