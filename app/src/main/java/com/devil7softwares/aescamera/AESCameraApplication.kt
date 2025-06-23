package com.devil7softwares.aescamera

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import com.devil7softwares.aescamera.utils.CommonUtils
import java.io.File

class AESCameraApplication : Application() {
    var keyLiveData: MutableLiveData<String?> = MutableLiveData()
    var key: String? = null
        set(value) {
            field = value

            if (value == null) {
                this.keyDigest = null
                keyLiveData.value = value
                return
            }

            this.keyDigest = CommonUtils.getKeyDigest(value)

            keyLiveData.value = value
        }
    var keyDigest: String? = null
        private set(value) {
            field = value

            this.outputDirectory = CommonUtils.getOutputDirectory(value, this)
        }
    var outputDirectory: File? = null
        private set(value) {
            field = value

            if (value != null) {
                this.thumbnailDirectory = File(value, ".thumbs").apply { mkdirs() }
            }
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
