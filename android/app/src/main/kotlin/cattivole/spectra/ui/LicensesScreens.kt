package cattivole.spectra.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cattivole.spectra.core.ThirdPartyLicenses

/** History → ⋮ → Licenses: Spectra's own license and every third-party notice the app has to carry. */
@Composable
fun LicensesScreen(nav: Navigator) {
    val components = remember { ThirdPartyLicenses.load() }
    SpectraScreen(title = tr("Licenses"), onBack = nav::back) {
        for (group in ThirdPartyLicenses.Group.entries) {
            val shown = components.withIndex().filter { it.value.group == group }
            cardSection(
                shown,
                header = group.title(),
                footer = group.footer(),
                key = { "license-${it.index}" },
                sectionKey = "licenses-${group.name}",
            ) { (index, component) ->
                ListRow(
                    title = component.name,
                    subtitle = listOf(component.license, component.version).filter { it.isNotEmpty() }.joinToString(" · "),
                    onClick = { nav.license(index) },
                )
            }
        }
    }
}

/** One component: where it comes from, its copyright and notice, and the full license text. */
@Composable
fun LicenseDetail(nav: Navigator, index: Int) {
    val component = remember(index) { ThirdPartyLicenses.load().getOrNull(index) } ?: return nav.back()
    val context = LocalContext.current
    SpectraScreen(
        title = component.name,
        subtitle = component.license,
        onBack = nav::back,
        actions = {
            if (component.url.isNotEmpty()) {
                IconButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(component.url))) } }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = tr("Open the project page"))
                }
            }
        },
    ) {
        freeCard("about") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (component.version.isNotEmpty()) Text(tr("Version %s", component.version), style = MaterialTheme.typography.bodyMedium)
                if (component.url.isNotEmpty()) Text(component.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (component.copyright.isNotEmpty()) Text(component.copyright, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (component.notice.isNotEmpty()) {
            freeCard("notice", header = tr("Notice")) { LicenseText(component.notice) }
        }
        component.text?.let { text -> freeCard("text", header = component.license) { LicenseText(text) } }
    }
}

@Composable
private fun LicenseText(text: String) {
    val shown = remember(text) { ThirdPartyLicenses.reflow(text) }
    SelectionContainer {
        Text(shown, style = MaterialTheme.typography.bodySmall)
    }
}

private fun ThirdPartyLicenses.Group.title(): String = when (this) {
    ThirdPartyLicenses.Group.APP -> tr("This app")
    ThirdPartyLicenses.Group.CODE -> tr("Libraries")
    ThirdPartyLicenses.Group.FIRMWARE -> tr("Sensor firmware")
    ThirdPartyLicenses.Group.DATA -> tr("Data")
}

private fun ThirdPartyLicenses.Group.footer(): String? = when (this) {
    ThirdPartyLicenses.Group.APP -> tr("Spectra is open source: github.com/CattivoLe/Spectra")
    ThirdPartyLicenses.Group.FIRMWARE -> tr("The app carries firmware images for flashing the sensor; these are the parts built into them.")
    else -> null
}
