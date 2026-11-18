package com.soniboost.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Сразу после микрофона приглушает звук ниже порога.
 * Выключенный модуль сигнал не меняет и задержку не добавляет.
 */
class NoiseGate {
    private class Run(
        val settings: NoiseGateSettings,
        val sampleRate: Int,
        val token: Int,
    )

    @Volatile
    private var run = Run(NoiseGateSettings(), 0, 0)
    private var appliedToken = 0
    private var envelope = 0f
    private var gain = 1f
    private var hold = 0

    fun configure(settings: NoiseGateSettings, sampleRate: Int) {
        val previous = run
        val nextRate = if (sampleRate > 0) sampleRate else previous.sampleRate
        val nextActive = settings.enabled && nextRate > 0
        val previousActive = previous.settings.enabled && previous.sampleRate > 0
        val restart = nextActive != previousActive || (nextActive && nextRate != previous.sampleRate)
        val token = if (restart) previous.token + 1 else previous.token
        run = Run(settings, nextRate, token)
    }

    fun process(buffer: ShortArray, count: Int) {
        val current = run
        val active = current.settings.enabled && current.sampleRate > 0
        if (current.token != appliedToken) {
            reset()
            appliedToken = current.token
        }
        if (!active) return
        val rate = current.sampleRate
        val threshold = current.settings.thresholdDb.toFloat()
        val depth = current.settings.depthDb.coerceIn(NoiseGateSettings.MIN_DEPTH, NoiseGateSettings.MAX_DEPTH)
        val follow = coeff(rate, ENVELOPE_SECONDS)
        val attackMs = current.settings.attackMs.coerceIn(NoiseGateSettings.MIN_ATTACK, NoiseGateSettings.MAX_ATTACK)
        val releaseMs = current.settings.releaseMs.coerceIn(NoiseGateSettings.MIN_RELEASE, NoiseGateSettings.MAX_RELEASE)
        val holdMs = current.settings.holdMs.coerceIn(NoiseGateSettings.MIN_HOLD, NoiseGateSettings.MAX_HOLD)
        val open = coeff(rate, attackMs / 1_000f)
        val close = coeff(rate, releaseMs / 1_000f)
        val holdSamples = rate * holdMs / 1_000
        for (index in 0 until count) {
            val sample = buffer[index] / 32_768f
            val level = abs(sample)
            envelope += (level - envelope) * follow
            val db = (20.0 * log10(envelope.toDouble() + 1e-7)).toFloat()
            val target = if (db >= threshold) {
                hold = holdSamples
                1f
            } else if (hold > 0) {
                hold--
                1f
            } else {
                val below = (threshold - db).coerceAtLeast(0f)
                val reduction = below.coerceAtMost(depth.toFloat())
                10.0.pow(-reduction / 20.0).toFloat()
            }
            val step = if (target > gain) open else close
            gain += (target - gain) * step
            buffer[index] = (sample * gain).coerceIn(-1f, 1f).times(32_767f).toInt().toShort()
        }
    }

    private fun coeff(rate: Int, seconds: Float): Float {
        return (1.0 - exp(-1.0 / rate / seconds)).toFloat().coerceIn(0f, 1f)
    }

    private fun reset() {
        envelope = 0f
        gain = 1f
        hold = 0
    }

    private companion object {
        const val ENVELOPE_SECONDS = 0.004f
    }
}
