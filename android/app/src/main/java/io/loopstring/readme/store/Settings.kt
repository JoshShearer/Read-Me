package io.loopstring.readme.store

import android.content.Context

/** R-M07: 0.5x to 4.0x in 0.1x steps, default 2.0x. Applied once, by setSpeechRate. */
object Rate {
  const val MIN = 0.5f
  const val MAX = 4.0f
  const val DEFAULT = 2.0f

  fun clamp(r: Float): Float {
    if (r.isNaN()) return DEFAULT
    return (Math.round(r.coerceIn(MIN, MAX) * 10) / 10f)
  }
}

/** App settings in app-private SharedPreferences (allowBackup is false, AGENTS.md 3). */
class Settings(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

  var rate: Float
    get() = Rate.clamp(prefs.getFloat(KEY_RATE, Rate.DEFAULT))
    set(value) {
      prefs.edit().putFloat(KEY_RATE, Rate.clamp(value)).apply()
    }

  private companion object {
    const val KEY_RATE = "rate"
  }
}
