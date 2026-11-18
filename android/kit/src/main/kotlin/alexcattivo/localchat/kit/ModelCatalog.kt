package alexcattivo.localchat.kit

import java.util.Locale
import org.json.JSONObject

/** Модель из каталога. Каталог общий с iOS (App/Catalog.json), поэтому список обновляется без правки кода. */
data class CatalogModel(
    val id: String,
    val name: String,
    val summary: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    /** Контрольная сумма SHA-256 файла, строчными шестнадцатеричными цифрами. */
    val sha256: String,
    val licenseName: String,
    val licenseURL: String,
    val recommended: Boolean,
    /** Важное предупреждение перед загрузкой (например, про отключённые ограничения). */
    val notice: String? = null,
    /** «embedding» — вспомогательная модель для поиска по смыслу, в разговоре не участвует. */
    val kind: String? = null,
    val nameEN: String? = null,
    val summaryEN: String? = null,
    val noticeEN: String? = null,
    /** Описание для Android, если оно отличается от iOS (например, скорость замерена на другом телефоне). */
    val summaryAndroid: String? = null,
    val summaryAndroidEN: String? = null,
) {
    val isEmbedding: Boolean get() = kind == "embedding"
    private val prefersEnglish: Boolean get() = Locale.getDefault().language == "en"
    val localizedName: String get() = if (prefersEnglish) nameEN ?: name else name
    val localizedSummary: String get() =
        if (prefersEnglish) summaryAndroidEN ?: summaryEN ?: summaryAndroid ?: summary else summaryAndroid ?: summary
    val localizedNotice: String? get() = if (prefersEnglish) noticeEN ?: notice else notice
}

/** Насколько модель подходит устройству. */
enum class ModelFit { Comfortable, Tight, TooBig }

object ModelCatalog {
    fun decode(json: String): List<CatalogModel> {
        val models = JSONObject(json).getJSONArray("models")
        return (0 until models.length()).map { index ->
            val item = models.getJSONObject(index)
            fun optional(key: String) = if (item.has(key) && !item.isNull(key)) item.getString(key) else null
            CatalogModel(
                id = item.getString("id"),
                name = item.getString("name"),
                summary = item.getString("summary"),
                fileName = item.getString("fileName"),
                url = item.getString("url"),
                sizeBytes = item.getLong("sizeBytes"),
                sha256 = item.getString("sha256"),
                licenseName = item.getString("licenseName"),
                licenseURL = item.getString("licenseURL"),
                recommended = item.optBoolean("recommended", false),
                notice = optional("notice"),
                kind = optional("kind"),
                nameEN = optional("nameEN"),
                summaryEN = optional("summaryEN"),
                noticeEN = optional("noticeEN"),
                summaryAndroid = optional("summaryAndroid"),
                summaryAndroidEN = optional("summaryAndroidEN"),
            )
        }
    }

    /**
     * Оценка по размеру файла и памяти устройства. На Android веса тоже читаются из файла через mmap,
     * но целиком поместиться в память всё равно должны. Границы те же, что в iOS-версии.
     */
    fun fit(fileSizeBytes: Long, physicalMemoryBytes: Long): ModelFit {
        val size = fileSizeBytes.toDouble()
        val memory = physicalMemoryBytes.toDouble()
        return when {
            size <= memory * 0.5 -> ModelFit.Comfortable
            size <= memory * 0.65 -> ModelFit.Tight
            else -> ModelFit.TooBig
        }
    }
}
