package com.devil7softwares.aescamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.devil7softwares.aescamera.databinding.ActivityMainBinding
import com.devil7softwares.aescamera.list.FilesListActivity
import com.devil7softwares.aescamera.utils.CommonUtils
import com.devil7softwares.aescamera.utils.EncryptionUtils
import com.devil7softwares.aescamera.utils.ThumbnailUtils
import com.devil7softwares.aescamera.video.EncryptedVideoRecording
import com.otaliastudios.cameraview.CameraException
import com.otaliastudios.cameraview.PictureResult
import com.otaliastudios.cameraview.VideoResult
import com.otaliastudios.cameraview.controls.Facing
import com.otaliastudios.cameraview.controls.Mode
import com.otaliastudios.cameraview.markers.DefaultAutoFocusMarker
import java.io.File


class MainActivity : ProtectedBaseActivity() {
    private lateinit var binding: ActivityMainBinding;

    private var activeRecording: EncryptedVideoRecording? = null

    // When set, the next PictureResult is a preview snapshot used as the thumbnail of
    // the video recording with this file name, not a photo to store.
    private var pendingVideoThumbnailName: String? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.hide();

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
        binding.cameraFlipButton.isEnabled = false
        binding.openFilesListButton.isEnabled = false
        binding.flashButton.isEnabled = false
    }

    private fun enableControls() {
        binding.cameraCaptureButton.isEnabled = true
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
        if (binding.camera.isTakingVideo) {
            return
        }

        binding.camera.mode = if (binding.camera.mode == Mode.PICTURE) {
            Mode.VIDEO
        } else {
            Mode.PICTURE
        }

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
        val outputDirectory = app.outputDirectory
        if (key == null) {
            Toast.makeText(this, "Key not found", Toast.LENGTH_SHORT).show()
            return
        }
        if (outputDirectory == null) {
            Toast.makeText(this, "No output directory set", Toast.LENGTH_SHORT).show()
            return
        }

        val outputFile = File(outputDirectory, CommonUtils.getVideoFileName())

        // No plaintext fallback: if the encryption pipeline can't be set up, we don't
        // record at all.
        val recording = try {
            EncryptedVideoRecording(outputFile, key)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up encrypted recording: ${e.message}", e)
            Toast.makeText(
                this,
                getString(R.string.video_recording_setup_failed_message),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        activeRecording = recording
        binding.camera.takeVideo(recording.writeFileDescriptor)
        setRecordingUi(true)
    }

    private fun videoRecordingStart() {
        val recording = activeRecording ?: return

        // MediaRecorder has dup'ed the pipe fd by now; ours must be closed so the drain
        // thread sees EOF when the recorder stops.
        recording.closeWriteSide()

        // Grab a preview frame as the (encrypted) thumbnail for this video.
        pendingVideoThumbnailName = recording.outputFile.name
        binding.camera.takePictureSnapshot()
    }

    private fun videoTaken(result: VideoResult) {
        val recording = activeRecording
        activeRecording = null
        if (recording == null) {
            return
        }

        Thread {
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

        enableControls()
    }

    private fun pictureShutter() {
        // Video thumbnail snapshots must not lock the recording controls.
        if (pendingVideoThumbnailName == null) {
            disableControls()
        }
    }

    private fun pictureTaken(pictureResult: PictureResult) {
        val app = application as AESCameraApplication

        // Preview snapshot taken at recording start: store it as the video's thumbnail.
        val videoThumbnailName = pendingVideoThumbnailName
        if (videoThumbnailName != null) {
            pendingVideoThumbnailName = null
            try {
                ThumbnailUtils.createImageThumbnail(app, videoThumbnailName, pictureResult.data)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create video thumbnail: ${e.message}", e)
            }
            return
        }

        val key = app.key
        val outputDirectory = app.outputDirectory
        val thumbnailDirectory = app.thumbnailDirectory
        if (key == null) {
            Toast.makeText(this@MainActivity, "Key not found", Toast.LENGTH_SHORT).show()
            return
        }

        val fileName = CommonUtils.getImageFileName()

        // Create main encrypted file
        val encryptedFile = File(outputDirectory, fileName)
        EncryptionUtils.encrypt(pictureResult.data, key).let {
            encryptedFile.writeBytes(it)
        }

        if (thumbnailDirectory !== null) {
            ThumbnailUtils.createImageThumbnail(app, fileName, pictureResult.data)
        }

        enableControls()
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
        private val REQUIRED_PERMISSIONS =
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}