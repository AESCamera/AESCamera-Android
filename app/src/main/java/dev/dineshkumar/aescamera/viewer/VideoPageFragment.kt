package dev.dineshkumar.aescamera.viewer

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import dev.dineshkumar.aescamera.AESCameraApplication
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.databinding.FragmentVideoPageBinding
import dev.dineshkumar.aescamera.utils.EncryptionUtils
import dev.dineshkumar.aescamera.utils.EncvHeader
import dev.dineshkumar.aescamera.utils.EncvVerification
import dev.dineshkumar.aescamera.utils.applySystemBarInsetsAsPadding
import dev.dineshkumar.aescamera.viewer.datasource.EncryptedCTRDataSourceFactory
import dev.dineshkumar.aescamera.viewer.extractor.EncvDurationReader
import dev.dineshkumar.aescamera.viewer.extractor.EncvFmp4Index
import dev.dineshkumar.aescamera.viewer.extractor.IndexedFmp4Extractor
import dev.dineshkumar.aescamera.viewer.extractor.PtsSeekableTsExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A video page of [MediaViewerActivity]. The page owns its ExoPlayer: it is built when
 * the page is the one on screen (ViewPager2 resumes only the current page) and released
 * when the user swipes away or leaves, so at most one player exists. The file is
 * integrity-checked (full-file HMAC, background) before the first play, with a spinner
 * while that runs; FAILED means tampering or a wrong password and playback is refused.
 *
 * Decryption happens on the fly in [EncryptedCTRDataSourceFactory]; nothing decrypted
 * is written to disk. MPEG-2 TS has no rotation metadata, so [PtsSeekableTsExtractor]
 * stamps the rotation stored in the .encv header onto the video format. Recordings from
 * the iOS app are fragmented MP4 (container 2) and play through [IndexedFmp4Extractor].
 */
@OptIn(UnstableApi::class)
class VideoPageFragment : Fragment() {

    private var _binding: FragmentVideoPageBinding? = null
    private val binding get() = _binding!!

    private lateinit var file: File
    private var imported = false
    private var player: ExoPlayer? = null
    private var verifying = false
    private var verified = false
    private var resumePositionMs = 0L
    private var playWhenReady = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(requireArguments().getString(ARG_PATH)!!)
        imported = requireArguments().getBoolean(ARG_IMPORTED)
        savedInstanceState?.let {
            resumePositionMs = it.getLong(STATE_POSITION, 0L)
            playWhenReady = it.getBoolean(STATE_PLAY_WHEN_READY, true)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoPageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Edge-to-edge: keep the Media3 controller's bottom bar above the navigation bar.
        binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
            ?.applySystemBarInsetsAsPadding(top = false)
        binding.playerView.setShowNextButton(false)
        binding.playerView.setShowPreviousButton(false)
        binding.resetKey.setOnClickListener {
            (requireActivity().application as AESCameraApplication).key = null
        }

        (requireActivity().application as AESCameraApplication).keyLiveData
            .observe(viewLifecycleOwner) {
                // Key entered or cleared: start over with the new key.
                releasePlayer()
                verified = false
                if (isResumed) {
                    initializePlayer()
                }
            }
    }

    override fun onResume() {
        super.onResume()
        initializePlayer()
    }

