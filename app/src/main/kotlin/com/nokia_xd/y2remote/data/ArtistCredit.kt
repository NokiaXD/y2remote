package com.nokia_xd.y2remote.data

object ArtistCredit {

    private val BARE_MARKERS = arrayOf("featuring", "feat.", "feat", "ft.", "ft")

    /**
     * Splits a raw artist credit string into individual artist names.
     * Handles semicolons (;), commas (,), and feature markers (feat, ft, featuring),
     * while preserving band names that contain '&' or '+'.
     */
    fun names(credit: String): List<String> {
        val trimmed = credit.trim()
        if (trimmed.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        // Semicolons and explicit slash separators
        val parts = if (trimmed.contains(" / ")) {
            trimmed.split(" / ")
        } else if (trimmed.contains(';')) {
            trimmed.split(';')
        } else {
            listOf(trimmed)
        }

        for (part in parts) {
            appendNames(part.trim(), result)
        }

        return if (result.isNotEmpty()) result else listOf(trimmed)
    }

    /**
     * Returns the primary / main artist name.
     */
    fun primary(credit: String): String {
        return names(credit).firstOrNull() ?: credit.trim()
    }

    private fun appendNames(text: String, result: MutableList<String>) {
        if (text.isEmpty()) return
        val lower = text.lowercase()
        var splitIndex = -1
        var markerLen = 0

        for (marker in BARE_MARKERS) {
            val idx = lower.indexOf(marker)
            if (idx >= 0) {
                val prev = if (idx > 0) lower[idx - 1] else ' '
                if (prev == ' ' || prev == '(' || prev == '[' || prev == '-' || prev == ',') {
                    splitIndex = idx
                    markerLen = marker.length
                    break
                }
            }
        }

        if (splitIndex >= 0) {
            val head = text.substring(0, splitIndex).trimEnd(' ', '(', '[', '-', ',', '\u2013')
            appendCommaSeparated(head, result)
            val tail = text.substring(splitIndex + markerLen).trimStart(' ', ':').trimEnd(' ', ')', ']')
            appendNames(tail, result)
        } else {
            appendCommaSeparated(text, result)
        }
    }

    private fun appendCommaSeparated(value: String, result: MutableList<String>) {
        val cleaned = value.trim()
        if (cleaned.isEmpty()) return

        // Preserve established band names that use '&' or '+' with commas (e.g. Earth, Wind & Fire)
        if (cleaned.contains('&') || cleaned.contains('+')) {
            appendUnique(cleaned, result)
            return
        }

        val items = cleaned.split(',')
        for (item in items) {
            appendUnique(item.trim(), result)
        }
    }

    private fun appendUnique(name: String, result: MutableList<String>) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty() && result.none { it.equals(trimmed, ignoreCase = true) }) {
            result.add(trimmed)
        }
    }
}
