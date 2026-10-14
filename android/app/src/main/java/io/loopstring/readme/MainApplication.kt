package io.loopstring.readme

import android.app.Application
import android.util.Log
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import io.loopstring.readme.fetch.Recovery
import io.loopstring.readme.playback.PlaybackFiles

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          // Packages that cannot be autolinked yet can be added manually here, for example:
          add(ReadMeSpeechPackage())
        },
    )
  }

  override fun onCreate() {
    super.onCreate()
    // Taken before anything can insert: a share that starts this process is not recovered.
    val startedAt = System.currentTimeMillis()
    loadReactNative(this)
    // REA-53: once per process, before any PlaybackService exists, so no queue can own these.
    // Audio left by a process that died mid-play is otherwise kept until the next service start.
    Thread {
      try {
        val n = PlaybackFiles.of(this).sweep()
        if (n > 0) Log.i("ReadMe", "playback swept at start files=$n")
      } catch (t: Throwable) {
        Log.w("ReadMe", "playback sweep failed: ${t.javaClass.simpleName}")
      }
    }.start()
    // R-M02 recovery at every process start, off the main thread (WorkManager queries block).
    Thread {
      try {
        Recovery.run(this, startedAt)
      } catch (t: Throwable) {
        // AGENTS.md 1: the exception's class only; its message could carry a URL.
        Log.w("ReadMe", "recovery failed: ${t.javaClass.simpleName}")
      }
    }.start()
  }
}
