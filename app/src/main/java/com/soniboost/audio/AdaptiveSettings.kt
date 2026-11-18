package com.soniboost.audio

data class AdaptiveSettings(
    val enabled: Boolean = false,
    val strengthDb: Int = DEFAULT_STRENGTH,
    val caution: Int = DEFAULT_CAUTION,
) {
    companion object {
        const val MIN_STRENGTH = 0
        const val MAX_STRENGTH = 10
        const val MIN_CAUTION = 0
        const val MAX_CAUTION = 100
        const val DEFAULT_STRENGTH = 6
        const val DEFAULT_CAUTION = 50

        fun moderate(): AdaptiveSettings = AdaptiveSettings(
            enabled = true,
            strengthDb = DEFAULT_STRENGTH,
            caution = DEFAULT_CAUTION,
        )
    }
}
