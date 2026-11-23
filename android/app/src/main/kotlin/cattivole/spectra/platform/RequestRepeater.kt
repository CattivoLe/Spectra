package cattivole.spectra.platform

import cattivole.spectra.core.HttpAssembler
import cattivole.spectra.core.HttpField
import cattivole.spectra.core.RepeatRequest
import cattivole.spectra.core.RepeatResult
import cattivole.spectra.core.decodeBody
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends a recorded or edited request again, from this app. While a recording
 * runs, the request goes through it like any other and shows up in the list.
 * Blocking: call it off the main thread.
 */
object RequestRepeater {
    fun send(request: RepeatRequest): RepeatResult {
        val started = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(request.url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.requestMethod = request.method
            request.headers.forEach { connection.addRequestProperty(it.name, it.value) }
            val body = request.body
            if (body != null && request.method != "GET" && request.method != "HEAD") {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val headers = connection.headerFields.flatMap { (name, values) ->
                if (name == null) emptyList() else values.map { HttpField(name, it) }
            }
            val stream = if (status >= 400) connection.errorStream else runCatching { connection.inputStream }.getOrNull()
            val raw = ByteArrayOutputStream()
            var cut = false
            stream?.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val room = HttpAssembler.RESPONSE_CAP - raw.size()
                    if (count > room) {
                        raw.write(buffer, 0, room)
                        cut = true
                        break
                    }
                    raw.write(buffer, 0, count)
                }
            }
            val (decoded, decodeCut) = decodeBody(raw.toByteArray(), headers, HttpAssembler.RESPONSE_CAP)
            RepeatResult(status, connection.responseMessage, headers, decoded, cut || decodeCut, System.currentTimeMillis() - started, null)
        } catch (error: Exception) {
            RepeatResult(null, null, emptyList(), ByteArray(0), false, System.currentTimeMillis() - started, error.message ?: error.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }
}
