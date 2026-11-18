package com.soniboost.audio

data class SoundProfile(
    val id: String,
    val name: String,
    val curve: EqCurve,
    val lowering: LoweringSettings,
    val adaptive: AdaptiveSettings = AdaptiveSettings(),
    val builtin: Boolean,
    val description: String = "",
    val gate: NoiseGateSettings = NoiseGateSettings(),
    val clarity: ClaritySettings = ClaritySettings(),
    val limiterDb: Int = OutputLevel.DEFAULT_DB,
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
        profile(EqCurves.SPEECH, EqCurves.mild(), LoweringSettings(), gate = speechGate(), clarity = clarity(4, -36), limiterDb = -8),
        profile(EqCurves.STRONG, EqCurves.strong(), LoweringSettings(), gate = strongGate(), clarity = clarity(3, -34), limiterDb = -10),
        profile(CLARITY, clarityCurve(), LoweringSettings(), gate = speechGate(), clarity = clarity(6, -36), limiterDb = -9),
        profile(ADAPTIVE, EqCurves.mild(), LoweringSettings(), adaptive = AdaptiveSettings.moderate(), gate = adaptiveGate(), clarity = clarity(3, -38), limiterDb = -8),
        profile(EVERYDAY, bands(0, 0, -1, -1, 1, 3, 3, 1, 0, 0), LoweringSettings(), gate = everydayGate(), clarity = clarity(4, -36), limiterDb = -8),
        profile(NOISE, bands(0, -1, -4, -4, 1, 3, 6, 2, 0, 0), LoweringSettings(), peak(3_000, 3), gate = noiseGate(), clarity = clarity(6, -30), limiterDb = -10),
        profile(QUIET, bands(0, 0, 0, 0, 0, 2, 3, 1, 0, 0), LoweringSettings(), peak(3_000, 2, 1f), gate = quietGate(), clarity = clarity(5, -42), limiterDb = -6),
        profile(MUSIC, bands(1, 1, 1, 0, 0, 0, 0, 1, 1, 0), LoweringSettings()),
        profile(TV, bands(0, 0, -1, -3, 2, 4, 5, 3, 0, 0), LoweringSettings(), peak(4_000, 2), gate = tvGate(), clarity = clarity(5, -40), limiterDb = -8),
        profile(PHONE, bands(-2, -2, 0, 1, 3, 5, 3, -3, -6, -6), LoweringSettings(), peak(2_000, 3, 1f), gate = phoneGate(), clarity = clarity(4, -32), limiterDb = -10),
        profile(OUTDOOR, bands(-2, -4, -5, -3, 1, 3, 4, 2, 0, 0), LoweringSettings(), gate = outdoorGate(), clarity = clarity(5, -32), limiterDb = -8),
        profile(CAR, bands(-3, -6, -6, -4, 1, 4, 5, 2, 0, 0), LoweringSettings(), gate = carGate(), clarity = clarity(4, -34), limiterDb = -10),
        profile(FL_MODERATE, EqCurves.mild(), LoweringSettings.moderate(), gate = speechGate(), clarity = clarity(4, -36), limiterDb = -8),
        profile(FL_STRONG, EqCurves.strong(), LoweringSettings.strong(), gate = strongGate(), clarity = clarity(3, -34), limiterDb = -10),
        profile(EqCurves.FLAT, EqCurves.flat(), LoweringSettings()),
    )

    fun byId(id: String): SoundProfile? = builtins().firstOrNull { it.id == id }

    private fun profile(
        id: String,
        curve: EqCurve,
        lowering: LoweringSettings,
        tones: List<ToneBand> = curve.tones,
        adaptive: AdaptiveSettings = AdaptiveSettings(),
        gate: NoiseGateSettings = NoiseGateSettings(),
        clarity: ClaritySettings = ClaritySettings(),
        limiterDb: Int = OutputLevel.DEFAULT_DB,
    ): SoundProfile {
        return SoundProfile(
            id, "", curve.copy(tones = tones), lowering, adaptive,
            builtin = true, gate = gate, clarity = clarity, limiterDb = limiterDb,
        )
    }

    private fun clarity(strength: Int, threshold: Int): ClaritySettings {
        return ClaritySettings(enabled = true, strength = strength, thresholdDb = threshold)
    }

    private fun gate(
        threshold: Int,
        depth: Int,
        hold: Int,
        release: Int,
        attack: Int = NoiseGateSettings.DEFAULT_ATTACK,
    ): NoiseGateSettings {
        return NoiseGateSettings(
            enabled = true,
            thresholdDb = threshold,
            depthDb = depth,
            attackMs = attack,
            holdMs = hold,
            releaseMs = release,
        )
    }

    private fun speechGate() = gate(-48, 12, hold = 160, release = 250)

    private fun strongGate() = gate(-48, 18, hold = 140, release = 220)

    private fun adaptiveGate() = gate(-46, 18, hold = 140, release = 200)

    private fun everydayGate() = gate(-50, 12, hold = 180, release = 280)

    private fun noiseGate() = gate(-36, 24, hold = 80, release = 120)

    private fun quietGate() = gate(-56, 10, hold = 220, release = 360, attack = 1)

    private fun tvGate() = gate(-44, 14, hold = 180, release = 300, attack = 3)

    private fun phoneGate() = gate(-40, 20, hold = 100, release = 160)

    private fun outdoorGate() = gate(-40, 18, hold = 100, release = 140)

    private fun carGate() = gate(-44, 16, hold = 200, release = 400, attack = 3)

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
