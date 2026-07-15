package dev.dineshkumar.aescamera.viewer

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import dev.dineshkumar.aescamera.viewer.extractor.EncvDurationReader
import dev.dineshkumar.aescamera.viewer.extractor.PtsSeekableTsExtractor
import androidx.media3.ui.AspectRatioFrameLayout
import dev.dineshkumar.aescamera.AESCameraApplication
import dev.dineshkumar.aescamera.ProtectedBaseActivity
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.databinding.ActivityDecryptedVideoPlayerBinding
import dev.dineshkumar.aescamera.utils.EncvHeader
import dev.dineshkumar.aescamera.viewer.datasource.EncryptedCTRDataSourceFactory
import java.io.File

/**
 * Plays .encv files by decrypting them on the fly through [EncryptedCTRDataSourceFactory].
 * No decrypted data is ever written to disk.
 *
 * All .encv files in the same directory are loaded as a playlist (newest first, matching
 * the gallery order), so the controller's previous/next buttons navigate between
 * recordings.
 *
 * MPEG-2 TS carries no rotation metadata, so the rotation stored in the .encv header is
 * applied manually: the video TextureView is rotated (with scale compensation) and the
 * content frame's aspect ratio is swapped for 90/270 degrees. Rotation is re-read from
 * the header on every playlist item transition.
 */
@OptIn(UnstableApi::class)
class DecryptedVideoPlayerActivity : ProtectedBaseActivity() {

    private lateinit var binding: ActivityDecryptedVideoPlayerBinding
    private var player: ExoPlayer? = null
    private var videoFile: File? = null
    private var rotationDegrees: Int = 0

    /** Single persistent transform listener (re)applied on every layout pass. */
    private val surfaceTransformListener =
        View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            applySurfaceTransform(v as TextureView)
        }

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

        (binding.playerView.videoSurfaceView as? TextureView)
            ?.addOnLayoutChangeListener(surfaceTransformListener)

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

    private fun readRotationDegrees(file: File): Int {
        return try {
            file.inputStream().use { input ->
                val headerBytes = ByteArray(EncvHeader.SIZE)
                var read = 0
                while (read < headerBytes.size) {
                    val r = input.read(headerBytes, read, headerBytes.size - read)
                    if (r == -1) break
                    read += r
                }
                EncvHeader.fromBytes(headerBytes).rotationDegrees
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read .encv header of ${file.name}: ${e.message}", e)
            0
        }
    }

    private fun initializePlayer() {
        if (player != null) {
            return
        }
        val file = videoFile ?: return
        val key = (application as AESCameraApplication).key

        if (key == null) {
            showError(getString(R.string.enter_key_message))
            return
        }

        clearError()

        rotationDegrees = readRotationDegrees(file)

        val playlist = buildPlaylist(file)
        val startIndex =
            playlist.indexOfFirst { it.absolutePath == file.absolutePath }.coerceAtLeast(0)

        // MediaRecorder's TS output has no PCR (so ExoPlayer can't derive duration or
        // seek) and no H.264 AUD NALs (so samples wouldn't be split). PtsSeekableTsExtractor
        // handles both: it enables access-unit detection and substitutes a linear seek map
        // whose duration is measured from the PES PTS at each end of the file. The URI-aware
        // overload is needed so each playlist item measures its own file; it is invoked on
        // ExoPlayer's loader thread, not the main thread.
        val extractorsFactory = object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> =
                arrayOf(PtsSeekableTsExtractor { C.TIME_UNSET })

            override fun createExtractors(
                uri: Uri,
                responseHeaders: Map<String, List<String>>
            ): Array<Extractor> {
                val itemFile = uri.path?.let { File(it) }
                return arrayOf(
                    PtsSeekableTsExtractor {
                        itemFile?.let { EncvDurationReader.readDurationUs(it, key) }
                            ?: C.TIME_UNSET
                    }
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
                rotationDegrees = readRotationDegrees(itemFile)
                // Reset the transform now; onVideoSizeChanged refreshes the aspect ratio.
                (binding.playerView.videoSurfaceView as? TextureView)
                    ?.let { applySurfaceTransform(it) }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                applyRotation(videoSize)
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

    private fun releasePlayer() {
        binding.playerView.player = null
        player?.release()
        player = null
    }

    /**
     * Applies the header rotation to the video output. The TextureView is sized by the
     * content frame for the *unrotated* video; rotating it in place requires scaling so
     * the painted area matches the frame again, and for 90/270 the frame's aspect ratio
     * must be swapped.
     */
    private fun applyRotation(videoSize: VideoSize) {
        if (videoSize.width == 0 || videoSize.height == 0) {
            return
        }

        val contentFrame = binding.playerView.findViewById<AspectRatioFrameLayout>(
            androidx.media3.ui.R.id.exo_content_frame
        )
        val textureView = binding.playerView.videoSurfaceView as? TextureView ?: return

        if (rotationDegrees % 180 != 0) {
            contentFrame?.setAspectRatio(videoSize.height.toFloat() / videoSize.width)
        }

        applySurfaceTransform(textureView)
        textureView.requestLayout()
    }

    /** Rotates/scales the TextureView for [rotationDegrees]; identity when rotation is 0. */
    private fun applySurfaceTransform(textureView: TextureView) {
        val width = textureView.width.toFloat()
        val height = textureView.height.toFloat()
        if (width <= 0 || height <= 0) {
            return
        }
        textureView.pivotX = width / 2f
        textureView.pivotY = height / 2f
        textureView.rotation = rotationDegrees.toFloat()
        if (rotationDegrees % 180 != 0) {
            textureView.scaleX = height / width
            textureView.scaleY = width / height
        } else {
            textureView.scaleX = 1f
            textureView.scaleY = 1f
        }
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
