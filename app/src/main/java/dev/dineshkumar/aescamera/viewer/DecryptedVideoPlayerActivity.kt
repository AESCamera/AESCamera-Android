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
import androidx.media3.ui.PlayerView
import dev.dineshkumar.aescamera.viewer.extractor.EncvDurationReader
import dev.dineshkumar.aescamera.viewer.extractor.PtsSeekableTsExtractor
import dev.dineshkumar.aescamera.AESCameraApplication
import dev.dineshkumar.aescamera.ProtectedBaseActivity
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.databinding.ActivityDecryptedVideoPlayerBinding
import dev.dineshkumar.aescamera.utils.EncryptionUtils
import dev.dineshkumar.aescamera.utils.EncvHeader
import dev.dineshkumar.aescamera.utils.applySystemBarInsetsAsPadding
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
 * the gallery order). Previous/next is driven by the shared gallery buttons (see
 * [GalleryNav]): a neighbouring video is reached by seeking within this playlist, while a
 * neighbouring image hands off to the image viewer — so navigation is continuous across
 * the whole vault, not just videos.
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

    // Gallery navigation: the full ordered vault list (images + videos), the video-only
    // subset ExoPlayer actually holds as a playlist, and whether prev/next may show.
    private var galleryPaths: List<String>? = null
    private var playlistFiles: List<File> = emptyList()
    private var navAllowed = false
    private var controllerVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDecryptedVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.decrypted_video_player_title)

        // Edge-to-edge (targetSdk 36): the video stays full-bleed, but inset the Media3
        // controller so its bottom bar (time, settings) clears the navigation bar.
        binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
            ?.applySystemBarInsetsAsPadding(top = false)

        galleryPaths = intent.getStringArrayListExtra(GalleryNav.EXTRA_PATHS)
        val path = galleryPaths?.let { paths ->
            paths[intent.getIntExtra(GalleryNav.EXTRA_INDEX, 0).coerceIn(paths.indices)]
        } ?: intent.getStringExtra(EXTRA_PATH) ?: intent.data?.path
        val file = path?.let { File(it) }

        if (file == null || !file.exists()) {
            showError(getString(R.string.error_no_file_selected))
            return
        }

        videoFile = file
        supportActionBar?.title = file.name

        // Replace ExoPlayer's built-in previous/next (they only walk its own video
        // playlist) with the unified gallery buttons, keeping every other control.
        binding.playerView.setShowNextButton(false)
        binding.playerView.setShowPreviousButton(false)
        // Our overlay buttons live with the player controls: shown/hidden together.
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                controllerVisible = visibility == View.VISIBLE
                updateNavButtons()
            }
        )

        (application as AESCameraApplication).keyLiveData.observe(this) {
            // Key changed (entered or cleared): rebuild the player with the new key.
            releasePlayer()
            initializePlayer()
        }

        binding.videoPlayerResetKey.setOnClickListener {
            (application as AESCameraApplication).key = null
        }
        binding.videoPlayerPrevious.setOnClickListener { navigate(-1) }
        binding.videoPlayerNext.setOnClickListener { navigate(1) }
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
        playlistFiles = playlist
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
                updateNavButtons()
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
        navAllowed = true
        updateNavButtons()
    }

    /**
     * Steps [delta] items through the gallery. A video neighbour is reached by seeking
     * within ExoPlayer's playlist (no reload); an image neighbour hands off to the image
     * viewer at that position, so previous/next feels continuous across both types.
     */
    private fun navigate(delta: Int) {
        val paths = galleryPaths ?: return
        val curIdx = paths.indexOf(videoFile?.absolutePath)
        if (curIdx < 0) return
        val target = curIdx + delta
        if (target !in paths.indices) return

        val targetPath = paths[target]
        if (GalleryNav.isVideo(targetPath)) {
            val playlistIndex = playlistFiles.indexOfFirst { it.absolutePath == targetPath }
            val activePlayer = player
            if (playlistIndex >= 0 && activePlayer != null) {
                activePlayer.seekToDefaultPosition(playlistIndex)
                activePlayer.playWhenReady = true
            }
        } else {
            startActivity(GalleryNav.intentFor(this, paths, target))
            finish()
        }
    }

    /** Shows the prev/next buttons alongside the controls, dimmed at the list ends. */
    private fun updateNavButtons() {
        val paths = galleryPaths
        val prev = binding.videoPlayerPrevious
        val next = binding.videoPlayerNext

        if (!navAllowed || !controllerVisible || paths == null || paths.size <= 1) {
            prev.visibility = View.GONE
            next.visibility = View.GONE
            return
        }

        val idx = paths.indexOf(videoFile?.absolutePath)
        setNavButton(prev, idx > 0)
        setNavButton(next, idx in 0 until paths.size - 1)
    }

    /** Dim-and-disable at the ends of the list, matching the image viewer. */
    private fun setNavButton(button: View, enabled: Boolean) {
        button.visibility = View.VISIBLE
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.3f
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
        navAllowed = false
        updateNavButtons()
    }

    private fun showError(message: String, showResetKey: Boolean = false) {
        binding.videoPlayerError.text = message
        binding.videoPlayerError.visibility = View.VISIBLE
        binding.videoPlayerResetKey.visibility = if (showResetKey) View.VISIBLE else View.GONE
        binding.playerView.visibility = View.INVISIBLE
        navAllowed = false
        updateNavButtons()
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
