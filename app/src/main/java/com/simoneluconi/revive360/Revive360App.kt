package com.simoneluconi.revive360

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.simoneluconi.revive360.link.CameraController
import com.simoneluconi.revive360.media.SavedFiles
import com.simoneluconi.revive360.media.ThumbCache
import com.simoneluconi.revive360.settings.AppSettings
import com.simoneluconi.revive360.settings.CameraStore

class Revive360App : Application() {
    lateinit var settings: AppSettings
        private set
    lateinit var store: CameraStore
        private set
    lateinit var controller: CameraController
        private set

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        EventLog.log("App started (${BuildConfig.VERSION_NAME})")
        settings = AppSettings(this)
        store = CameraStore(this)
        controller = CameraController(this, store, settings, ThumbCache(this), SavedFiles(this))
        // On the camera's Wi-Fi while the app is on screen, off it shortly after (battery, and the
        // phone's own Wi-Fi comes back)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = controller.onForeground()
            override fun onStop(owner: LifecycleOwner) = controller.onBackground()
        })
    }
}
