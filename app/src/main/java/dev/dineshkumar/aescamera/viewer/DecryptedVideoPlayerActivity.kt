package dev.dineshkumar.aescamera.viewer

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.lifecycle.Lifecycle
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import dev.dineshkumar.aescamera.viewer.extractor.EncvDurationReader
import dev.dineshkumar.aescamera.viewer.extractor.PtsSeekableTsExtractor
import dev.dineshkumar.aescamera.AESCameraApplication
import dev.dineshkumar.aescamera.ProtectedBaseActivity
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.databinding.ActivityDecryptedVideoPlayerBinding
import dev.dineshkumar.aescamera.utils.EncryptionUtils
import dev.dineshkumar.aescamera.utils.EncvHeader
import dev.dineshkumar.aescamera.utils.EncvVerification
import dev.dineshkumar.aescamera.viewer.datasource.EncryptedCTRDataSourceFactory
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Plays .encv files by decrypting them on the fly through [EncryptedCTRDataSourceFactory].
 * No decrypted data is ever written to disk.
 *
 * All .encv files in the same directory are loaded as a playlist (newest first, matching
 * the gallery order), so the controller's previous/next buttons navigate between
 * recordings.
 *
 * MPEG-2 TS carries no rotation metadata, so [PtsSeekableTsExtractor] stamps the rotation
 * stored in the .encv header onto the video track's Format; the player pipeline then
 * rotates the picture and swaps the aspect ratio natively, as it would for a rotated MP4.
 */
@OptIn(UnstableApi::class)
class DecryptedVideoPlayerActivity : ProtectedBaseActivity() {

