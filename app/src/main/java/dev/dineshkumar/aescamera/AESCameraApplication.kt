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
    // Setting a non-null key runs the expensive vault unlock (a PBKDF2 verifier check
    // per vault scanned); callers must do it off the main thread — hence postValue for
    // the LiveData notification. Setting null (lock) is cheap.
    var key: String? = null
        set(value) {
            field = value

            if (value == null) {
                EncryptionUtils.clearKeyCache()
                outputDirectory = null
                keyLiveData.postValue(null)
                return
            }

            // Only an existing vault is opened here. A key without a vault stays
            // directory-less until the first save (see ensureVault), so a mistyped
            // password leaves nothing behind.
            outputDirectory = VaultUtils.findVault(value, this)

            keyLiveData.postValue(value)
        }

    /**
     * The vault directory of the current key, or null while locked or while the key has
     * no vault yet. Writers call [ensureVault] instead; readers (gallery) treat null as
     * an empty vault.
     */
    var outputDirectory: File? = null
        private set(value) {
            field = value

            thumbnailDirectory = value?.let { File(it, ".thumbs").apply { mkdirs() } }
        }
    var thumbnailDirectory: File? = null
        private set

    /**
     * Returns the vault directory for the current key, creating it on first use. Runs a
     * PBKDF2 derivation when it creates, so call off the main thread. Null when locked.
     */
    @Synchronized
    fun ensureVault(): File? {
        val currentKey = key ?: return null
        outputDirectory?.let { return it }
        val created = VaultUtils.createVault(currentKey, this)
        // The key may have been cleared while the vault was being created.
        if (key != currentKey) {
            return null
        }
        outputDirectory = created
        return created
    }

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
