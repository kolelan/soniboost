package com.soniboost.audio

data class SoundProfile(
    val id: String,
    val name: String,
    val curve: EqCurve,
    val lowering: LoweringSettings,
    val adaptive: AdaptiveSettings = AdaptiveSettings(),
    val builtin: Boolean,
    val description: String = "",
)

object SoundProfiles {
    const val CLARITY = "speech_clarity"
    const val ADAPTIVE = "speech_adaptive"
    const val EVERYDAY = "scene_everyday"
    const val NOISE = "scene_noise"
    const val QUIET = "scene_quiet"
    const val MUSIC = "scene_music"
    const val TV = "scene_tv"
    const val PHONE = "scene_phone"
    const val OUTDOOR = "scene_outdoor"
    const val CAR = "scene_car"
    const val FL_MODERATE = "fl_moderate"
    const val FL_STRONG = "fl_strong"

    fun builtins(): List<SoundProfile> = listOf(
        profile(EqCurves.SPEECH, EqCurves.mild(), LoweringSettings()),
        profile(EqCurves.STRONG, EqCurves.strong(), LoweringSettings()),
        profile(CLARITY, clarityCurve(), LoweringSettings()),
        profile(ADAPTIVE, EqCurves.mild(), LoweringSettings(), adaptive = AdaptiveSettings.moderate()),
        profile(EVERYDAY, bands(0, 0, -1, -1, 1, 3, 3, 1, 0, 0), LoweringSettings()),
        profile(NOISE, bands(0, -1, -4, -4, 1, 3, 6, 2, 0, 0), LoweringSettings(), peak(3_000, 3)),
        profile(QUIET, bands(0, 0, 0, 0, 0, 2, 3, 1, 0, 0), LoweringSettings(), peak(3_000, 2, 1f)),
        profile(MUSIC, bands(1, 1, 1, 0, 0, 0, 0, 1, 1, 0), LoweringSettings()),
        profile(TV, bands(0, 0, -1, -3, 2, 4, 5, 3, 0, 0), LoweringSettings(), peak(4_000, 2)),
        profile(PHONE, bands(-2, -2, 0, 1, 3, 5, 3, -3, -6, -6), LoweringSettings(), peak(2_000, 3, 1f)),
        profile(OUTDOOR, bands(-2, -4, -5, -3, 1, 3, 4, 2, 0, 0), LoweringSettings()),
        profile(CAR, bands(-3, -6, -6, -4, 1, 4, 5, 2, 0, 0), LoweringSettings()),
        profile(FL_MODERATE, EqCurves.mild(), LoweringSettings.moderate()),
        profile(FL_STRONG, EqCurves.strong(), LoweringSettings.strong()),
        profile(EqCurves.FLAT, EqCurves.flat(), LoweringSettings()),
    )

    fun byId(id: String): SoundProfile? = builtins().firstOrNull { it.id == id }

    private fun profile(
        id: String,
        curve: EqCurve,
        lowering: LoweringSettings,
        tones: List<ToneBand> = curve.tones,
        adaptive: AdaptiveSettings = AdaptiveSettings(),
    ): SoundProfile {
        return SoundProfile(id, "", curve.copy(tones = tones), lowering, adaptive, builtin = true)
    }

    private fun bands(vararg gain: Int): EqCurve = EqCurve(graphic = gain.toList())

    private fun peak(hz: Int, gain: Int, q: Float = 1.2f): List<ToneBand> {
        return listOf(ToneBand(ToneShape.Peak, hz, q, gain), ToneBand(), ToneBand())
    }

    private fun clarityCurve(): EqCurve {
        return EqCurve(
            graphic = listOf(0, -2, -3, -2, 1, 4, 6, 3, 0, 0),
            tones = listOf(
                ToneBand(shape = ToneShape.Peak, frequencyHz = 3_000, q = 1.2f, gainDb = 3),
                ToneBand(),
                ToneBand(),
            ),
        )
    }
}
