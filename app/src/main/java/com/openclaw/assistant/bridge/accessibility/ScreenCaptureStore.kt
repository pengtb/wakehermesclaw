package com.openclaw.assistant.bridge.accessibility

import java.util.concurrent.atomic.AtomicLong

/** One captured screen, held only in memory. */
class ScreenCapture(
    val id: String,
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val longEdge: Int,
    val quality: Int,
    val createdAt: Long,
) {
    val mime: String get() = "image/jpeg"
    val expiresAt: Long get() = createdAt + ScreenCaptureStore.TTL_MS
}

/**
 * Short-lived, memory-only hand-off between `screen.screenshot` (stage 1, metadata)
 * and `screen.screenshot.fetch` (stage 2, base64 pixels).
 *
 * Two stages exist because the bridge transport is JSON: shipping a full-screen
 * JPEG inline in one /execute response makes the payload large for every caller,
 * even the ones that only wanted to know "did the screen change". Hermes asks for
 * the metadata, decides, then drains the pixels by id.
 *
 * Nothing is persisted: no files, no cache dir, nothing survives a process restart
 * or the TTL (5 minutes). Entries are also bounded by count and total bytes so a
 * runaway caller cannot grow the service's heap.
 */
object ScreenCaptureStore {
    const val TTL_MS = 5 * 60 * 1000L
    private const val MAX_ENTRIES = 4
    private const val MAX_TOTAL_BYTES = 48L * 1024 * 1024

    private val counter = AtomicLong(0L)
    private val entries = object : LinkedHashMap<String, ScreenCapture>(8, 0.75f, true) {}

    @Synchronized
    fun put(
        jpeg: ByteArray,
        width: Int,
        height: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        longEdge: Int,
        quality: Int,
        now: Long = System.currentTimeMillis(),
    ): ScreenCapture {
        val id = "sc-%d-%d".format(now, counter.incrementAndGet())
        val capture = ScreenCapture(
            id = id,
            jpeg = jpeg,
            width = width,
            height = height,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            longEdge = longEdge,
            quality = quality,
            createdAt = now,
        )
        entries[id] = capture
        pruneLocked(now)
        return capture
    }

    /** The capture, or null when the id is unknown or already expired (expired ones are dropped). */
    @Synchronized
    fun get(id: String, now: Long = System.currentTimeMillis()): ScreenCapture? {
        pruneLocked(now)
        return entries[id]
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun pruneLocked(now: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.createdAt > TTL_MS) iterator.remove()
        }
        val it2 = entries.entries.iterator()
        while (entries.size > MAX_ENTRIES && it2.hasNext()) {
            it2.next()
            it2.remove()
        }
        var total = entries.values.sumOf { it.jpeg.size.toLong() }
        val it3 = entries.entries.iterator()
        while (total > MAX_TOTAL_BYTES && it3.hasNext()) {
            val victim = it3.next()
            total -= victim.value.jpeg.size
            it3.remove()
        }
    }
}