    override fun onPause() {
        super.onPause()
        releasePlayer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_POSITION, player?.currentPosition ?: resumePositionMs)
        outState.putBoolean(STATE_PLAY_WHEN_READY, player?.playWhenReady ?: playWhenReady)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        releasePlayer()
        _binding = null
    }

    private fun initializePlayer() {
        val b = _binding ?: return
        val key = (requireActivity().application as AESCameraApplication).key
        if (key == null) {
            // Locked: blank the page whatever else is in flight.
            b.playerView.visibility = View.INVISIBLE
            b.error.visibility = View.GONE
            b.resetKey.visibility = View.GONE
            b.unverifiedBadge.visibility = View.GONE
            b.progress.visibility = View.GONE
            return
        }
        if (player != null || verifying) {
            return
        }

        clearError()
        if (verified) {
            buildPlayer(key)
            return
        }

        verifying = true
        b.progress.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = EncryptionUtils.verifyEncv(file, key)
            withContext(Dispatchers.Main) {
                verifying = false
                val bound = _binding ?: return@withContext
                bound.progress.visibility = View.GONE
                if ((requireActivity().application as AESCameraApplication).key != key) {
                    // Locked or re-keyed meanwhile: this verdict belongs to the old key.
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
                            bound.unverifiedBadge.visibility = View.VISIBLE
                        }
                        verified = true
                        if (isResumed) {
                            buildPlayer(key)
                        }
                    }
                }
            }
        }
    }

    private fun buildPlayer(key: String) {
        val b = _binding ?: return
        // MediaRecorder's TS output has no PCR (so ExoPlayer can't derive duration or
        // seek) and no H.264 AUD NALs (so samples wouldn't be split). PtsSeekableTsExtractor
        // handles both: it enables access-unit detection and substitutes a linear seek map
        // whose duration is measured from the PES PTS at each end of the file. iOS
        // recordings (fragmented MP4) get a seek map built from their fragments instead.
        // The factory and providers are invoked on ExoPlayer's loader thread.
        val extractorsFactory = object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> {
                // An unreadable or unsupported header fails in the data source anyway.
                val container = runCatching { EncvHeader.fromFile(file).container }
                    .getOrDefault(EncvHeader.CONTAINER_MPEG2_TS)
                if (container == EncvHeader.CONTAINER_FRAGMENTED_MP4) {
                    return arrayOf(IndexedFmp4Extractor { EncvFmp4Index.read(file, key) })
                }
                return arrayOf(
                    PtsSeekableTsExtractor(
                        durationUsProvider = { EncvDurationReader.readDurationUs(file, key) },
                        rotationDegreesProvider = {
                            runCatching { EncvHeader.fromFile(file).rotationDegrees }
                                .onFailure { Log.e(TAG, "Failed to read rotation of ${file.name}", it) }
                                .getOrDefault(0)
                        }
                    )
                )
            }
        }

        val exoPlayer = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(
                ProgressiveMediaSource.Factory(EncryptedCTRDataSourceFactory(key), extractorsFactory)
            )
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error: ${error.message}", error)
                // A garbage stream (wrong key) surfaces as a source/parsing error.
                showError(getString(R.string.video_playback_failed_message), showResetKey = true)
            }
        })

        b.playerView.player = exoPlayer
        b.playerView.visibility = View.VISIBLE
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exoPlayer.prepare()
        if (resumePositionMs > 0) {
            exoPlayer.seekTo(resumePositionMs)
        }
        exoPlayer.playWhenReady = playWhenReady
        player = exoPlayer
    }

    private fun releasePlayer() {
        player?.let {
            resumePositionMs = it.currentPosition.takeIf { pos -> pos != C.TIME_UNSET } ?: 0L
            playWhenReady = it.playWhenReady
            it.release()
        }
        player = null
        _binding?.playerView?.player = null
    }

    private fun showError(message: String, showResetKey: Boolean = false) {
        val b = _binding ?: return
        b.error.text = message
        b.error.visibility = View.VISIBLE
        b.resetKey.visibility = if (showResetKey) View.VISIBLE else View.GONE
        b.playerView.visibility = View.INVISIBLE
        b.progress.visibility = View.GONE
        releasePlayer()
    }

    private fun clearError() {
        val b = _binding ?: return
        b.error.visibility = View.GONE
        b.resetKey.visibility = View.GONE
        b.unverifiedBadge.visibility = View.GONE
        b.playerView.visibility = View.VISIBLE
    }

    companion object {
        private const val TAG = "VideoPage"
        private const val ARG_PATH = "path"
        private const val ARG_IMPORTED = "imported"
        private const val STATE_POSITION = "position"
        private const val STATE_PLAY_WHEN_READY = "playWhenReady"

        fun newInstance(file: File, imported: Boolean) = VideoPageFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PATH, file.absolutePath)
                putBoolean(ARG_IMPORTED, imported)
            }
        }
    }
}
