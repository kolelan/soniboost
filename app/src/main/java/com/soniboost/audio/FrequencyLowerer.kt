package com.soniboost.audio

import kotlin.math.PI
import kotlin.math.cos

/**
 * Перенос высоких частот через короткое оконное преобразование.
 * Выключенный модуль звук не задерживает.
 */
class FrequencyLowerer {
    private val fftSize = 512
    private val hop = 256
    private val fft = Radix2Fft(fftSize)
    private val window = FloatArray(fftSize)
    private val history = FloatArray(fftSize)
    private val overlap = FloatArray(fftSize)
    private val real = FloatArray(fftSize)
    private val imag = FloatArray(fftSize)
    private val dryReal = FloatArray(fftSize)
    private val dryImag = FloatArray(fftSize)
    private val wetReal = FloatArray(fftSize)
    private val wetImag = FloatArray(fftSize)
    private val inputHold = FloatArray(hop)
    private val output = FloatArray(hop * 3)
    private var inputCount = 0
    private var outputCount = 0

    private class Run(
        val settings: LoweringSettings,
        val allowed: Boolean,
        val sampleRate: Int,
        val token: Int,
    )

    @Volatile
    private var run = Run(LoweringSettings(), true, 0, 0)
    private var appliedToken = 0

    init {
        for (index in window.indices) {
            window[index] = (0.5 * (1.0 - cos(2.0 * PI * index / fftSize))).toFloat()
        }
    }

    fun configure(settings: LoweringSettings, allowed: Boolean, sampleRate: Int) {
        val previous = run
        val nextRate = if (sampleRate > 0) sampleRate else previous.sampleRate
        val nextActive = settings.enabled && allowed && nextRate > 0
        val previousActive = previous.settings.enabled && previous.allowed && previous.sampleRate > 0
        val restart = nextActive != previousActive || (nextActive && nextRate != previous.sampleRate)
        val token = if (restart) previous.token + 1 else previous.token
        run = Run(settings, allowed, nextRate, token)
    }

    fun process(buffer: ShortArray, count: Int) {
        val current = run
        val active = current.settings.enabled && current.allowed && current.sampleRate > 0
        if (current.token != appliedToken) {
            reset(active)
            appliedToken = current.token
        }
        if (!active) return
        val rate = current.sampleRate
        var offset = 0
        while (offset < count) {
            val take = minOf(hop - inputCount, count - offset)
            for (index in 0 until take) {
                inputHold[inputCount + index] = buffer[offset + index] / 32_768f
            }
            inputCount += take
            offset += take
            if (inputCount == hop) {
                synthesize(current.settings, rate)
                inputCount = 0
            }
        }
        val available = minOf(outputCount, count)
        for (index in 0 until available) {
            buffer[index] = (output[index].coerceIn(-1f, 1f) * 32_767f).toInt().toShort()
        }
        for (index in available until count) buffer[index] = 0
        if (available > 0) {
            System.arraycopy(output, available, output, 0, outputCount - available)
            outputCount -= available
        }
    }

    private fun reset(active: Boolean) {
        history.fill(0f)
        overlap.fill(0f)
        output.fill(0f)
        inputCount = 0
        outputCount = if (active) hop else 0
    }

    private fun synthesize(current: LoweringSettings, rate: Int) {
        System.arraycopy(history, hop, history, 0, fftSize - hop)
        System.arraycopy(inputHold, 0, history, fftSize - hop, hop)
        for (index in 0 until fftSize) {
            real[index] = history[index] * window[index]
            imag[index] = 0f
        }
        fft.forward(real, imag)
        remap(current, rate)
        fft.inverse(real, imag)
        for (index in 0 until fftSize) overlap[index] += real[index]
        for (index in 0 until hop) output[outputCount + index] = overlap[index]
        outputCount += hop
        System.arraycopy(overlap, hop, overlap, 0, fftSize - hop)
        overlap.fill(0f, fftSize - hop, fftSize)
    }

    private fun remap(current: LoweringSettings, rate: Int) {
        real.copyInto(dryReal)
        imag.copyInto(dryImag)
        real.copyInto(wetReal)
        imag.copyInto(wetImag)
        val binHz = rate.toFloat() / fftSize
        val nyquist = fftSize / 2
        val cutoffBin = (current.cutoffHz / binHz).toInt().coerceIn(1, nyquist - 1)
        for (bin in cutoffBin until nyquist) {
            wetReal[bin] = 0f
            wetImag[bin] = 0f
        }
        wetReal[nyquist] = 0f
        wetImag[nyquist] = 0f
        val ratio = current.ratio.coerceIn(LoweringStore.MIN_RATIO, LoweringStore.MAX_RATIO)
        val shift = current.shiftHz.toFloat()
        for (bin in cutoffBin until nyquist) {
            val frequency = bin * binHz
            val target = when (current.method) {
                LoweringMethod.Compression -> current.cutoffHz + (frequency - current.cutoffHz) / ratio
                LoweringMethod.Transposition -> frequency - shift
            }
            if (target < binHz || target >= rate * 0.5f) continue
            val destination = (target / binHz).toInt().coerceIn(1, nyquist - 1)
            wetReal[destination] += dryReal[bin]
            wetImag[destination] += dryImag[bin]
        }
        for (bin in 1 until nyquist) {
            wetReal[fftSize - bin] = wetReal[bin]
            wetImag[fftSize - bin] = -wetImag[bin]
        }
        val dryMix = 1f - current.mix.coerceIn(0f, 1f)
        val wetMix = current.mix.coerceIn(0f, 1f)
        for (bin in 0 until fftSize) {
            real[bin] = dryReal[bin] * dryMix + wetReal[bin] * wetMix
            imag[bin] = dryImag[bin] * dryMix + wetImag[bin] * wetMix
        }
    }
}
