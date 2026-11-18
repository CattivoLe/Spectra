package cattivole.spectra.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import cattivole.spectra.SpectraModel
import cattivole.spectra.assistant.AssistantModel

/**
 * The ✨ button: opens a new assistant chat about what this screen shows, with [question] asked as soon as the model
 * is there. [prepare] hands the screen's data over first (a decoded capture, the recorded traffic). Hidden on phones
 * the assistant does not run on.
 */
@Composable
fun AskAssistantButton(model: SpectraModel, nav: Navigator, question: () -> String, prepare: () -> Unit = {}) {
    if (!AssistantModel.isSupported) return
    IconButton(onClick = {
        prepare()
        nav.askAssistant(model, question())
    }) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = tr("Ask the assistant"), tint = Spectra.colors.revealed)
    }
}
