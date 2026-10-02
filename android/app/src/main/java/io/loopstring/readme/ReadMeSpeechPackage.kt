package io.loopstring.readme

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class ReadMeSpeechPackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
    if (name == ReadMeSpeechModule.NAME) ReadMeSpeechModule(reactContext) else null

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
      ReadMeSpeechModule.NAME to ReactModuleInfo(
        ReadMeSpeechModule.NAME,
        ReadMeSpeechModule.NAME,
        false, // canOverrideExistingModule
        false, // needsEagerInit
        false, // isCxxModule
        true, // isTurboModule
      ),
    )
  }
}
