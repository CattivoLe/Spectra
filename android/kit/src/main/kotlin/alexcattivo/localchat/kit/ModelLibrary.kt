package alexcattivo.localchat.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Модель, лежащая на устройстве. */
data class InstalledModel(val fileName: String, val sizeBytes: Long, val catalog: CatalogModel?) {
    val displayName: String get() = catalog?.localizedName ?: readableName(fileName)
    /** Модель эмбеддингов: в списках моделей для разговора её быть не должно. */
    val isEmbedding: Boolean get() = catalog?.isEmbedding ?: fileName.lowercase().contains("embedding")

    companion object {
        /** Имя модели не из каталога, собранное из имени файла: «gemma-3-4b-it-Q4_K_M.gguf» → «gemma 3 4b». */
        fun readableName(fileName: String, maxLength: Int = 26): String {
            var stem = fileName
            if (stem.lowercase().endsWith(".gguf")) stem = stem.dropLast(5)
            val quantization = Regex("(?i)[._-](i?q\\d\\w*|f16|bf16|f32|fp16)$")
            while (true) {
                val match = quantization.find(stem) ?: break
                stem = stem.removeRange(match.range)
            }
            val dropped = setOf("instruct", "it", "chat", "gguf")
            val words = stem.split('-', '_', ' ').filter { it.isNotEmpty() && it.lowercase() !in dropped }
            val name = words.joinToString(" ")
            if (name.isEmpty()) return fileName
            if (name.length <= maxLength) return name
            var kept = ""
            for (word in words) {
                val next = if (kept.isEmpty()) word else "$kept $word"
                if (next.length + 1 > maxLength) break
                kept = next
            }
            if (kept.isEmpty()) kept = name.take(maxLength - 1)
            return "$kept…"
        }
    }
}

/** Почему загрузка не удалась. Текст для человека составляет приложение. */
sealed interface DownloadFailure {
    data class NotEnoughSpace(val needed: Long, val available: Long) : DownloadFailure
    data object ChecksumMismatch : DownloadFailure
    data class Http(val code: Int) : DownloadFailure
    data object MeteredNetwork : DownloadFailure
    data class Network(val message: String) : DownloadFailure
}

sealed interface DownloadState {
    data class Downloading(val received: Long, val total: Long) : DownloadState
    data class Paused(val received: Long) : DownloadState
    /** Файл скачан, считается контрольная сумма. */
    data object Verifying : DownloadState
    data class Failed(val reason: DownloadFailure) : DownloadState
}

/**
 * Модели на устройстве: что установлено, что скачивается, импорт из «Файлов» и удаление.
 *
 * Скачанное лежит в `.downloads/<id>.part` и докачивается с того же места (заголовок Range), в том числе
 * после перезапуска приложения. Каждый файл сверяется с контрольной суммой из каталога и только потом
 * становится доступным. Чтобы система не остановила загрузку в фоне, приложение держит на это время
 * службу с уведомлением (см. DownloadService в app).
 */
