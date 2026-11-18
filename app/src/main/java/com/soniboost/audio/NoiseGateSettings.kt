package com.soniboost.audio

data class NoiseGateSettings(
    val enabled: Boolean = false,
    val thresholdDb: Int = DEFAULT_THRESHOLD,
    val depthDb: Int = DEFAULT_DEPTH,
    val attackMs: Int = DEFAULT_ATTACK,
    val holdMs: Int = DEFAULT_HOLD,
    val releaseMs: Int = DEFAULT_RELEASE,
) {
    companion object {
        const val MIN_THRESHOLD = -60
        const val MAX_THRESHOLD = -24
        const val DEFAULT_THRESHOLD = -42
        const val MIN_DEPTH = 6
        const val MAX_DEPTH = 36
        const val DEFAULT_DEPTH = 18
        const val MIN_ATTACK = 1
        const val MAX_ATTACK = 50
        const val DEFAULT_ATTACK = 2
        const val MIN_HOLD = 0
        const val MAX_HOLD = 400
        const val HOLD_STEP = 10
        const val DEFAULT_HOLD = 120
        const val MIN_RELEASE = 10
        const val MAX_RELEASE = 600
        const val RELEASE_STEP = 10
        const val DEFAULT_RELEASE = 180
    }
}
