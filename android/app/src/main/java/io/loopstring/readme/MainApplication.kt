package io.loopstring.readme

import android.app.Application
import android.util.Log
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import io.loopstring.readme.fetch.Recovery

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
    loadReactNative(this)
    // R-M02 recovery at every process start, off the main thread (WorkManager queries block).
    Thread {
      try {
        Recovery.run(this)
      } catch (t: Throwable) {
        // AGENTS.md 1: the exception's class only; its message could carry a URL.
        Log.w("ReadMe", "recovery failed: ${t.javaClass.simpleName}")
      }
    }.start()
  }
}
