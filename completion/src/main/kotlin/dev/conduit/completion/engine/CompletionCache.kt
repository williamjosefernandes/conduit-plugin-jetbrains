package dev.conduit.completion.engine

/**
 * Remembers recent suggestions so that typing what was suggested, or re-requesting at the same
 * spot, costs no model call. Keyed by file and the text around the caret.
 */
class CompletionCache(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private data class Entry(
        val prefix: String,
        val suffix: String,
        val suggestion: String,
    )

    private val entries = LinkedHashMap<String, MutableList<Entry>>()

    /**
     * A suggestion for the caret described by [prefix] and [suffix], or null. Also matches when
     * the user has typed the first characters of a cached suggestion since it was made, in which
     * case the rest is returned.
     */
    @Synchronized
    fun get(
        file: String,
        prefix: String,
        suffix: String,
    ): String? {
        val list = entries[file] ?: return null
        for (entry in list.asReversed()) {
            if (entry.suffix != suffix) continue
            if (entry.prefix == prefix) return entry.suggestion
            if (prefix.startsWith(entry.prefix)) {
                val typed = prefix.substring(entry.prefix.length)
                if (entry.suggestion.startsWith(typed) &&
                    entry.suggestion.length > typed.length
                ) {
                    return entry.suggestion.substring(typed.length)
                }
            }
        }
        return null
    }

    @Synchronized
    fun put(
        file: String,
        prefix: String,
        suffix: String,
        suggestion: String,
    ) {
        val list = entries.getOrPut(file) { ArrayList() }
        list += Entry(prefix, suffix, suggestion)
        if (list.size > PER_FILE) list.removeAt(0)
        entries.remove(file)
        entries[file] = list
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun clear() = entries.clear()

    private companion object {
        const val DEFAULT_CAPACITY = 32
        const val PER_FILE = 16
    }
}
