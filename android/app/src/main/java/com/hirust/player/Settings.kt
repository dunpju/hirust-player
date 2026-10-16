package com.hirust.player

import android.content.Context

/**
 * 应用设置(SharedPreferences)。
 *
 * - `bufferMs`:AudioTrack 流缓冲毫秒(下一曲目生效);
 * - `minDurationSec`:曲库扫描时忽略短于该秒数的音频。
 */
object Settings {

    private const val PREFS = "settings"
    private const val KEY_BUFFER_MS = "buffer_ms"
    private const val KEY_MIN_DURATION_SEC = "min_duration_sec"

    const val DEFAULT_BUFFER_MS = 500
    const val DEFAULT_MIN_DURATION_SEC = 10

    fun bufferMs(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_BUFFER_MS, DEFAULT_BUFFER_MS)
            .coerceIn(200, 2000)

    fun setBufferMs(context: Context, ms: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BUFFER_MS, ms.coerceIn(200, 2000))
            .apply()
    }

    fun minDurationSec(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MIN_DURATION_SEC, DEFAULT_MIN_DURATION_SEC)
            .coerceIn(0, 300)

    fun setMinDurationSec(context: Context, sec: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MIN_DURATION_SEC, sec.coerceIn(0, 300))
            .apply()
    }
}
