package dev.dineshkumar.aescamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.dineshkumar.aescamera.databinding.ActivityMainBinding
import dev.dineshkumar.aescamera.list.FilesListActivity
import dev.dineshkumar.aescamera.utils.AppPrefs
import dev.dineshkumar.aescamera.utils.CommonUtils
import dev.dineshkumar.aescamera.utils.EncryptionUtils
import dev.dineshkumar.aescamera.utils.ThumbnailUtils
import dev.dineshkumar.aescamera.utils.applySystemBarInsetsAsPadding
import dev.dineshkumar.aescamera.video.EncryptedVideoRecording
import com.otaliastudios.cameraview.CameraException
import com.otaliastudios.cameraview.PictureResult
import com.otaliastudios.cameraview.VideoResult
import com.otaliastudios.cameraview.controls.Facing
import com.otaliastudios.cameraview.controls.Mode
import com.otaliastudios.cameraview.markers.DefaultAutoFocusMarker
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : ProtectedBaseActivity() {
    private lateinit var binding: ActivityMainBinding;

    private var activeRecording: EncryptedVideoRecording? = null

    // When set, the next PictureResult is a preview snapshot used as the thumbnail of
    // the video recording with this file name, not a photo to store.
    private var pendingVideoThumbnailName: String? = null

    // True from the moment a photo capture is requested until its PictureResult (or an
    // error) arrives. Switching the camera to VIDEO mode during this window makes the
    // CameraView engine run the still-pending picture action in VIDEO mode and throw
    // "Can't take hq pictures while in VIDEO mode" on its worker thread, crashing the
    // app — so toggleMode() refuses to switch while this is set.
    private var isCapturingPhoto = false

    private lateinit var prefs: AppPrefs

    // Photo saves run one after another on this executor. With rapid capture on, the
    // shutter stays live while earlier photos are still being encrypted; each pending
    // save holds a full-resolution JPEG in memory, so the number in flight is capped.
    private val saveExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var pendingSaves = 0

    // True for the capture currently in flight: decided at the shutter so a settings
    // change mid-capture cannot unbalance the control state.
    private var rapidCaptureActive = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        supportActionBar?.hide();

        // Edge-to-edge is enforced at targetSdk 36; keep the top/bottom controls out of
        // the status- and navigation-bar areas. The preview sits between the button rows,
        // so padding the black root just frames it and shifts the controls into safety.
        binding.root.applySystemBarInsetsAsPadding()

        binding.camera.setAutoFocusMarker(DefaultAutoFocusMarker())

        // Check camera permissions if all permission granted
        // start camera else ask for the permission
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        binding.camera.setLifecycleOwner(this);

        // MPEG-2 TS is written sequentially, so full video recordings can target the
        // encryption pipe (see EncryptedVideoRecording). Requires the vendored
        // CameraView patch.
        binding.camera.setVideoOutputFormat(MediaRecorder.OutputFormat.MPEG_2_TS)

        val app = application as AESCameraApplication

        app.keyLiveData.observe(this) {
            binding.lockButton.visibility = if (it == null) {
                View.INVISIBLE
            } else {
                View.VISIBLE
            }
        }

        binding.camera.addCameraListener(object : com.otaliastudios.cameraview.CameraListener() {
            override fun onPictureShutter() {
                super.onPictureShutter()
                pictureShutter()
            }

            override fun onPictureTaken(pictureResult: PictureResult) {
                super.onPictureTaken(pictureResult)
                pictureTaken(pictureResult)
            }

            override fun onCameraError(exception: CameraException) {
                super.onCameraError(exception)
                cameraError(exception)
            }

            override fun onVideoRecordingStart() {
                super.onVideoRecordingStart()
                videoRecordingStart()
            }

            override fun onVideoTaken(result: VideoResult) {
                super.onVideoTaken(result)
                videoTaken(result)
            }
        })

        binding.cameraCaptureButton.setOnClickListener {
            if (binding.camera.mode == Mode.PICTURE) {
                takePhoto()
            } else {
                startStopRecording()
            }
        }
        binding.cameraModeToggleButton.setOnClickListener {
            toggleMode()
        }
        binding.cameraFlipButton.setOnClickListener {
            flipCamera()
        }
        binding.openFilesListButton.setOnClickListener {
            openFilesList()
        }
        binding.lockButton.setOnClickListener {
            lock()
        }
        binding.flashButton.setOnClickListener {
            toggleFlash()
        }
    }

    private fun disableControls() {
        binding.cameraCaptureButton.isEnabled = false
        binding.cameraModeToggleButton.isEnabled = false
        binding.cameraFlipButton.isEnabled = false
        binding.openFilesListButton.isEnabled = false
        binding.flashButton.isEnabled = false
    }

    private fun enableControls() {
        binding.cameraCaptureButton.isEnabled = true
        binding.cameraModeToggleButton.isEnabled = true
        binding.cameraFlipButton.isEnabled = true
        binding.openFilesListButton.isEnabled = true
        binding.flashButton.isEnabled = true
    }

    private fun lock() {
        // Finish writing the encrypted file before dropping the key. The encryption
        // pipeline keeps working without the key (its cipher is already initialized).
        if (binding.camera.isTakingVideo) {
            binding.camera.stopVideo()
        }

        val app = application as AESCameraApplication
        app.key = null
        Toast.makeText(this, "Locked", Toast.LENGTH_SHORT).show()
    }

    private fun toggleMode() {
        if (binding.camera.isTakingVideo || isCapturingPhoto) {
            return
        }

        binding.camera.mode = if (binding.camera.mode == Mode.PICTURE) {
            Mode.VIDEO
        } else {
            Mode.PICTURE
        }

        // Have the next recording's key ready before the user taps record.
        (application as AESCameraApplication).prewarmKeys()

        if (binding.camera.mode == Mode.PICTURE) {
            binding.cameraCaptureButton.setIcon(getString(R.string.fa_camera))
            binding.cameraModeToggleButton.setIcon(getString(R.string.fa_camera_mode_video))
        } else {
            binding.cameraCaptureButton.setIcon(getString(R.string.fa_camera_start_recording))
            binding.cameraModeToggleButton.setIcon(getString(R.string.fa_camera_mode_photo))
        }
    }

    private  fun openFilesList() {
        val intent = Intent(this, FilesListActivity::class.java)
        startActivity(intent)
    }

    private fun toggleFlash() {
        binding.camera.flash = if (binding.camera.flash == com.otaliastudios.cameraview.controls.Flash.OFF) {
            com.otaliastudios.cameraview.controls.Flash.ON
        } else {
            com.otaliastudios.cameraview.controls.Flash.OFF
        }

        if (binding.camera.flash == com.otaliastudios.cameraview.controls.Flash.ON) {
            binding.flashButton.setFontAwesomeStyle("solid")
        } else {
            binding.flashButton.setFontAwesomeStyle("regular")
        }
    }

    private fun flipCamera() {
        binding.camera.facing = if (binding.camera.facing == Facing.FRONT) {
            Facing.BACK
        } else {
            Facing.FRONT
        }

        if (binding.camera.facing == Facing.FRONT) {
            binding.flashButton.visibility = View.INVISIBLE
        } else {
            binding.flashButton.visibility = View.VISIBLE
        }
    }

    private fun takePhoto() {
        // Guard before the async capture starts: no mode switch until it resolves.
        isCapturingPhoto = true
        rapidCaptureActive = prefs.rapidCapture
        binding.camera.takePicture()
    }

    private fun startStopRecording() {
        if (binding.camera.isTakingVideo) {
            binding.camera.stopVideo()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val app = application as AESCameraApplication
        val key = app.key
        if (key == null) {
            Toast.makeText(this, "Key not found", Toast.LENGTH_SHORT).show()
            return
        }

        // Setting up the recording derives an encryption key (PBKDF2, ~100 ms) and may
        // create the vault on first use, so it runs off the main thread; controls stay
        // disabled until the recorder starts, which also prevents a double-tap from
        // starting two recordings.
        disableControls()
        Thread {
            // No plaintext fallback: if the encryption pipeline can't be set up, we
            // don't record at all. mkdirs: the vault dir lives on shared storage and
            // can vanish underneath us; recreate it rather than failing with ENOENT.
            val recording = try {
                val outputDirectory = app.ensureVault()
                    ?: throw IllegalStateException("Locked while starting the recording")
                outputDirectory.mkdirs()
                val outputFile = File(outputDirectory, CommonUtils.getVideoFileName())
                EncryptedVideoRecording(outputFile, key, app.takeVideoSalt())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set up encrypted recording: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.video_recording_setup_failed_message),
                        Toast.LENGTH_LONG
                    ).show()
                    enableControls()
                }
                return@Thread
            }

            runOnUiThread {
                if (isFinishing || isDestroyed || app.key == null) {
                    // Left the screen or locked while setting up: don't start recording.
                    Thread { recording.abort() }.start()
                    enableControls()
                    return@runOnUiThread
                }
                activeRecording = recording
                binding.camera.takeVideo(recording.writeFileDescriptor)
                // Re-enable the shutter (needed to stop recording) first, then let the
                // recording UI state re-lock the controls that must stay disabled while
                // recording (mode toggle, flip, files, flash).
                enableControls()
                setRecordingUi(true)
            }
        }.start()
    }

    private fun videoRecordingStart() {
        val recording = activeRecording ?: return

        // MediaRecorder has dup'ed the pipe fd by now; ours must be closed so the drain
        // thread sees EOF when the recorder stops.
        recording.closeWriteSide()

        // The recorder is actually running from this callback on, so this is where the
        // duration counter starts.
        showRecordingIndicator()

        // Grab a preview frame as the (encrypted) thumbnail for this video.
        pendingVideoThumbnailName = recording.outputFile.name
        binding.camera.takePictureSnapshot()
    }

    private fun showRecordingIndicator() {
        binding.recordingDuration.base = SystemClock.elapsedRealtime()
        binding.recordingDuration.start()
        binding.recordingIndicator.visibility = View.VISIBLE
        binding.recordingDot.startAnimation(AlphaAnimation(1f, 0.2f).apply {
            duration = 600
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        })
    }

    private fun hideRecordingIndicator() {
        binding.recordingDuration.stop()
        binding.recordingDot.clearAnimation()
        binding.recordingIndicator.visibility = View.GONE
    }

    private fun videoTaken(result: VideoResult) {
        val recording = activeRecording
        activeRecording = null
        if (recording == null) {
            return
        }

        Thread {
            (application as AESCameraApplication).prewarmKeys()
            try {
                recording.finish(result.rotation)
                runOnUiThread {
                    setRecordingUi(false)
                    Toast.makeText(
                        this,
                        getString(R.string.video_saved_message, recording.outputFile.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to finalize encrypted recording: ${e.message}", e)
                recording.abort()
                runOnUiThread {
                    setRecordingUi(false)
                    Toast.makeText(
                        this,
                        getString(R.string.video_recording_failed_message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun setRecordingUi(recording: Boolean) {
        if (!recording) {
            hideRecordingIndicator()
        }
        binding.cameraCaptureButton.setIcon(
            getString(
                if (recording) R.string.fa_camera_stop_recording
                else R.string.fa_camera_start_recording
            )
        )
        binding.cameraModeToggleButton.isEnabled = !recording
        binding.cameraFlipButton.isEnabled = !recording
        binding.openFilesListButton.isEnabled = !recording
        binding.flashButton.isEnabled = !recording
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun cameraError(exception: CameraException) {
        val msg = "Capture failed: ${exception.message}"
        Toast.makeText(baseContext, msg, Toast.LENGTH_LONG).show()
        Log.e(TAG, msg, exception)

        val recording = activeRecording
        if (recording != null) {
            activeRecording = null
            Thread { recording.abort() }.start()
            setRecordingUi(false)
        }

        // A failed capture also ends the photo-capture window.
        isCapturingPhoto = false
        if (rapidCaptureActive && pendingSaves > 0) {
            pendingSaves--
        }
        enableControls()
    }

    private fun pictureShutter() {
        // Video thumbnail snapshots must not lock the recording controls or flash the
        // screen — they're taken silently while a recording starts.
        if (pendingVideoThumbnailName != null) {
            return
        }
        flashShutter()
        if (rapidCaptureActive) {
            // Only the mode toggle must wait for the capture; the shutter stays live
            // until too many saves are queued.
            pendingSaves++
            binding.cameraModeToggleButton.isEnabled = false
            binding.cameraCaptureButton.isEnabled = pendingSaves < MAX_PENDING_SAVES
        } else {
            disableControls()
        }
    }

    /** Called when a photo save finishes (either way) to release what the shutter locked. */
    private fun photoSaveFinished(rapid: Boolean) {
        if (rapid) {
            if (pendingSaves > 0) {
                pendingSaves--
            }
            binding.cameraCaptureButton.isEnabled = pendingSaves < MAX_PENDING_SAVES
            if (!isCapturingPhoto) {
                binding.cameraModeToggleButton.isEnabled = true
            }
        } else {
            enableControls()
        }
    }

    // A quick white flash confirms the capture; the encrypted save finishes
    // asynchronously and only then shows a Toast, so this is the immediate feedback.
    private fun flashShutter() {
        binding.shutterFlash.apply {
            alpha = 1f
            visibility = View.VISIBLE
            animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction { visibility = View.GONE }
                .start()
        }
    }

    private fun pictureTaken(pictureResult: PictureResult) {
        val app = application as AESCameraApplication

        // Preview snapshot taken at recording start: store it as the video's thumbnail.
        // Thumbnail creation encrypts (PBKDF2 + AES), so keep it off the main thread —
        // this runs while the recording is starting.
        val videoThumbnailName = pendingVideoThumbnailName
        if (videoThumbnailName != null) {
            pendingVideoThumbnailName = null
            Thread {
                try {
                    ThumbnailUtils.createImageThumbnail(
                        app, videoThumbnailName, pictureResult.data
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create video thumbnail: ${e.message}", e)
                }
            }.start()
            return
        }

        // A real photo (not a video-thumbnail snapshot) resolved: the capture window is
        // over, so mode switching is safe again.
        isCapturingPhoto = false
        val rapid = rapidCaptureActive
        if (rapid) {
            binding.cameraModeToggleButton.isEnabled = true
        }

        val key = app.key
        if (key == null) {
            Toast.makeText(this@MainActivity, "Key not found", Toast.LENGTH_SHORT).show()
            photoSaveFinished(rapid)
            return
        }

        val fileName = CommonUtils.getImageFileName()
        // The next file key is normally derived already (prewarmKeys); if not, the
        // encrypt below derives it on the spot.
        val salt = app.takePhotoSalt() ?: EncryptionUtils.newSalt()

        // The save + thumbnail work runs off the main thread, in capture order. The
        // controls locked at shutter (pictureShutter) come back in photoSaveFinished.
        saveExecutor.execute {
            // The vault dir lives on shared storage and can vanish underneath us (file
            // managers, MediaProvider cleanup); recreate it and surface write failures
            // instead of crashing. ensureVault creates the vault on the first save.
            try {
                val outputDirectory = app.ensureVault()
                    ?: throw IllegalStateException("Locked while saving the photo")
                outputDirectory.mkdirs()
                val encryptedFile = File(outputDirectory, fileName)
                EncryptionUtils.encryptImage(pictureResult.data, key, salt = salt).let {
                    encryptedFile.writeBytes(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save encrypted photo: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.photo_save_failed_message),
                        Toast.LENGTH_LONG
                    ).show()
                    photoSaveFinished(rapid)
                }
                app.prewarmKeys()
                return@execute
            }

            try {
                ThumbnailUtils.createImageThumbnail(app, fileName, pictureResult.data)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create photo thumbnail: ${e.message}", e)
            }

            // Derive the key for the photo after this one while the camera is idle.
            app.prewarmKeys()

            runOnUiThread {
                // In rapid mode the shutter flash is the feedback; a Toast per shot
                // would pile up over the viewfinder.
                if (!rapid) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.photo_saved_message, fileName),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                photoSaveFinished(rapid)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Queued saves keep running to completion; only stop accepting new ones.
        saveExecutor.shutdown()
    }

    // checks the camera permission
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            // If all permissions granted , then start Camera
            if (!allPermissionsGranted()) {
                // If permissions are not granted,
                // present a toast to notify the user that
                // the permissions were not granted.
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT)
                    .show()
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "AESCamera"
        private const val REQUEST_CODE_PERMISSIONS = 20

        /** Rapid capture: photos that may be waiting to be encrypted before the shutter pauses. */
        private const val MAX_PENDING_SAVES = 3
        private val REQUIRED_PERMISSIONS =
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}