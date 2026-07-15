package dev.dineshkumar.aescamera.viewer.extractor

/**
 * Minimal MPEG-2 TS parser that extracts PES PTS values (90 kHz ticks).
 *
 * MediaRecorder's TS muxer writes no PCR at all (verified on device), so ExoPlayer's
 * PCR-based duration/seeking doesn't work on our recordings. Every PES packet does carry
 * a PTS though, so the stream duration can be measured as (last PTS - first PTS).
 *
 * The input must be packet-aligned: byte 0 is the start of a TS packet.
 */
object TsPtsParser {
    const val TS_PACKET_SIZE = 188

    private const val SYNC_BYTE = 0x47

    /** Returns the first PES PTS found in [data], or null if there is none. */
    fun findFirstPts(data: ByteArray): Long? {
        var offset = 0
        while (offset + TS_PACKET_SIZE <= data.size) {
            val pts = readPts(data, offset)
            if (pts != null) {
                return pts
            }
            offset += TS_PACKET_SIZE
        }
        return null
    }

    /** Returns the last PES PTS found in [data], or null if there is none. */
    fun findLastPts(data: ByteArray): Long? {
        var offset = ((data.size / TS_PACKET_SIZE) - 1) * TS_PACKET_SIZE
        while (offset >= 0) {
            val pts = readPts(data, offset)
            if (pts != null) {
                return pts
            }
            offset -= TS_PACKET_SIZE
        }
        return null
    }

    /** Reads the PTS of the PES packet starting in the TS packet at [packetStart], if any. */
    private fun readPts(data: ByteArray, packetStart: Int): Long? {
        if (packetStart + TS_PACKET_SIZE > data.size) return null
        if (byteAt(data, packetStart) != SYNC_BYTE) return null

        // Only packets carrying the start of a PES packet have a PES header (and PTS).
        val payloadUnitStart = byteAt(data, packetStart + 1) and 0x40 != 0
        if (!payloadUnitStart) return null

        val adaptationFieldControl = (byteAt(data, packetStart + 3) shr 4) and 0x3
        if (adaptationFieldControl and 0x1 == 0) return null // no payload

        var payload = packetStart + 4
        if (adaptationFieldControl and 0x2 != 0) {
            payload += 1 + byteAt(data, packetStart + 4)
        }

        // PES header: start code (3) + stream id (1) + length (2) + flags (2) + header len (1)
        // + PTS (5) = payload + 14 bytes minimum.
        if (payload + 14 > packetStart + TS_PACKET_SIZE) return null
        if (byteAt(data, payload) != 0x00 ||
            byteAt(data, payload + 1) != 0x00 ||
            byteAt(data, payload + 2) != 0x01
        ) return null

        val streamId = byteAt(data, payload + 3)
        // Audio (0xC0-0xDF) and video (0xE0-0xEF) elementary streams.
        if (streamId < 0xC0 || streamId > 0xEF) return null

        val ptsDtsFlags = (byteAt(data, payload + 7) shr 6) and 0x3
        if (ptsDtsFlags and 0x2 == 0) return null // no PTS

        val p = payload + 9
        return (((byteAt(data, p) shr 1) and 0x07).toLong() shl 30) or
            (byteAt(data, p + 1).toLong() shl 22) or
            ((byteAt(data, p + 2) shr 1).toLong() shl 15) or
            (byteAt(data, p + 3).toLong() shl 7) or
            (byteAt(data, p + 4) shr 1).toLong()
    }

    private fun byteAt(data: ByteArray, index: Int): Int = data[index].toInt() and 0xFF
}
