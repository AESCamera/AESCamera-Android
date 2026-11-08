package dev.dineshkumar.aescamera.viewer.extractor

/**
 * Seek points and duration of a fragmented MP4 (the iOS app's recordings), found by
 * walking the top-level boxes: the `moov` gives the tracks and their timescales, and
 * each `moof` gives the decode time (`tfdt`) and sample durations (`trun`) of its
 * fragment. The iOS writer emits no `sidx` and leaves the `moov` durations at 0, so this
 * is the only way to get a duration and seek points for these files.
 *
 * Times are relative to the earliest sample of the first fragment, matching the
 * extractor's TimestampAdjuster(0), because iOS stamps samples with the capture clock
 * (the first `tfdt` is typically hours, not zero).
 *
 * Only `moov`/`moof` boxes are read (a few hundred bytes per fragment); `mdat` is
 * skipped. [read] returns plaintext bytes at a stream offset.
 */
class Fmp4Index private constructor(
    /** Seek point times in microseconds, ascending; the first is 0. */
    val timesUs: LongArray,
    /** Stream offsets of the `moof` boxes that start at [timesUs]. */
    val positions: LongArray,
    val durationUs: Long,
) {

    /** Index of the last seek point at or before [timeUs]. */
    fun pointIndexAtOrBefore(timeUs: Long): Int {
        var low = 0
        var high = timesUs.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (timesUs[mid] <= timeUs) low = mid else high = mid - 1
        }
        return low
    }

    private class Track(val id: Int, val isVideo: Boolean, val timescale: Long)

    private class Fragment(
        val position: Long,
        val startUs: Long,
        val endUs: Long,
        val startsWithSync: Boolean,
        val earliestUs: Long,
    )

    companion object {
        /** Larger `moov`/`moof` boxes than this mean the stream isn't what we expect. */
        private const val MAX_METADATA_BOX = 4 * 1024 * 1024

        private const val SAMPLE_IS_NON_SYNC = 0x00010000

        /**
         * Builds the index of a stream of [length] bytes, or returns null when it isn't a
         * fragmented MP4 with a video track (so the caller keeps the extractor's own map).
         */
        fun build(length: Long, read: (offset: Long, count: Int) -> ByteArray): Fmp4Index? {
            var tracks: Map<Int, Track>? = null
            val trexDurations = HashMap<Int, Long>()
            val trexFlags = HashMap<Int, Int>()
            val fragments = ArrayList<Fragment>()

            var offset = 0L
            while (offset + 8 <= length) {
                val head = read(offset, minOf(16L, length - offset).toInt())
                if (head.size < 8) break
                var size = u32(head, 0)
                val type = fourcc(head, 4)
                var headerSize = 8
                if (size == 1L) {
                    if (head.size < 16) break
                    size = u64(head, 8)
                    headerSize = 16
                } else if (size == 0L) {
                    size = length - offset
                }
                if (size < headerSize || offset + size > length) break

                when (type) {
                    "moov" -> {
                        if (size > MAX_METADATA_BOX) return null
                        val box = read(offset, size.toInt())
                        tracks = parseMoov(box, headerSize, trexDurations, trexFlags)
                    }

                    "moof" -> {
                        val known = tracks ?: return null
                        if (size > MAX_METADATA_BOX) return null
                        val box = read(offset, size.toInt())
                        parseMoof(box, headerSize, offset, known, trexDurations, trexFlags)
                            ?.let(fragments::add)
                    }
                }
                offset += size
            }

            if (fragments.isEmpty()) return null
            val baseUs = fragments.first().earliestUs
            val times = ArrayList<Long>()
            val positions = ArrayList<Long>()
            fragments.forEachIndexed { index, fragment ->
                // The first fragment is always a point, so seeks to 0 land on the start.
                if (index == 0 || fragment.startsWithSync) {
                    times += maxOf(0L, fragment.startUs - baseUs)
                    positions += fragment.position
                }
            }
            times[0] = 0L
            val durationUs = maxOf(0L, fragments.maxOf { it.endUs } - baseUs)
            return Fmp4Index(times.toLongArray(), positions.toLongArray(), durationUs)
        }

        private fun parseMoov(
            box: ByteArray,
            headerSize: Int,
            trexDurations: MutableMap<Int, Long>,
            trexFlags: MutableMap<Int, Int>,
        ): Map<Int, Track> {
            val tracks = HashMap<Int, Track>()
            children(box, headerSize, box.size) { type, start, end ->
                when (type) {
                    "trak" -> parseTrak(box, start, end)?.let { tracks[it.id] = it }
                    "mvex" -> children(box, start, end) { childType, s, _ ->
                        if (childType == "trex") {
                            // version/flags(4) track_ID(4) desc_index(4) duration(4) size(4) flags(4)
                            val trackId = u32(box, s + 4).toInt()
                            trexDurations[trackId] = u32(box, s + 12)
                            trexFlags[trackId] = u32(box, s + 20).toInt()
                        }
                    }
                }
            }
            return tracks
        }

        private fun parseTrak(box: ByteArray, start: Int, end: Int): Track? {
            var id = -1
            var timescale = 0L
            var handler = ""
            children(box, start, end) { type, s, e ->
                when (type) {
                    "tkhd" -> id = u32(box, s + if (box[s].toInt() == 1) 20 else 12).toInt()
                    "mdia" -> children(box, s, e) { childType, cs, _ ->
                        when (childType) {
                            "mdhd" -> timescale = u32(box, cs + if (box[cs].toInt() == 1) 20 else 12)
                            "hdlr" -> handler = fourcc(box, cs + 8)
                        }
                    }
                }
            }
            if (id < 0 || timescale <= 0) return null
            return Track(id, handler == "vide", timescale)
        }

        private fun parseMoof(
            box: ByteArray,
            headerSize: Int,
            position: Long,
            tracks: Map<Int, Track>,
            trexDurations: Map<Int, Long>,
            trexFlags: Map<Int, Int>,
        ): Fragment? {
            var video: Fragment? = null
            var earliestUs = Long.MAX_VALUE
            children(box, headerSize, box.size) { type, start, end ->
                if (type != "traf") return@children
                val run = parseTraf(box, start, end, trexDurations, trexFlags) ?: return@children
                val track = tracks[run.trackId] ?: return@children
                val startUs = run.decodeTime * 1_000_000L / track.timescale
                earliestUs = minOf(earliestUs, startUs)
                if (track.isVideo && video == null) {
                    val endUs = (run.decodeTime + run.duration) * 1_000_000L / track.timescale
                    video = Fragment(position, startUs, endUs, run.firstIsSync, startUs)
                }
            }
            return video?.let {
                Fragment(it.position, it.startUs, it.endUs, it.startsWithSync, earliestUs)
            }
        }

        private class TrackRun(
            val trackId: Int,
            val decodeTime: Long,
            val duration: Long,
            val firstIsSync: Boolean,
        )

        private fun parseTraf(
            box: ByteArray,
            start: Int,
            end: Int,
            trexDurations: Map<Int, Long>,
            trexFlags: Map<Int, Int>,
        ): TrackRun? {
            var trackId = -1
            var defaultDuration = 0L
            var defaultFlags = 0
            var decodeTime = -1L
            var duration = 0L
            var firstFlags: Int? = null
            children(box, start, end) { type, s, _ ->
                when (type) {
                    "tfhd" -> {
                        val flags = u32(box, s).toInt() and 0xFFFFFF
                        trackId = u32(box, s + 4).toInt()
                        defaultDuration = trexDurations[trackId] ?: 0L
                        defaultFlags = trexFlags[trackId] ?: 0
                        var p = s + 8
                        if (flags and 0x01 != 0) p += 8 // base data offset
                        if (flags and 0x02 != 0) p += 4 // sample description index
                        if (flags and 0x08 != 0) { defaultDuration = u32(box, p); p += 4 }
                        if (flags and 0x10 != 0) p += 4 // default sample size
                        if (flags and 0x20 != 0) defaultFlags = u32(box, p).toInt()
                    }

                    "tfdt" -> decodeTime = if (box[s].toInt() == 1) u64(box, s + 4) else u32(box, s + 4)

                    "trun" -> {
                        val flags = u32(box, s).toInt() and 0xFFFFFF
                        val count = u32(box, s + 4)
                        var p = s + 8
                        if (flags and 0x01 != 0) p += 4 // data offset
                        var firstSampleFlags: Int? = null
                        if (flags and 0x04 != 0) { firstSampleFlags = u32(box, p).toInt(); p += 4 }
                        val hasDuration = flags and 0x100 != 0
                        val hasSize = flags and 0x200 != 0
                        val hasFlags = flags and 0x400 != 0
                        val hasCts = flags and 0x800 != 0
                        if (!hasDuration && !hasSize && !hasFlags && !hasCts) {
                            // No per-sample fields: nothing to walk (and a corrupt count
                            // can't make the loop below spin).
                            duration += count * defaultDuration
                            if (count > 0 && firstFlags == null) firstFlags = firstSampleFlags ?: defaultFlags
                            return@children
                        }
                        // Each sample reads at least 4 bytes, so a corrupt count runs
                        // off the end of the box and throws instead of spinning.
                        for (i in 0 until count) {
                            if (hasDuration) {
                                duration += u32(box, p); p += 4
                            } else {
                                duration += defaultDuration
                            }
                            if (hasSize) p += 4
                            var sampleFlags = defaultFlags
                            if (hasFlags) { sampleFlags = u32(box, p).toInt(); p += 4 }
                            if (hasCts) p += 4
                            if (i == 0L && firstFlags == null) {
                                firstFlags = firstSampleFlags ?: sampleFlags
                            }
                        }
                    }
                }
            }
            if (trackId < 0 || decodeTime < 0) return null
            val sync = ((firstFlags ?: defaultFlags) and SAMPLE_IS_NON_SYNC) == 0
            return TrackRun(trackId, decodeTime, duration, sync)
        }

        /** Calls [visit] with each child box's type and payload range within [start, end). */
        private inline fun children(
            box: ByteArray,
            start: Int,
            end: Int,
            visit: (type: String, payloadStart: Int, payloadEnd: Int) -> Unit,
        ) {
            var p = start
            while (p + 8 <= end) {
                val size = u32(box, p)
                if (size < 8 || p + size > end) return
                visit(fourcc(box, p + 4), p + 8, (p + size).toInt())
                p += size.toInt()
            }
        }

        private fun u32(b: ByteArray, at: Int): Long {
            if (at < 0 || at + 4 > b.size) throw IndexOutOfBoundsException("box truncated")
            return ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
                    ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
        }

        private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)

        private fun fourcc(b: ByteArray, at: Int): String =
            if (at + 4 > b.size) "" else String(b, at, 4, Charsets.US_ASCII)
    }
}
