package cattivole.spectra.ui

import androidx.compose.runtime.Composable
import cattivole.spectra.SpectraModel
import cattivole.spectra.core.HistoryArea

/**
 * The Internet tab: checks of the way out of whatever network the phone is on.
 * The connection check, a link, the speed, the route, and the phone's own traffic.
 * The LAN tab keeps the devices of the local network.
 */
@Composable
fun InternetScreen(model: SpectraModel, nav: Navigator) {
    model.generation
    SpectraScreen(
        title = tr("Internet"),
        actions = { HistoryButton(nav, HistoryArea.INTERNET) },
    ) {
        connectionEntry(model, nav)
        linkCheckEntry(model, nav)
        trafficEntry(model, nav)
    }
}
