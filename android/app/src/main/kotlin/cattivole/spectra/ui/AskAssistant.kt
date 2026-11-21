package cattivole.spectra.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import cattivole.spectra.SpectraModel
import cattivole.spectra.assistant.AssistantModel
import cattivole.spectra.assistant.CardSubject

/**
 * The ✨ button: opens a summary card about what this screen shows — the app's findings at once, the on-device
 * model's plain words under them, and buttons for the next steps. [prepare] hands the screen's data over first (the
 * recorded traffic). Hidden on phones the assistant does not run on.
 */
@Composable
fun AskAssistantButton(model: SpectraModel, nav: Navigator, subject: () -> CardSubject, prepare: () -> Unit = {}) {
    if (!AssistantModel.isSupported) return
    IconButton(onClick = {
        prepare()
        nav.summary(model, subject())
    }) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = tr("Summary"), tint = Spectra.colors.revealed)
    }
}
