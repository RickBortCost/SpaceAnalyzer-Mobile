package it.spaceanalyzer.mobile.link

data class SignalInfo(val id: Int, val name: String, val unit: String)

data class LatestValue(val value: Double, val timestampUs: Long, val quality: Int, val receivedAtMs: Long)

sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    data class Streaming(val serverName: String, val acquisition: String) : LinkState
    data class Reconnecting(val attempt: Int, val reason: String) : LinkState
    /** Terminal: authentication, pin or protocol problems are not retried. */
    data class Failed(val reason: String) : LinkState
}

/** Result of one received batch, for sequencing and gap reporting. */
data class BatchStats(val lastSeq: Long, val gaps: Int, val samples: Long)

class Ring(private val capacity: Int) {
    private val times = LongArray(capacity)
    private val values = DoubleArray(capacity)
    private var head = 0
    private var size = 0
    @Synchronized fun add(timeUs: Long, value: Double) {
        times[head] = timeUs; values[head] = value
        head = (head + 1) % capacity
        if (size < capacity) size++
    }
    /** Chronological copy. */
    @Synchronized fun snapshot(): Series {
        val t = LongArray(size); val v = DoubleArray(size)
        val start = (head - size + capacity) % capacity
        for (i in 0 until size) { val j = (start + i) % capacity; t[i] = times[j]; v[i] = values[j] }
        return Series(t, v)
    }
}

class Series(val timesUs: LongArray, val values: DoubleArray) {
    val size get() = values.size
}
