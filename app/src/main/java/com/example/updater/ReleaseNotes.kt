package com.example.updater

/** Release notes are a short bullet list (the workflow writes at most [MAX_ENTRIES] lines); this reads them back. */
object ReleaseNotes {
    const val MAX_ENTRIES = 4

    fun summarize(body: String?, max: Int = MAX_ENTRIES): List<String> = body.orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("- ") || it.startsWith("* ") || it.startsWith("• ") }
        .map { line ->
            line.drop(2).trim()
                .replace("**", "")
                .replace("`", "")
                .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        }
        .filter { it.isNotBlank() }
        .take(max)
        .toList()
}
