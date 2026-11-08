package dev.dineshkumar.aescamera

import dev.dineshkumar.aescamera.viewer.extractor.Fmp4Index
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * [Fmp4Index] against synthetic streams shaped like the iOS app's recordings: an init
 * segment with no durations, then moof+mdat fragments whose tfdt starts at the capture
 * clock, with no sidx.
 */
class Fmp4IndexTest {

    private class Box(val type: String) {
        val body = ByteArrayOutputStream()
        fun u8(v: Int) = apply { body.write(v) }
        fun u32(v: Long) = apply { for (s in intArrayOf(24, 16, 8, 0)) body.write((v ushr s).toInt() and 0xFF) }
        fun u64(v: Long) = apply { u32(v ushr 32); u32(v and 0xFFFFFFFFL) }
        fun bytes(b: ByteArray) = apply { body.write(b) }
        fun ascii(s: String) = bytes(s.toByteArray(Charsets.US_ASCII))
        fun child(b: Box) = bytes(b.build())
        fun fullHeader(version: Int, flags: Int) = apply { u32((version.toLong() shl 24) or flags.toLong()) }
        fun build(): ByteArray {
            val payload = body.toByteArray()
            val out = ByteArrayOutputStream()
            val size = 8L + payload.size
            for (s in intArrayOf(24, 16, 8, 0)) out.write((size ushr s).toInt() and 0xFF)
            out.write(type.toByteArray(Charsets.US_ASCII))
            out.write(payload)
            return out.toByteArray()
        }
    }

    private fun trak(id: Int, handler: String, timescale: Long, version: Int = 0): Box {
        val tkhd = Box("tkhd").fullHeader(version, 3)
        if (version == 1) tkhd.u64(0).u64(0).u32(id.toLong()).u32(0).u64(0)
        else tkhd.u32(0).u32(0).u32(id.toLong()).u32(0).u32(0)
        tkhd.bytes(ByteArray(60))
        val mdhd = Box("mdhd").fullHeader(version, 0)
        if (version == 1) mdhd.u64(0).u64(0).u32(timescale).u64(0)
        else mdhd.u32(0).u32(0).u32(timescale).u32(0)
        mdhd.u32(0)
        val hdlr = Box("hdlr").fullHeader(0, 0).u32(0).ascii(handler).bytes(ByteArray(13))
        return Box("trak").child(tkhd).child(Box("mdia").child(mdhd).child(hdlr).child(Box("minf")))
    }

    private fun moov(): ByteArray = Box("moov")
        .child(Box("mvhd").fullHeader(0, 0).bytes(ByteArray(96)))
        .child(trak(1, "vide", 600))
        .child(trak(2, "soun", 44_100, version = 1))
        .child(
            Box("mvex")
                .child(Box("trex").fullHeader(0, 0).u32(1).u32(1).u32(0).u32(0).u32(0))
                .child(Box("trex").fullHeader(0, 0).u32(2).u32(1).u32(1024).u32(0).u32(0))
        )
        .build()

    /** A video traf like AVAssetWriter's: tfhd defaults, then a trun with durations. */
    private fun videoTraf(decodeTime: Long, durations: List<Long>, firstIsSync: Boolean = true): Box {
        val tfhd = Box("tfhd").fullHeader(0, 0x020038).u32(1).u32(20).u32(1000).u32(0x01010000)
        val tfdt = Box("tfdt").fullHeader(1, 0).u64(decodeTime)
        val trun = Box("trun").fullHeader(0, 0x000305).u32(durations.size.toLong()).u32(0)
            .u32(if (firstIsSync) 0x02000000 else 0x01010000)
        durations.forEach { trun.u32(it).u32(1000) }
        return Box("traf").child(tfhd).child(tfdt).child(trun)
    }

    /** An audio traf using the trex default duration and no per-sample fields. */
    private fun audioTraf(decodeTime: Long, samples: Int): Box {
        val tfhd = Box("tfhd").fullHeader(0, 0x020000).u32(2)
        val tfdt = Box("tfdt").fullHeader(0, 0).u32(decodeTime)
        val trun = Box("trun").fullHeader(0, 0x000001).u32(samples.toLong()).u32(0)
        return Box("traf").child(tfhd).child(tfdt).child(trun)
    }

