package dev.conduit.completion.engine

/** Recent request latencies with percentiles, for adaptive debouncing, model choice and benchmarks. */
class LatencyStats(
    private val window: Int = DEFAULT_WINDOW,
) {
    private val samples = ArrayDeque<Long>()

    @Synchronized
    fun record(millis: Long) {
        samples.addLast(millis)
        while (samples.size > window) samples.removeFirst()
    }

    @Synchronized
    fun percentile(p: Int): Long? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        val index = ((p / PERCENT) * (sorted.size - 1) + HALF).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    val p50: Long? get() = percentile(P50)
    val p95: Long? get() = percentile(P95)

    @Synchronized
    fun count(): Int = samples.size

    /**
     * How long to wait after a keystroke before asking the model: short for fast models, longer
     * for slow ones so a burst of typing does not queue requests that are stale on arrival.
     */
    fun debounceMillis(): Long {
        val typical = p50 ?: return DEFAULT_DEBOUNCE_MS
        return (typical / DEBOUNCE_DIVISOR).coerceIn(MIN_DEBOUNCE_MS, MAX_DEBOUNCE_MS)
    }

    companion object {
        const val DEFAULT_WINDOW = 50
        const val DEFAULT_DEBOUNCE_MS = 120L
        private const val MIN_DEBOUNCE_MS = 60L
        private const val MAX_DEBOUNCE_MS = 400L
        private const val DEBOUNCE_DIVISOR = 3
        private const val PERCENT = 100.0
        private const val HALF = 0.5
        private const val P50 = 50
        private const val P95 = 95
    }
}
