package dev.dineshkumar.aescamera.viewer.extractor

import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser

/**
 * A [FragmentedMp4Extractor] for the iOS app's recordings (container 2).
 *
 * Those files have no `sidx`, so the extractor alone reports them unseekable with an
 * unknown duration. This wrapper substitutes a seek map built from the `moof` boxes
 * ([indexProvider], see [Fmp4Index]): each fragment starts with a keyframe, so seeking
 * jumps to the fragment at or before the target and the player decodes forward from
 * there. A [TimestampAdjuster] moves the first sample to time 0, because iOS stamps
 * samples with the capture clock rather than starting at zero.
 *
 * Rotation needs nothing extra: it's in the MP4 track matrix, which the extractor reads.
 * [indexProvider] runs on the loader thread (it does file I/O and decryption).
 */
@UnstableApi
class IndexedFmp4Extractor(
    private val indexProvider: () -> Fmp4Index?,
) : Extractor {

    private val delegate = FragmentedMp4Extractor(
        SubtitleParser.Factory.UNSUPPORTED,
        /* flags= */ 0,
        TimestampAdjuster(0),
        /* sideloadedTrack= */ null,
        /* closedCaptionFormats= */ emptyList(),
        /* additionalEmsgTrackOutput= */ null
    )

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        delegate.init(SeekMapReplacingOutput(output))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

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
            val index = runCatching(indexProvider).getOrNull()
            real.seekMap(if (index != null) FragmentSeekMap(index) else seekMap)
        }
    }

    private class FragmentSeekMap(private val index: Fmp4Index) : SeekMap {

        override fun isSeekable(): Boolean = true

        override fun getDurationUs(): Long = index.durationUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val i = index.pointIndexAtOrBefore(timeUs)
            val before = SeekPoint(index.timesUs[i], index.positions[i])
            if (index.timesUs[i] == timeUs || i + 1 >= index.timesUs.size) {
                return SeekMap.SeekPoints(before)
            }
            return SeekMap.SeekPoints(before, SeekPoint(index.timesUs[i + 1], index.positions[i + 1]))
        }
    }
}