    private fun moof(vararg trafs: Box): ByteArray {
        val box = Box("moof").child(Box("mfhd").fullHeader(0, 0).u32(1))
        trafs.forEach { box.child(it) }
        return box.build()
    }

    private fun mdat(size: Int) = Box("mdat").bytes(ByteArray(size)).build()

    private fun index(stream: ByteArray): Fmp4Index? =
        Fmp4Index.build(stream.size.toLong()) { offset, count ->
            stream.copyOfRange(offset.toInt(), minOf(stream.size, offset.toInt() + count))
        }

    private fun stream(vararg parts: ByteArray): Pair<ByteArray, List<Long>> {
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Long>()
        for (part in parts) {
            offsets += out.size().toLong()
            out.write(part)
        }
        return out.toByteArray() to offsets
    }

    @Test
    fun indexesFragmentsRelativeToTheCaptureClock() {
        // A capture-clock tfdt as iOS writes it (timescale 600), here a whole second so
        // the audio track's start converts exactly and both tracks start together.
        val base = 2_507_400L
        val thirty = List(30) { 20L } // 30 frames of 1/30 s = 600 ticks
        val (bytes, offsets) = stream(
            Box("ftyp").ascii("iso6").build(),
            moov(),
            moof(videoTraf(base, thirty), audioTraf(base * 44_100 / 600, 43)), mdat(5000),
            moof(videoTraf(base + 600, thirty), audioTraf((base + 600) * 44_100 / 600, 43)), mdat(4000),
            moof(videoTraf(base + 1200, List(14) { 20L })), mdat(3000),
        )

        val index = index(bytes)!!
        assertArrayEquals(longArrayOf(0, 1_000_000, 2_000_000), index.timesUs)
        assertArrayEquals(longArrayOf(offsets[2], offsets[4], offsets[6]), index.positions)
        // 2 s of full fragments plus 14 frames of the last one.
        assertEquals(2_000_000L + 14 * 20 * 1_000_000L / 600, index.durationUs)

        assertEquals(0, index.pointIndexAtOrBefore(0))
        assertEquals(0, index.pointIndexAtOrBefore(999_999))
        assertEquals(1, index.pointIndexAtOrBefore(1_000_000))
        assertEquals(2, index.pointIndexAtOrBefore(9_000_000))
    }

    @Test
    fun skipsFragmentsThatDoNotStartWithAKeyframe() {
        val (bytes, offsets) = stream(
            moov(),
            moof(videoTraf(600, List(30) { 20L })), mdat(10),
            moof(videoTraf(1200, List(30) { 20L }, firstIsSync = false)), mdat(10),
            moof(videoTraf(1800, List(30) { 20L })), mdat(10),
        )
        val index = index(bytes)!!
        assertArrayEquals(longArrayOf(0, 2_000_000), index.timesUs)
        assertArrayEquals(longArrayOf(offsets[1], offsets[5]), index.positions)
        assertEquals(3_000_000L, index.durationUs)
    }

    @Test
    fun aTruncatedRecordingIndexesUpToTheLastCompleteFragment() {
        val full = stream(
            moov(),
            moof(videoTraf(0, List(30) { 20L })), mdat(100),
            moof(videoTraf(600, List(30) { 20L })), mdat(100),
        ).first
        // Cut inside the last mdat: its box claims more bytes than remain.
        val index = index(full.copyOf(full.size - 50))!!
        assertArrayEquals(longArrayOf(0, 1_000_000), index.timesUs)
    }

    @Test
    fun returnsNullForStreamsThatAreNotFragmentedMp4() {
        assertNull(index(ByteArray(188 * 4) { if (it % 188 == 0) 0x47 else 0 })) // MPEG-TS
        assertNull(index(stream(moov(), mdat(100)).first)) // no fragments
        assertNull(index(ByteArray(0)))
    }
}
