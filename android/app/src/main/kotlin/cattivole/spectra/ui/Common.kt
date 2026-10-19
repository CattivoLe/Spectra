package cattivole.spectra.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** One "Copy …" line of a long-press menu: what the menu says, what the message calls it, and the text. */
data class CopyItem(val title: String, val label: String, val value: String)

/**
 * Wraps a row so a long press opens a small menu of things to copy.
 * [content] gets the function that opens it.
 */
@Composable
fun WithCopyMenu(items: List<CopyItem>, content: @Composable (open: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val copy = rememberCopier()
    Box {
        content { if (items.isNotEmpty()) open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.title) },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                    onClick = {
                        copy(item.label, item.value)
                        open = false
                    },
                )
            }
        }
    }
}

/** A row of chips that scrolls sideways, under the search field. */
@Composable
fun ChipRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Sort row: full space above, half the usual space before the filter row. */
internal val SortChipPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 2.dp)

/** Filter row: half the usual space after sort, full space below. */
internal val FilterChipPadding = PaddingValues(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 4.dp)

@Composable
fun ToggleChip(text: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        leadingIcon = when {
            selected -> ({ Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) })
            icon != null -> ({ Icon(icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) })
            else -> null
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = null,
    )
}

/** Wall-clock time that ticks every [periodMs], for "5 s ago" and the scan timer. */
@Composable
fun rememberNow(periodMs: Long = 1000): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(periodMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/** The moment [running] last became true, or null while it is false. */
@Composable
fun rememberStart(running: Boolean): Long? = remember(running) { if (running) System.currentTimeMillis() else null }
