package com.devil7softwares.aescamera.viewer

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import com.devil7softwares.aescamera.AESCameraApplication
import com.devil7softwares.aescamera.ProtectedBaseActivity
import com.devil7softwares.aescamera.R
import com.devil7softwares.aescamera.databinding.ActivityDecryptedVideoPlayerBinding
import com.devil7softwares.aescamera.utils.EncvHeader
import com.devil7softwares.aescamera.viewer.datasource.EncryptedCTRDataSourceFactory
import java.io.File

/**
 * Plays .encv files by decrypting them on the fly through [EncryptedCTRDataSourceFactory].
 * No decrypted data is ever written to disk.
 *
 * MPEG-2 TS carries no rotation metadata, so the rotation stored in the .encv header is
 * applied manually: the video TextureView is rotated (with scale compensation) and the
 * content frame's aspect ratio is swapped for 90/270 degrees.
 */
@OptIn(UnstableApi::class)
class DecryptedVideoPlayerActivity : ProtectedBaseActivity() {

    private lateinit var binding: ActivityDecryptedVideoPlayerBinding
    private var player: ExoPlayer? = null
    private var videoFile: File? = null
    private var rotationDegrees: Int = 0

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

        rotationDegrees = try {
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
            Log.e(TAG, "Failed to read .encv header: ${e.message}", e)
            showError(getString(R.string.video_playback_failed_message))
            return
        }

        val mediaSourceFactory =
            ProgressiveMediaSource.Factory(EncryptedCTRDataSourceFactory(key))

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error: ${error.message}", error)
                // A garbage stream (wrong key) surfaces as a source/parsing error.
                showError(getString(R.string.video_playback_failed_message), showResetKey = true)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                applyRotation(videoSize)
            }
        })

        binding.playerView.player = exoPlayer
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
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
        if (rotationDegrees == 0 || videoSize.width == 0 || videoSize.height == 0) {
            return
        }

        val contentFrame = binding.playerView.findViewById<AspectRatioFrameLayout>(
            androidx.media3.ui.R.id.exo_content_frame
        )
        val textureView = binding.playerView.videoSurfaceView as? TextureView ?: return

        if (rotationDegrees % 180 != 0) {
            contentFrame?.setAspectRatio(videoSize.height.toFloat() / videoSize.width)
        }

        textureView.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val width = (right - left).toFloat()
            val height = v.height.toFloat()
            if (width > 0 && height > 0) {
                v.pivotX = width / 2f
                v.pivotY = height / 2f
                v.rotation = rotationDegrees.toFloat()
                if (rotationDegrees % 180 != 0) {
                    v.scaleX = height / width
                    v.scaleY = width / height
                }
            }
        }
        textureView.requestLayout()
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
