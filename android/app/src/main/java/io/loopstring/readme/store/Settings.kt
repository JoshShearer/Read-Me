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

/** R-M12: the pairing token, 128 bits from SecureRandom as 32 hex chars. Never logged (AGENTS.md 4). */
object BridgeToken {
  fun generate(rng: java.security.SecureRandom = java.security.SecureRandom()): String {
    val b = ByteArray(16)
    rng.nextBytes(b)
    return b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
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

  /** R-M01 Settings "voice": a voice name; null means the engine's default (R-M06 rules apply). */
  var voice: String?
    get() = prefs.getString(KEY_VOICE, null)
    set(value) {
      prefs.edit().apply { if (value == null) remove(KEY_VOICE) else putString(KEY_VOICE, value) }.apply()
    }

  /** R-M12: the bridge runs only while this is true. */
  var bridgeEnabled: Boolean
    get() = prefs.getBoolean(KEY_BRIDGE, false)
    set(value) {
      prefs.edit().putBoolean(KEY_BRIDGE, value).apply()
    }

  /** R-M12: 8787 unless the user chose another port in 1024..65535. */
  var bridgePort: Int
    get() = prefs.getInt(KEY_BRIDGE_PORT, BRIDGE_PORT_DEFAULT)
    set(value) {
      require(value in BRIDGE_PORT_MIN..BRIDGE_PORT_MAX) { "port out of range" }
      prefs.edit().putInt(KEY_BRIDGE_PORT, value).apply()
    }

  /** Generated on first use and kept until regenerated (R-M12). commit(): the bridge may read it at once. */
  fun bridgeToken(): String = synchronized(LOCK) {
    prefs.getString(KEY_BRIDGE_TOKEN, null) ?: regenerateBridgeToken()
  }

  fun regenerateBridgeToken(): String = synchronized(LOCK) {
    BridgeToken.generate().also { prefs.edit().putString(KEY_BRIDGE_TOKEN, it).commit() }
  }

  companion object {
    const val BRIDGE_PORT_DEFAULT = 8787
    const val BRIDGE_PORT_MIN = 1024
    const val BRIDGE_PORT_MAX = 65535
    private const val KEY_RATE = "rate"
    private const val KEY_VOICE = "voice"
    private const val KEY_BRIDGE = "bridge"
    private const val KEY_BRIDGE_PORT = "bridgePort"
    private const val KEY_BRIDGE_TOKEN = "bridgeToken"
    private val LOCK = Any()
  }
}
