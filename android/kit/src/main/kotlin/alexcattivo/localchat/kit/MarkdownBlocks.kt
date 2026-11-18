package alexcattivo.localchat.kit

/** Блок разметки в ответе модели. Внутристрочное оформление (жирный, курсив, `код`, ссылки) разбирается при показе. */
sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    /** `marker` — «•» для маркированного списка или «1.» для нумерованного. */
    data class ListItem(val marker: String, val text: String, val indent: Int) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    /** `isClosed == false`, пока модель не дописала закрывающие кавычки: блок всё равно показывается как код. */
    data class Code(val language: String?, val text: String, val isClosed: Boolean) : MarkdownBlock
}

object MarkdownParser {
    /** Разбирает текст на блоки. Работает с недописанным текстом: ответ приходит по кусочку. */
    fun parse(source: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraph = mutableListOf<String>()
        val quote = mutableListOf<String>()
        var codeLanguage: String? = null
        var codeLines: MutableList<String>? = null

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) blocks.add(MarkdownBlock.Paragraph(paragraph.joinToString("\n")))
            paragraph.clear()
        }
        fun flushQuote() {
            if (quote.isNotEmpty()) blocks.add(MarkdownBlock.Quote(quote.joinToString("\n")))
            quote.clear()
        }

        for (line in source.split("\n")) {
            val trimmed = line.trim()
            val open = codeLines
            if (open != null) {
                if (trimmed.startsWith("```")) {
                    blocks.add(MarkdownBlock.Code(codeLanguage, open.joinToString("\n"), true))
                    codeLines = null
                } else {
                    open.add(line)
                }
                continue
            }
            val heading = headingMatch(trimmed)
            val item = listMatch(line)
            when {
                trimmed.startsWith("```") -> {
                    flushParagraph(); flushQuote()
                    codeLanguage = trimmed.drop(3).trim().ifEmpty { null }
                    codeLines = mutableListOf()
                }
                trimmed.isEmpty() -> { flushParagraph(); flushQuote() }
                heading != null -> { flushParagraph(); flushQuote(); blocks.add(heading) }
                item != null -> { flushParagraph(); flushQuote(); blocks.add(item) }
                trimmed.startsWith(">") -> { flushParagraph(); quote.add(trimmed.drop(1).trim()) }
                else -> { flushQuote(); paragraph.add(trimmed) }
            }
        }
        codeLines?.let { blocks.add(MarkdownBlock.Code(codeLanguage, it.joinToString("\n"), false)) }
        flushParagraph(); flushQuote()
        return blocks
    }

    private fun headingMatch(line: String): MarkdownBlock.Heading? {
        val hashes = line.takeWhile { it == '#' }.length
        if (hashes !in 1..6 || line.getOrNull(hashes) != ' ') return null
        return MarkdownBlock.Heading(hashes, line.drop(hashes).trim())
    }

    private fun listMatch(line: String): MarkdownBlock.ListItem? {
        val indent = line.takeWhile { it == ' ' || it == '\t' }.length
        val body = line.drop(indent)
        val first = body.firstOrNull()
        if (first != null && first in "-*+" && body.getOrNull(1) == ' ') {
            return MarkdownBlock.ListItem("•", body.drop(2).trim(), indent / 2)
        }
        val digits = body.takeWhile { it.isDigit() }
        if (digits.isNotEmpty() && digits.length <= 3) {
            val rest = body.drop(digits.length)
            val mark = rest.firstOrNull()
            if (mark != null && mark in ".)" && rest.getOrNull(1) == ' ') {
                return MarkdownBlock.ListItem("$digits.", rest.drop(2).trim(), indent / 2)
            }
        }
        return null
    }
}
