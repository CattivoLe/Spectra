package cattivole.spectra.core

/**
 * Who refused Spectra's certificate in this recording: an app and the host it was talking to. Its later connections
 * there pass through unread, so the app keeps working. Kept per app, because apps share hosts: Google's services
 * refuse it for www.google.com while the browser accepts it.
 */
class RefusedHosts {
    private val refused = HashSet<Pair<String?, String>>()

    /** [app] is null when the system did not say whose connection it was. */
    fun add(app: String?, host: String) {
        refused.add(app to host.lowercase())
    }

    fun contains(app: String?, host: String): Boolean = (app to host.lowercase()) in refused
}
