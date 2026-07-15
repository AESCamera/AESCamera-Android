package dev.dineshkumar.aescamera

import dev.dineshkumar.aescamera.utils.EncryptionUtils
import dev.dineshkumar.aescamera.utils.EncvHeader
import dev.dineshkumar.aescamera.viewer.extractor.EncvDurationReader
import dev.dineshkumar.aescamera.viewer.extractor.TsPtsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class TsPtsParserTest {

    /** Builds a 188-byte TS packet whose payload starts a PES packet with the given PTS. */
    private fun pesPacket(pts: Long, streamId: Int = 0xE0): ByteArray {
        val packet = ByteArray(TsPtsParser.TS_PACKET_SIZE) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = 0x40 // payload unit start, pid 0
        packet[2] = 0x21
        packet[3] = 0x10 // payload only, no adaptation field

        var p = 4
        packet[p] = 0x00; packet[p + 1] = 0x00; packet[p + 2] = 0x01 // PES start code
        packet[p + 3] = streamId.toByte()
        packet[p + 4] = 0x00; packet[p + 5] = 0x00 // PES length (unbounded)
        packet[p + 6] = 0x80.toByte() // marker bits
        packet[p + 7] = 0x80.toByte() // PTS_DTS_flags = '10' (PTS only)
        packet[p + 8] = 0x05 // PES header data length
        p += 9
        packet[p] = (0x21 or (((pts shr 30) and 0x07) shl 1).toInt()).toByte()
        packet[p + 1] = ((pts shr 22) and 0xFF).toByte()
        packet[p + 2] = (0x01 or (((pts shr 15) and 0x7F) shl 1).toInt()).toByte()
        packet[p + 3] = ((pts shr 7) and 0xFF).toByte()
        packet[p + 4] = (0x01 or ((pts and 0x7F) shl 1).toInt()).toByte()
        return packet
    }

    /** A TS packet that continues a PES packet (no PES header, no PTS). */
    private fun fillerPacket(): ByteArray {
        val packet = ByteArray(TsPtsParser.TS_PACKET_SIZE) { 0xAB.toByte() }
        packet[0] = 0x47
        packet[1] = 0x00 // no payload unit start
        packet[2] = 0x21
        packet[3] = 0x10
        return packet
    }

    @Test
    fun parsesPtsRoundTrip() {
        for (pts in longArrayOf(0, 1, 90_000, 0x1FFFFFFFFL, 0x155555555L)) {
            assertEquals(pts, TsPtsParser.findFirstPts(pesPacket(pts)))
        }
    }

    @Test
    fun findsFirstAndLastAcrossPackets() {
        val stream = fillerPacket() + pesPacket(90_000) + fillerPacket() +
            pesPacket(180_000, streamId = 0xC0) + pesPacket(450_000) + fillerPacket()
        assertEquals(90_000L, TsPtsParser.findFirstPts(stream))
        assertEquals(450_000L, TsPtsParser.findLastPts(stream))
    }

    @Test
    fun ignoresPacketsWithoutPts() {
        assertNull(TsPtsParser.findFirstPts(fillerPacket() + fillerPacket()))
        assertNull(TsPtsParser.findLastPts(ByteArray(0)))
    }

    @Test
    fun durationReaderMeasuresEncryptedStream() {
        val password = "test-password"
        // 10 seconds between first and last PTS, with filler in between.
        val stream = pesPacket(90_000) + ByteArray(400 * TsPtsParser.TS_PACKET_SIZE).also {
            for (i in it.indices step TsPtsParser.TS_PACKET_SIZE) {
                fillerPacket().copyInto(it, i)
            }
        } + pesPacket(990_000)

        val file = File.createTempFile("encv-duration", ".encv").apply { deleteOnExit() }
        EncryptionUtils.createEncryptedVideoOutputStream(file, password, EncvHeader())
            .use { it.write(stream) }

        assertEquals(10_000_000L, EncvDurationReader.readDurationUs(file, password))
    }
}