    private lateinit var binding: ActivityDecryptedVideoPlayerBinding
    private var player: ExoPlayer? = null
    private var videoFile: File? = null
    private var verifying = false
    private var verifiedPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDecryptedVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.decrypted_video_player_title)

        val path = intent.getStringExtra(EXTRA_PATH) ?: intent.data?.path
        val file = path?.let { File(it) }

        if (file == null || !file.exists()) {
            showError(getString(R.string.error_no_file_selected))
            return
        }

        videoFile = file
        supportActionBar?.title = file.name

        (application as AESCameraApplication).keyLiveData.observe(this) {
            // Key changed (entered or cleared): rebuild the player with the new key.
            releasePlayer()
            initializePlayer()
        }

        binding.videoPlayerResetKey.setOnClickListener {
            (application as AESCameraApplication).key = null
        }
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    /** All .encv files in the same directory, newest first — the gallery's order. */
    private fun buildPlaylist(file: File): List<File> {
        val siblings = file.parentFile?.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && it.extension == "encv" }
            ?.sortedByDescending { it.lastModified() }
        return if (siblings.isNullOrEmpty()) listOf(file) else siblings
    }

    /**
     * Integrity-checks [videoFile] (full-file HMAC, background thread), then builds the
     * player. FAILED means tampering, corruption, or a wrong key — playback is refused.
     * UNVERIFIED (a v2 file, or a recording that died before finalizing) plays as
     * before; there is nothing to check against.
     */
    @kotlin.OptIn(DelicateCoroutinesApi::class)
    private fun initializePlayer() {
        if (player != null || verifying) {
            return
        }
        val file = videoFile ?: return
        val key = (application as AESCameraApplication).key

        if (key == null) {
            showError(getString(R.string.enter_key_message))
            return
        }

        clearError()

        verifying = true
        GlobalScope.launch(Dispatchers.IO) {
            val result = EncryptionUtils.verifyEncv(file, key)
            withContext(Dispatchers.Main) {
                verifying = false
                // The activity may have stopped (or the file changed) while verifying.
                if (isFinishing || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    || videoFile?.absolutePath != file.absolutePath
                ) {
                    return@withContext
                }
                when (result) {
                    EncvVerification.FAILED -> showError(
                        getString(R.string.video_verification_failed_message),
                        showResetKey = true
                    )

                    else -> {
                        if (result == EncvVerification.UNVERIFIED) {
                            Log.w(TAG, "Playing unverified recording: ${file.name}")
                        }
                        verifiedPath = file.absolutePath
                        buildPlayer(file, key)
                    }
                }
            }
        }
    }

    private fun buildPlayer(file: File, key: String) {
        val playlist = buildPlaylist(file)
        val startIndex =
            playlist.indexOfFirst { it.absolutePath == file.absolutePath }.coerceAtLeast(0)

        // MediaRecorder's TS output has no PCR (so ExoPlayer can't derive duration or
        // seek) and no H.264 AUD NALs (so samples wouldn't be split). PtsSeekableTsExtractor
        // handles both: it enables access-unit detection and substitutes a linear seek map
        // whose duration is measured from the PES PTS at each end of the file. It also
        // stamps the .encv header rotation onto the video format. The URI-aware overload
        // is needed so each playlist item reads its own file; providers are invoked on
        // ExoPlayer's loader thread, not the main thread.
        val extractorsFactory = object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> =
                arrayOf(PtsSeekableTsExtractor(durationUsProvider = { C.TIME_UNSET }))

            override fun createExtractors(
                uri: Uri,
                responseHeaders: Map<String, List<String>>
            ): Array<Extractor> {
                val itemFile = uri.path?.let { File(it) }
                return arrayOf(
                    PtsSeekableTsExtractor(
                        durationUsProvider = {
                            itemFile?.let { EncvDurationReader.readDurationUs(it, key) }
                                ?: C.TIME_UNSET
                        },
                        rotationDegreesProvider = {
                            itemFile?.let { f ->
                                runCatching { EncvHeader.fromFile(f).rotationDegrees }
                                    .onFailure {
                                        Log.e(TAG, "Failed to read rotation of ${f.name}", it)
                                    }
                                    .getOrDefault(0)
                            } ?: 0
                        }
                    )
                )
            }
        }

        val mediaSourceFactory = ProgressiveMediaSource.Factory(
            EncryptedCTRDataSourceFactory(key),
            extractorsFactory
        )

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            // Don't auto-advance to the next recording when one finishes; the user
            // navigates explicitly with the previous/next buttons.
            .setPauseAtEndOfMediaItems(true)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error: ${error.message}", error)
                // A garbage stream (wrong key) surfaces as a source/parsing error.
                showError(getString(R.string.video_playback_failed_message), showResetKey = true)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val itemFile = mediaItem?.localConfiguration?.uri?.path?.let { File(it) }
                    ?: return
                videoFile = itemFile
                supportActionBar?.title = itemFile.name
                // Playlist navigation reaches files the initial check didn't cover.
                if (itemFile.absolutePath != verifiedPath) {
                    verifyInBackground(itemFile, key)
                }
            }
        })

        binding.playerView.player = exoPlayer
        exoPlayer.setMediaItems(
            playlist.map { MediaItem.fromUri(Uri.fromFile(it)) },
            startIndex,
            C.TIME_UNSET
        )
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        player = exoPlayer
    }

    /**
     * Checks a playlist item the user navigated to. The item has already started
     * playing (Media3 offers no pre-transition hook), so a FAILED verdict stops
     * playback within moments rather than preventing it outright.
     */
    @kotlin.OptIn(DelicateCoroutinesApi::class)
    private fun verifyInBackground(file: File, key: String) {
        GlobalScope.launch(Dispatchers.IO) {
            val result = EncryptionUtils.verifyEncv(file, key)
            withContext(Dispatchers.Main) {
                // Only act if this file is still the one being played.
                if (isFinishing || videoFile?.absolutePath != file.absolutePath) {
                    return@withContext
                }
                when (result) {
                    EncvVerification.FAILED -> {
                        releasePlayer()
                        showError(
                            getString(R.string.video_verification_failed_message),
                            showResetKey = true
                        )
                    }

                    else -> {
                        if (result == EncvVerification.UNVERIFIED) {
                            Log.w(TAG, "Playing unverified recording: ${file.name}")
                        }
                        verifiedPath = file.absolutePath
                    }
                }
            }
        }
    }

    private fun releasePlayer() {
        binding.playerView.player = null
        player?.release()
        player = null
    }

    private fun showError(message: String, showResetKey: Boolean = false) {
        binding.videoPlayerError.text = message
        binding.videoPlayerError.visibility = View.VISIBLE
        binding.videoPlayerResetKey.visibility = if (showResetKey) View.VISIBLE else View.GONE
        binding.playerView.visibility = View.INVISIBLE
    }

    private fun clearError() {
        binding.videoPlayerError.visibility = View.GONE
        binding.videoPlayerResetKey.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.file_viewer_menu, menu)
        menu?.findItem(R.id.action_add_to_vault)?.isVisible = false
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }

            R.id.action_lock -> {
                (application as AESCameraApplication).key = null
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val TAG = "DecryptedVideoPlayer"
        const val EXTRA_PATH = "path"
    }
}
