package com.soniboost.audio

import android.content.Context

enum class LoweringMethod {
    Compression,
    Transposition,
}

data class LoweringSettings(
    val enabled: Boolean = false,
    val method: LoweringMethod = LoweringMethod.Compression,
    val cutoffHz: Int = 2_500,
    val ratio: Float = 2f,
    val shiftHz: Int = 1_000,
    val mix: Float = 1f,
) {
    companion object {
        fun moderate(): LoweringSettings = LoweringSettings(
            enabled = true,
            method = LoweringMethod.Compression,
            cutoffHz = 2_500,
            ratio = 2f,
            mix = 1f,
        )

        fun strong(): LoweringSettings = LoweringSettings(
            enabled = true,
            method = LoweringMethod.Compression,
            cutoffHz = 1_800,
            ratio = 3f,
            mix = 1f,
        )
    }
}

class LoweringStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(): LoweringSettings {
        val method = runCatching {
            LoweringMethod.valueOf(preferences.getString(METHOD, LoweringMethod.Compression.name)!!)
        }.getOrDefault(LoweringMethod.Compression)
        return LoweringSettings(
            enabled = preferences.getBoolean(ENABLED, false),
            method = method,
            cutoffHz = preferences.getInt(CUTOFF, 2_500).coerceIn(MIN_CUTOFF, MAX_CUTOFF),
            ratio = preferences.getFloat(RATIO, 2f).coerceIn(MIN_RATIO, MAX_RATIO),
            shiftHz = preferences.getInt(SHIFT, 1_000).coerceIn(MIN_SHIFT, MAX_SHIFT),
            mix = preferences.getFloat(MIX, 1f).coerceIn(0f, 1f),
        )
    }

    fun set(settings: LoweringSettings) {
        preferences.edit()
            .putBoolean(ENABLED, settings.enabled)
            .putString(METHOD, settings.method.name)
            .putInt(CUTOFF, settings.cutoffHz.coerceIn(MIN_CUTOFF, MAX_CUTOFF))
            .putFloat(RATIO, settings.ratio.coerceIn(MIN_RATIO, MAX_RATIO))
            .putInt(SHIFT, settings.shiftHz.coerceIn(MIN_SHIFT, MAX_SHIFT))
            .putFloat(MIX, settings.mix.coerceIn(0f, 1f))
            .apply()
    }

    companion object {
        const val MIN_CUTOFF = 1_200
        const val MAX_CUTOFF = 6_000
        const val MIN_RATIO = 1.5f
        const val MAX_RATIO = 3f
        const val MIN_SHIFT = 500
        const val MAX_SHIFT = 2_500
        private const val FILE = "soniboost"
        private const val ENABLED = "lowering_enabled"
        private const val METHOD = "lowering_method"
        private const val CUTOFF = "lowering_cutoff"
        private const val RATIO = "lowering_ratio"
        private const val SHIFT = "lowering_shift"
        private const val MIX = "lowering_mix"
    }
}