class ModelLibrary(
    val directory: File,
    val catalog: List<CatalogModel>,
    private val scope: CoroutineScope,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    /** Сеть сейчас платная (мобильная). Задаёт приложение. */
    private val isMetered: () -> Boolean = { false },
) {
    var installed by mutableStateOf<List<InstalledModel>>(emptyList())
        private set
    val downloads = mutableStateMapOf<String, DownloadState>()
    /** Имя файла, который сейчас копируется из «Файлов». */
    var importing by mutableStateOf<String?>(null)
        private set

    /** Скачивать только по Wi-Fi (по неплатной сети). */
    var wifiOnly = true
    /** Вызывается, когда набор установленных моделей изменился. */
    var onInstalledChanged: (() -> Unit)? = null
    /** Вызывается, когда загрузок стало больше или меньше: приложение включает и выключает фоновую службу. */
    var onActiveDownloadsChanged: ((Boolean) -> Unit)? = null

    private val staging = File(directory, ".downloads")
    private val jobs = mutableMapOf<String, Job>()

    init {
        staging.mkdirs()
        refreshInstalled()
        // После перезапуска недокачанное видно как «приостановлено», а скачанное, но не проверенное — проверяется.
        for (model in catalog) {
            val part = partFile(model.id)
            if (part.exists()) {
                downloads[model.id] = if (part.length() >= model.sizeBytes) DownloadState.Verifying else DownloadState.Paused(part.length())
                if (part.length() >= model.sizeBytes) scope.launch { verify(model) }
            }
        }
    }

    // MARK: - Установленные

    fun refreshInstalled() {
        val files = directory.listFiles { file -> file.isFile && file.name.lowercase().endsWith(".gguf") }.orEmpty()
        installed = files.sortedBy { it.name }.map { file ->
            InstalledModel(file.name, file.length(), catalog.firstOrNull { it.fileName == file.name })
        }
    }

    /** Модели, с которыми можно разговаривать. */
    val chatModels: List<InstalledModel> get() = installed.filter { !it.isEmbedding }
    /** Установленная модель для поиска по смыслу, если есть. */
    val embeddingModel: InstalledModel? get() = installed.firstOrNull { it.isEmbedding }

    fun isInstalled(model: CatalogModel) = installed.any { it.fileName == model.fileName }

    fun file(model: InstalledModel) = File(directory, model.fileName)

    fun delete(model: InstalledModel) {
        File(directory, model.fileName).delete()
        refreshInstalled()
        onInstalledChanged?.invoke()
    }

    /** Копирует выбранный файл в папку моделей. Возвращает имя установленного файла. */
    suspend fun importFile(name: String, size: Long, open: () -> InputStream): String {
        val head = open().use { it.readNBytes(4) }
        if (!ModelFiles.isGGUF(head)) throw NotGgufException()
        val available = freeSpace(directory)
        if (available < size + 100_000_000) throw NotEnoughSpaceException(size, available)
        val target = ModelFiles.uniqueFile(name, directory)
        importing = target.name
        try {
            withContext(Dispatchers.IO) { open().use { input -> target.outputStream().use { input.copyTo(it, 4 * 1024 * 1024) } } }
        } catch (error: Throwable) {
            target.delete()
            throw error
        } finally {
            importing = null
        }
        refreshInstalled()
        onInstalledChanged?.invoke()
        return target.name
    }

    class NotGgufException : Exception("not a GGUF file")
    class NotEnoughSpaceException(val needed: Long, val available: Long) : Exception("not enough space")

    // MARK: - Загрузка

    /** Начинает загрузку или продолжает приостановленную. */
    fun download(model: CatalogModel) {
        when (downloads[model.id]) {
            is DownloadState.Downloading, DownloadState.Verifying -> return
            else -> Unit
        }
        val received = partFile(model.id).takeIf { it.exists() }?.length() ?: 0L
        val needed = model.sizeBytes - received
        val available = freeSpace(directory)
        if (available < needed + 100_000_000) {
            downloads[model.id] = DownloadState.Failed(DownloadFailure.NotEnoughSpace(needed, available))
            return
        }
        if (wifiOnly && isMetered()) {
            downloads[model.id] = DownloadState.Failed(DownloadFailure.MeteredNetwork)
            return
        }
        downloads[model.id] = DownloadState.Downloading(received, model.sizeBytes)
        jobs[model.id] = scope.launch {
            try {
                fetch(model)
                downloads[model.id] = DownloadState.Verifying
                verify(model)
            } catch (_: CancellationException) {
                // Пауза или отмена: состояние уже выставлено тем, кто остановил.
            } catch (error: HttpException) {
                downloads[model.id] = DownloadState.Failed(DownloadFailure.Http(error.code))
            } catch (error: Throwable) {
                downloads[model.id] = DownloadState.Failed(DownloadFailure.Network(error.message ?: error.toString()))
            } finally {
                jobs.remove(model.id)
                notifyActive()
            }
        }
        notifyActive()
    }

    /** Останавливает загрузку, сохранив скачанное: потом её можно продолжить. */
    fun pause(model: CatalogModel) {
        val state = downloads[model.id] as? DownloadState.Downloading ?: return
        downloads[model.id] = DownloadState.Paused(state.received)
        jobs.remove(model.id)?.cancel()
        notifyActive()
    }

    /** Прекращает загрузку и стирает всё скачанное. */
    fun cancel(model: CatalogModel) {
        jobs.remove(model.id)?.cancel()
        partFile(model.id).delete()
        downloads.remove(model.id)
        notifyActive()
    }

    val hasActiveDownloads: Boolean get() = downloads.values.any { it is DownloadState.Downloading || it == DownloadState.Verifying }

    private fun notifyActive() = onActiveDownloadsChanged?.invoke(hasActiveDownloads)

    private class HttpException(val code: Int) : Exception("HTTP $code")

    private suspend fun fetch(model: CatalogModel) = withContext(Dispatchers.IO) {
        val part = partFile(model.id)
        var offset = if (part.exists()) part.length() else 0L
        var url = URL(model.url)
        var connection: HttpURLConnection
        // Hugging Face отдаёт файлы через переадресацию на CDN; следуем ей сами, чтобы сохранить заголовок Range.
        var redirects = 0
        while (true) {
            connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "LocalChat")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            val code = connection.responseCode
            if (code in 300..399 && redirects < 8) {
                url = URL(url, connection.getHeaderField("Location"))
                connection.disconnect()
                redirects++
                continue
            }
            if (code == 416 && offset >= model.sizeBytes) return@withContext
            if (code !in listOf(200, 206)) throw HttpException(code)
            // Сервер не умеет докачивать — начинаем сначала.
            if (code == 200 && offset > 0) {
                offset = 0
                part.delete()
            }
            break
        }
        val total = model.sizeBytes
        RandomAccessFile(part, "rw").use { file ->
            file.seek(offset)
            connection.inputStream.use { input ->
                val buffer = ByteArray(256 * 1024)
                var received = offset
                var lastReport = 0L
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    file.write(buffer, 0, count)
                    received += count
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 250) {
                        lastReport = now
                        val snapshot = received
                        withContext(scope.coroutineContext) { downloads[model.id] = DownloadState.Downloading(snapshot, total) }
                    }
                }
            }
        }
        connection.disconnect()
    }

    private suspend fun verify(model: CatalogModel) {
        val part = partFile(model.id)
        val matches = withContext(Dispatchers.IO) { runCatching { ModelFiles.sha256(part) == model.sha256 }.getOrDefault(false) }
        if (!matches) {
            part.delete()
            downloads[model.id] = DownloadState.Failed(DownloadFailure.ChecksumMismatch)
            return
        }
        val target = File(directory, model.fileName)
        target.delete()
        if (!part.renameTo(target)) {
            downloads[model.id] = DownloadState.Failed(DownloadFailure.Network("rename failed"))
            return
        }
        downloads.remove(model.id)
        refreshInstalled()
        onInstalledChanged?.invoke()
    }

    private fun partFile(id: String) = File(staging, "$id.part")
}
