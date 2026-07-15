package dev.dineshkumar.aescamera.viewer.extractor

import androidx.media3.common.C
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor

/**
 * A [TsExtractor] wrapper that makes MediaRecorder's MPEG-2 TS output seekable.
 *
 * The wrapped extractor is configured with FLAG_DETECT_ACCESS_UNITS because the H.264
 * stream contains no AUD NALs. Because the stream also contains no PCR, TsExtractor
 * reports it unseekable; when that happens this wrapper substitutes a seek map that maps
 * time to byte offsets by linear interpolation ([durationUsProvider] supplies the real
 * duration, measured from PES PTS by [EncvDurationReader]). MediaRecorder output is
 * near-constant bitrate so the estimate lands close, and TsExtractor re-anchors sample
 * timestamps to the seek target after every seek, so the timeline stays consistent.
 *
 * [durationUsProvider] is only invoked on the loader thread (it does file I/O + crypto).
 */
@UnstableApi
class PtsSeekableTsExtractor(private val durationUsProvider: () -> Long) : Extractor {

    private val delegate = TsExtractor(
        TsExtractor.MODE_SINGLE_PMT,
        TimestampAdjuster(0),
        DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
    )

    private var inputLength = C.LENGTH_UNSET.toLong()

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        delegate.init(SeekMapReplacingOutput(output))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        inputLength = input.length
        return delegate.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    private inner class SeekMapReplacingOutput(private val real: ExtractorOutput) :
        ExtractorOutput {

        override fun track(id: Int, type: Int): TrackOutput = real.track(id, type)

        override fun endTracks() = real.endTracks()

        override fun seekMap(seekMap: SeekMap) {
            if (seekMap.isSeekable) {
                real.seekMap(seekMap)
                return
            }
            val durationUs = durationUsProvider()
            if (durationUs == C.TIME_UNSET || inputLength == C.LENGTH_UNSET.toLong()) {
                real.seekMap(seekMap)
            } else {
                real.seekMap(LinearInterpolatingSeekMap(durationUs, inputLength))
            }
        }
    }

    private class LinearInterpolatingSeekMap(
        private val durationUs: Long,
        private val dataLength: Long
    ) : SeekMap {

        override fun isSeekable(): Boolean = true

        override fun getDurationUs(): Long = durationUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val clampedUs = timeUs.coerceIn(0L, durationUs)
            var position = (clampedUs.toDouble() / durationUs * dataLength).toLong()
            position -= position % TsPtsParser.TS_PACKET_SIZE
            position = position.coerceIn(0L, maxOf(0L, dataLength - TsPtsParser.TS_PACKET_SIZE))
            return SeekMap.SeekPoints(SeekPoint(clampedUs, position))
        }
    }
}
