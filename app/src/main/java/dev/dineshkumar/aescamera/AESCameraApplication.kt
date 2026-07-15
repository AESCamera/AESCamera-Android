package dev.dineshkumar.aescamera

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import dev.dineshkumar.aescamera.utils.EncryptionUtils
import dev.dineshkumar.aescamera.utils.VaultUtils
import java.io.File

class AESCameraApplication : Application() {
    var keyLiveData: MutableLiveData<String?> = MutableLiveData()
    var key: String? = null
        set(value) {
            field = value

            if (value == null) {
                EncryptionUtils.clearKeyCache()
                outputDirectory = null
                keyLiveData.value = value
                return
            }

            // Locating the vault test-decrypts each vault's verifier (a PBKDF2
            // derivation apiece), so unlocking costs ~100 ms per existing vault.
            outputDirectory = VaultUtils.openVault(value, this)

            keyLiveData.value = value
        }
    var outputDirectory: File? = null
        private set(value) {
            field = value

            thumbnailDirectory = value?.let { File(it, ".thumbs").apply { mkdirs() } }
        }
    var thumbnailDirectory: File? = null
        private set

    private var activeActivities = 0
    private val handler = Handler(Looper.getMainLooper())
    private val clearKeyDelay = 5000L

    fun onActivityStarted() {
        activeActivities++
        handler.removeCallbacksAndMessages(null)
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                activeActivities++
                handler.removeCallbacks(clearKeyRunnable)
            }

            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                onActivityStopped()
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun onActivityStopped() {
        activeActivities--
        if (activeActivities == 0) {
            handler.postDelayed(clearKeyRunnable, clearKeyDelay)
        }
    }

    private val clearKeyRunnable = Runnable {
        key = null
    }
}
