package com.soniboost.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * После переноса частот поднимает тихие куски около 3 кГц, когда кадр похож на речь.
 * Выключенный модуль звук не меняет. Голос из смеси не вырезается.
 */
class AdaptiveBooster {
    private val fftSize = 512
    private val hop = 256
    private val fft = Radix2Fft(fftSize)
    private val window = FloatArray(fftSize)
    private val history = FloatArray(fftSize)
    private val real = FloatArray(fftSize)
    private val imag = FloatArray(fftSize)
    private var historyIndex = 0
    private var hopCount = 0
    private var hopSquare = 0f
    private var hopParity = 0

    private var fastEnv = 0f
    private var slowEnv = 0f
    private var peakEnv = 0f
    private var noiseFloor = 0f
    private var primed = false
    private var aimDb = 0f
    private var gainDb = 0f

    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var live = false
    private var z1 = 0f
    private var z2 = 0f

    private class Run(
        val settings: AdaptiveSettings,
        val allowed: Boolean,
        val pace: Float,
        val sampleRate: Int,
        val token: Int,
    )

    @Volatile
    private var run = Run(AdaptiveSettings(), true, 1f, 0, 0)
    private var appliedToken = 0

    init {
        for (index in window.indices) {
            window[index] = (0.5 * (1.0 - cos(2.0 * PI * index / fftSize))).toFloat()
        }
    }

    fun configure(settings: AdaptiveSettings, allowed: Boolean, pace: Float, sampleRate: Int) {
        val previous = run
        val nextRate = if (sampleRate > 0) sampleRate else previous.sampleRate
        val nextPace = pace.coerceIn(0.2f, 1f)
        val nextActive = settings.enabled && allowed && nextRate > 0
        val previousActive = previous.settings.enabled && previous.allowed && previous.sampleRate > 0
        val restart = nextActive != previousActive || (nextActive && nextRate != previous.sampleRate)
        val token = if (restart) previous.token + 1 else previous.token
        run = Run(settings, allowed, nextPace, nextRate, token)
    }

    fun process(buffer: ShortArray, count: Int) {
        val current = run
        val active = current.settings.enabled && current.allowed && current.sampleRate > 0
        if (current.token != appliedToken) {
            reset()
            appliedToken = current.token
        }
        if (!active) return
        for (index in 0 until count) {
            val sample = buffer[index] / 32_768f
            history[historyIndex] = sample
            historyIndex = (historyIndex + 1) and (fftSize - 1)
            hopSquare += sample * sample
            hopCount++
            if (hopCount == hop) {
                updateGain(current)
                hopCount = 0
                hopSquare = 0f
            }
            buffer[index] = (filter(sample).coerceIn(-1f, 1f) * 32_767f).toInt().toShort()
        }
    }

    private fun updateGain(current: Run) {
        val rate = current.sampleRate
        val rms = sqrt(hopSquare / hop)
        if (rms < SILENCE_RMS) {
            aimDb = 0f
            moveGain(current)
            return
        }
        hopParity = hopParity xor 1
        if (current.pace < 0.99f && hopParity == 1) {
            moveGain(current)
            return
        }
        var read = historyIndex
        for (index in 0 until fftSize) {
            real[index] = history[read] * window[index]
            imag[index] = 0f
            read = (read + 1) and (fftSize - 1)
        }
        fft.forward(real, imag)
        val speech = meanPower(rate, 300, 4_000)
        val syllable = meanPower(rate, 1_000, 4_000)
        if (!primed) {
            fastEnv = syllable
            slowEnv = syllable
            peakEnv = syllable.coerceAtLeast(1e-8f)
            noiseFloor = 1e-6f
            primed = true
        }
        val fastCoeff = coeff(rate, 0.03f)
        val slowCoeff = coeff(rate, 0.25f)
        fastEnv += (syllable - fastEnv) * fastCoeff
        slowEnv += (syllable - slowEnv) * slowCoeff
        val modulation = abs(fastEnv - slowEnv) / (slowEnv + 1e-12f)
        if (speech < noiseFloor) {
            noiseFloor = speech * 0.2f + noiseFloor * 0.8f
        } else {
            noiseFloor = speech * 0.002f + noiseFloor * 0.998f
        }
        val decay = coeff(rate, 0.4f)
        peakEnv += (syllable - peakEnv) * if (syllable > peakEnv) 1f else decay
        val flatness = flatness(rate)
        val tonal = ((0.72f - flatness) / 0.42f).coerceIn(0f, 1f)
        val snrDb = (10.0 * log10((speech / (noiseFloor + 1e-12f)).toDouble())).toFloat()
        val snrScore = ((snrDb - 4f) / 14f).coerceIn(0f, 1f)
        val modScore = ((modulation - 0.04f) / 0.22f).coerceIn(0f, 1f)
        val likelihood = tonal * snrScore * (0.4f + 0.6f * modScore)
        val caution = current.settings.caution.coerceIn(AdaptiveSettings.MIN_CAUTION, AdaptiveSettings.MAX_CAUTION)
        val threshold = 0.08f + caution / 100f * 0.47f
        val gate = ((likelihood - threshold) / 0.22f).coerceIn(0f, 1f)
        val dip = 1f - (syllable / (peakEnv + 1e-12f)).coerceIn(0f, 1f)
        val quietness = ((dip - 0.15f) / 0.6f).coerceIn(0f, 1f)
        val emphasis = 0.35f + 0.65f * quietness
        val strength = current.settings.strengthDb.coerceIn(AdaptiveSettings.MIN_STRENGTH, AdaptiveSettings.MAX_STRENGTH)
        aimDb = strength * emphasis * gate
        moveGain(current)
    }

