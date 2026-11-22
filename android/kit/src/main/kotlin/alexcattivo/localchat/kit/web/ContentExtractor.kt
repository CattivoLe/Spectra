package alexcattivo.localchat.kit.web

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Основной текст страницы: заголовок и абзацы по порядку, без меню, рекламы и подвала. */
data class ExtractedPage(val title: String, val blocks: List<Block>) {
    data class Block(val text: String, val isHeading: Boolean, val isTableRow: Boolean = false)

    val text: String get() = blocks.joinToString("\n") { it.text }
}

/**
 * Упрощённый «режим чтения»: убрать заведомый мусор, найти элемент, где больше всего связного текста, и взять из него
 * абзацы. Повторяет ContentExtractor.swift (там SwiftSoup — перенос того же Jsoup).
 */
object ContentExtractor {
    private const val JUNK_TAGS = "script, style, noscript, svg, iframe, form, nav, header, footer, aside, button, select, template, sup, " +
        "[role=navigation], [role=banner], [role=contentinfo], [aria-hidden=true]"
    /** Классы и id, по которым видно служебный блок. */
    private val junkNames = Regex(
        "(^|[\\s_-])(nav|navbar|menu|footer|sidebar|comments?|share|social|advert|ads?|banner|cookie|subscribe|related|breadcrumbs?|promo|popup|modal|newsletter|navbox|mw-editsection|reference|toc)([\\s_-]|$)",
    )
    private val blockTags = setOf("h1", "h2", "h3", "h4", "p", "li", "pre", "blockquote", "dd", "tr")

    /**
     * Text a search service already took out of the page (markdown or plain): «#» lines become headings, the rest
     * paragraphs, so [Chunker] cuts it like a fetched page.
     */
    fun fromText(title: String, text: String): ExtractedPage {
        val blocks = mutableListOf<ExtractedPage.Block>()
        val paragraph = mutableListOf<String>()
        fun flush() {
            plainMarkdown(paragraph.joinToString(" ")).takeIf { it.isNotEmpty() }?.let { blocks.add(ExtractedPage.Block(it, isHeading = false)) }
            paragraph.clear()
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> flush()
                line.startsWith("#") -> {
                    flush()
                    plainMarkdown(line.trimStart('#', ' ')).takeIf { it.isNotEmpty() }?.let { blocks.add(ExtractedPage.Block(it, isHeading = true)) }
                }
                else -> paragraph.add(line)
            }
        }
        flush()
        return ExtractedPage(title, blocks)
    }

    /** Markdown links and images carry addresses the model does not need: only their words stay. */
    private fun plainMarkdown(text: String): String = text
        .replace(Regex("""!\[[^\]]*\]\([^)]*\)"""), "")
        .replace(Regex("""\[([^\]]*)\]\([^)]*\)"""), "$1")
        .split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")

    fun extract(html: String, url: String): ExtractedPage {
        val document = runCatching { Jsoup.parse(html, url) }.getOrNull() ?: return ExtractedPage("", emptyList())
        val title = document.select("meta[property=og:title]").first()?.attr("content")?.takeIf { it.isNotEmpty() } ?: document.title()
        document.select(JUNK_TAGS).remove()
        val body = document.body() ?: return ExtractedPage(clean(title), emptyList())
        for (element in body.allElements.toList()) {
            if (element.tagName() in setOf("body", "main", "article")) continue
            val names = (element.className() + " " + element.id()).lowercase()
            if (junkNames.containsMatchIn(names)) element.remove()
        }

        var root = bestRoot(body)
        if (root.text().length < 300) root = body

        val blocks = mutableListOf<ExtractedPage.Block>()
        for (element in root.allElements) {
            val tag = element.tagName()
            if (tag !in blockTags) continue
            // Элемент, внутри которого есть другие блоки (пункт списка с абзацем), пропускаем: возьмём вложенные.
            if (tag != "tr" && element.children().any { it.tagName() in blockTags }) continue
            if (tag != "tr" && element.parents().any { it.tagName() == "tr" }) continue
            val text = if (tag == "tr") {
                element.children().map { clean(it.text()) }.filter { it.isNotEmpty() }.joinToString(" | ")
            } else {
                clean(element.text())
            }
            val isHeading = tag.startsWith("h")
            if (text.isEmpty()) continue
            if (!isHeading && text.length < 25 && tag != "tr") continue
            if (linkDensity(element, text.length) > 0.6) continue
            if (blocks.lastOrNull()?.text == text) continue
            blocks.add(ExtractedPage.Block(text, isHeading, tag == "tr"))
        }
        return ExtractedPage(clean(title), blocks)
    }

    /** Элемент с наибольшим «весом» абзацев: каждый абзац добавляет свою длину родителю и половину — деду. */
    private fun bestRoot(body: Element): Element {
        body.select("article, main, [role=main]").maxByOrNull { it.text().length }?.let { if (it.text().length >= 300) return it }
        val scores = LinkedHashMap<Element, Int>()
        for (paragraph in body.select("p, pre, li")) {
            val length = paragraph.text().length
            if (length < 25) continue
            val parent = paragraph.parent() ?: continue
            scores[parent] = (scores[parent] ?: 0) + length
            parent.parent()?.let { scores[it] = (scores[it] ?: 0) + length / 2 }
        }
        return scores.maxByOrNull { it.value }?.key ?: body
    }

    private fun linkDensity(element: Element, textLength: Int): Double {
        if (textLength == 0) return 0.0
        val linked = element.select("a").sumOf { it.text().length }
        return linked.toDouble() / textLength
    }

    fun clean(text: String): String = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
}
