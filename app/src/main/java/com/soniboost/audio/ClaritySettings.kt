package com.soniboost.audio

data class ClaritySettings(
    val enabled: Boolean = false,
    val strength: Int = DEFAULT_STRENGTH,
    val thresholdDb: Int = DEFAULT_THRESHOLD,
) {
    fun cutoffs(sampleRate: Int): FloatArray {
        val limit = if (sampleRate > 0) sampleRate * 0.45f else CUTOFFS.last()
        val scale = if (CUTOFFS.last() > limit) limit / CUTOFFS.last() else 1f
        return FloatArray(CUTOFFS.size) { index -> CUTOFFS[index] * scale }
    }

    companion object {
        const val MIN_STRENGTH = 0
        const val MAX_STRENGTH = 10
        const val DEFAULT_STRENGTH = 5
        const val MIN_THRESHOLD = -50
        const val MAX_THRESHOLD = -20
        const val DEFAULT_THRESHOLD = -35
        const val BAND_COUNT = 5
        val CUTOFFS = floatArrayOf(250f, 1_000f, 4_000f, 8_000f, 16_000f)
        val WEIGHTS = floatArrayOf(0f, 0.35f, 1f, 0.7f, 0.15f)
    }
}

object OutputLevel {
    const val MIN_DB = -24
    const val MAX_DB = -3
    const val DEFAULT_DB = -6
}
