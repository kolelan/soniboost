package com.soniboost.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class ToneShape {
    Peak,
    LowShelf,
    HighShelf,
}

data class ToneBand(
    val shape: ToneShape = ToneShape.Peak,
    val frequencyHz: Int = 1_000,
    val q: Float = 1f,
    val gainDb: Int = 0,
) {
    fun coefficients(sampleRate: Int): Biquad.Coeff {
        if (gainDb == 0 || sampleRate <= 0) return Biquad.Coeff.BYPASS
        val rate = sampleRate.toDouble()
        val frequency = frequencyHz.toDouble().coerceIn(60.0, rate * 0.45)
        val quality = q.toDouble().coerceIn(0.3, 10.0)
        val amplitude = 10.0.pow(gainDb / 40.0)
        val omega = 2.0 * PI * frequency / rate
        val cosine = cos(omega)
        val sine = sin(omega)
        val (b0, b1, b2, a0, a1, a2) = when (shape) {
            ToneShape.Peak -> peak(amplitude, cosine, sine, quality)
            ToneShape.LowShelf -> shelf(amplitude, cosine, sine, quality, high = false)
            ToneShape.HighShelf -> shelf(amplitude, cosine, sine, quality, high = true)
        }
        return Biquad.Coeff(
            b0 = (b0 / a0).toFloat(),
            b1 = (b1 / a0).toFloat(),
            b2 = (b2 / a0).toFloat(),
            a1 = (a1 / a0).toFloat(),
            a2 = (a2 / a0).toFloat(),
            bypass = false,
        )
    }

    private fun peak(amplitude: Double, cosine: Double, sine: Double, quality: Double): FilterTerms {
        val alpha = sine / (2.0 * quality)
        return FilterTerms(
            b0 = 1.0 + alpha * amplitude,
            b1 = -2.0 * cosine,
            b2 = 1.0 - alpha * amplitude,
            a0 = 1.0 + alpha / amplitude,
            a1 = -2.0 * cosine,
            a2 = 1.0 - alpha / amplitude,
        )
    }

    private fun shelf(
        amplitude: Double,
        cosine: Double,
        sine: Double,
        quality: Double,
        high: Boolean,
    ): FilterTerms {
        val slope = quality.coerceIn(0.5, 1.0)
        val underSqrt = ((amplitude + 1.0 / amplitude) * (1.0 / slope - 1.0) + 2.0).coerceAtLeast(0.0)
        val alpha = sine / 2.0 * sqrt(underSqrt)
        val beta = 2.0 * sqrt(amplitude) * alpha
        val sign = if (high) 1.0 else -1.0
        return FilterTerms(
            b0 = amplitude * ((amplitude + 1.0) + sign * (amplitude - 1.0) * cosine + beta),
            b1 = -sign * 2.0 * amplitude * ((amplitude - 1.0) + sign * (amplitude + 1.0) * cosine),
            b2 = amplitude * ((amplitude + 1.0) + sign * (amplitude - 1.0) * cosine - beta),
            a0 = (amplitude + 1.0) - sign * (amplitude - 1.0) * cosine + beta,
            a1 = sign * 2.0 * ((amplitude - 1.0) - sign * (amplitude + 1.0) * cosine),
            a2 = (amplitude + 1.0) - sign * (amplitude - 1.0) * cosine - beta,
        )
    }
}

private data class FilterTerms(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a0: Double,
    val a1: Double,
    val a2: Double,
)

data class EqCurve(
    val graphic: List<Int> = EqCurves.flatGraphic(),
    val tones: List<ToneBand> = EqCurves.flatTones(),
) {
    companion object {
        const val BAND_COUNT = 10
        const val TONE_COUNT = 3
        const val MIN_DB = -12
        const val MAX_DB = 12
        val CUTOFFS = floatArrayOf(
            63f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 12_000f, 16_000f,
        )
        val LABELS = arrayOf("63", "125", "250", "500", "1k", "2k", "4k", "8k", "12k", "16k")
    }
}

object EqCurves {
    const val SPEECH = "speech_mild"
    const val STRONG = "speech_strong"
    const val FLAT = "flat"

    fun mild(): EqCurve = EqCurve(graphic = listOf(0, 0, 0, 0, 0, 3, 3, 0, 0, 0))

    fun strong(): EqCurve = EqCurve(graphic = listOf(0, 0, 0, 0, 0, 8, 8, 0, 0, 0))

    fun flat(): EqCurve = EqCurve()

    fun byId(id: String): EqCurve? {
        return when (id) {
            SPEECH -> mild()
            STRONG -> strong()
            FLAT -> flat()
            else -> null
        }
    }

    fun flatGraphic(): List<Int> = List(EqCurve.BAND_COUNT) { 0 }

    fun flatTones(): List<ToneBand> = List(EqCurve.TONE_COUNT) { ToneBand() }
}