    private fun moveGain(current: Run) {
        val rising = aimDb > gainDb
        val tau = (if (rising) 0.04f else 0.18f) / current.pace
        gainDb += (aimDb - gainDb) * coeff(current.sampleRate, tau)
        tune(current.sampleRate, gainDb)
    }

    private fun tune(rate: Int, gain: Float) {
        if (gain < 0.2f) {
            live = false
            z1 = 0f
            z2 = 0f
            return
        }
        val amplitude = 10.0.pow(gain / 40.0)
        val frequency = 3_000.0.coerceAtMost(rate * 0.45)
        val omega = 2.0 * PI * frequency / rate
        val cosine = cos(omega)
        val sine = sin(omega)
        val alpha = sine / (2.0 * 1.2)
        val a0 = 1.0 + alpha / amplitude
        b0 = ((1.0 + alpha * amplitude) / a0).toFloat()
        b1 = ((-2.0 * cosine) / a0).toFloat()
        b2 = ((1.0 - alpha * amplitude) / a0).toFloat()
        a1 = ((-2.0 * cosine) / a0).toFloat()
        a2 = ((1.0 - alpha / amplitude) / a0).toFloat()
        live = true
    }

    private fun filter(sample: Float): Float {
        if (!live) return sample
        val output = b0 * sample + z1
        z1 = b1 * sample - a1 * output + z2
        z2 = b2 * sample - a2 * output
        return output
    }

    private fun flatness(rate: Int): Float {
        val nyquist = fftSize / 2
        val from = bin(rate, 300).coerceIn(1, nyquist)
        val to = bin(rate, 4_000).coerceIn(from, nyquist)
        var logSum = 0.0
        var magSum = 0.0
        var count = 0
        for (index in from..to) {
            val magnitude = sqrt(real[index] * real[index] + imag[index] * imag[index]).toDouble() + 1e-12
            logSum += ln(magnitude)
            magSum += magnitude
            count++
        }
        if (count == 0 || magSum <= 0.0) return 1f
        return (exp(logSum / count) / (magSum / count)).toFloat().coerceIn(0f, 1f)
    }

    private fun meanPower(rate: Int, fromHz: Int, toHz: Int): Float {
        val nyquist = fftSize / 2
        val from = bin(rate, fromHz).coerceIn(1, nyquist)
        val to = bin(rate, toHz).coerceIn(from, nyquist)
        var sum = 0f
        var count = 0
        for (index in from..to) {
            sum += real[index] * real[index] + imag[index] * imag[index]
            count++
        }
        return if (count == 0) 0f else sum / count
    }

    private fun bin(rate: Int, hz: Int): Int = (hz.toLong() * fftSize / rate).toInt()

    private fun coeff(rate: Int, seconds: Float): Float {
        return (1.0 - exp(-hop.toDouble() / rate / seconds)).toFloat().coerceIn(0f, 1f)
    }

    private fun reset() {
        history.fill(0f)
        historyIndex = 0
        hopCount = 0
        hopSquare = 0f
        hopParity = 0
        fastEnv = 0f
        slowEnv = 0f
        peakEnv = 0f
        noiseFloor = 0f
        primed = false
        aimDb = 0f
        gainDb = 0f
        live = false
        z1 = 0f
        z2 = 0f
    }

    private companion object {
        const val SILENCE_RMS = 0.0015f
    }
}
