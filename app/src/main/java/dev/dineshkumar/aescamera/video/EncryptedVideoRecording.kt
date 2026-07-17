package dev.dineshkumar.aescamera.video

import android.os.ParcelFileDescriptor
import android.util.Log
import dev.dineshkumar.aescamera.utils.EncryptedVideoOutput
import dev.dineshkumar.aescamera.utils.EncryptionUtils
import dev.dineshkumar.aescamera.utils.EncvHeader
import java.io.File
import java.io.FileDescriptor

/**
 * Encrypt-while-recording pipeline for a single video recording.
 *
 * MediaRecorder (via the patched CameraView, MPEG-2 TS output format) writes the
 * container stream into the write end of a pipe; a drain thread reads it and pushes it
 * through an AES/CTR [javax.crypto.CipherOutputStream] into the `.encv` output file.
 * Plaintext video bytes never touch disk.
 *
 * There is deliberately no fallback: if any part of this pipeline fails, the recording
 * fails — recording without encryption is never acceptable.
 */
class EncryptedVideoRecording(val outputFile: File, password: String) {

    // Writes the .encv prefix (header + salt + iv) immediately. Rotation is not yet
    // known (CameraView computes it when recording ends), so it starts as 0 and is
    // patched in finish(), together with the v3 authentication trailer.
    private val encryptedStream: EncryptedVideoOutput =
        EncryptionUtils.createEncryptedVideoOutputStream(outputFile, password, EncvHeader())

    private val readSide: ParcelFileDescriptor
    private val writeSide: ParcelFileDescriptor

    @Volatile
    private var drainError: Exception? = null

    @Volatile
    private var bytesDrained: Long = 0

    private var writeSideClosed = false

    private val drainThread: Thread

    init {
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: Exception) {
            runCatching { encryptedStream.close() }
            runCatching { outputFile.delete() }
            throw e
        }
        readSide = pipe[0]
        writeSide = pipe[1]

        drainThread = Thread({
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readSide).use { input ->
                    encryptedStream.use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            bytesDrained += read
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Encryption drain failed: ${e.message}", e)
                drainError = e
            }
        }, "EncryptedVideoDrain")
        drainThread.start()
    }

    /** The pipe's write end, to pass to CameraView.takeVideo(FileDescriptor). */
    val writeFileDescriptor: FileDescriptor
        get() = writeSide.fileDescriptor

    /**
     * Closes our copy of the pipe's write end. MediaRecorder dups the descriptor in
     * setOutputFile(), so once recording has started ours must be closed — otherwise the
     * drain thread never sees EOF when the recorder finishes. Call from
     * onVideoRecordingStart().
     */
    @Synchronized
    fun closeWriteSide() {
        if (!writeSideClosed) {
            writeSideClosed = true
            runCatching { writeSide.close() }
        }
    }

    /**
     * Finalizes the recording: waits for the drain thread to hit EOF, verifies data was
     * written, patches the actual [rotationDegrees] into the plaintext header, and
     * appends the authentication trailer. Blocking — call from a background thread.
     * Throws on pipeline failure; the caller should then [abort]. If the trailer never
     * gets written (crash, error), the file stays playable but unverified.
     */
    fun finish(rotationDegrees: Int, timeoutMs: Long = 10_000) {
        closeWriteSide()
        drainThread.join(timeoutMs)
        if (drainThread.isAlive) {
            throw IllegalStateException("Encryption drain did not finish within ${timeoutMs}ms")
        }
        drainError?.let { throw it }
        if (bytesDrained == 0L) {
            throw IllegalStateException("Recording produced no data")
        }
        encryptedStream.finish(rotationDegrees)
        Log.i(TAG, "Recording finished: ${outputFile.name}, $bytesDrained bytes encrypted")
    }

    /**
     * Tears down the pipeline and deletes the partial output (ciphertext-only, so this
     * is hygiene, not security).
     */
    fun abort() {
        closeWriteSide()
        // Closing the read side unblocks the drain thread if it is stuck in read().
        runCatching { readSide.close() }
        runCatching { drainThread.join(2_000) }
        runCatching { encryptedStream.close() }
        runCatching { outputFile.delete() }
    }

    companion object {
        private const val TAG = "EncryptedVideoRec"
        private const val BUFFER_SIZE = 64 * 1024
    }
}
