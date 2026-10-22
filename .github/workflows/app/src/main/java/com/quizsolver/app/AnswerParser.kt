package com.quizsolver.app

object AnswerParser {

    private val optionPattern =
        Regex("^\\s*\\(?([A-Ha-h])\\)?\\s*[.).:\\-–]*\\s*(.+)$")

    private val embeddedPattern =
        Regex("([A-Ha-h])\\s*[.).:\\-–]+\\s*([^\\n\\r]+)")

    fun clean(raw: String?): String? {
        if (raw.isNullOrBlank()) {
            return null
        }

        var text = raw.trim()

        text = text
            .replace("```", " ")
            .replace("`", "")
            .replace("*", "")
            .replace("_", "")

        text = text.replace(
            Regex("(?i)(final\\s+answer|answer)\\s*[:\\-]?"),
            ""
        )

        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val directMatches = lines.mapNotNull { optionPattern.find(it) }

        if (directMatches.isNotEmpty()) {
            val match = directMatches.last()
            return format(
                match.groupValues[1],
                match.groupValues[2]
            )
        }

        val embeddedMatches = embeddedPattern.findAll(text).toList()

        if (embeddedMatches.isNotEmpty()) {
            val match = embeddedMatches.last()
            return format(
                match.groupValues[1],
                match.groupValues[2]
            )
        }

        val singleLetter = Regex("^\\s*([A-Ha-h])\\s*$").find(text)

        if (singleLetter != null) {
            return "${singleLetter.groupValues[1].uppercase()})"
        }

        val fallback = lines.firstOrNull()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()

        return fallback?.take(80)
    }

    private fun format(letter: String, answer: String): String {
        val cleanedAnswer = answer
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', ' ')

        val limited = if (cleanedAnswer.length > 120) {
            cleanedAnswer.take(120)
        } else {
            cleanedAnswer
        }

        return "${letter.uppercase()}) $limited"
    }
}
