package com.soniboost.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal class Radix2Fft(val size: Int) {
    private val cos = FloatArray(size / 2)
    private val sin = FloatArray(size / 2)
    private val bitReverse = IntArray(size)

    init {
        require(size > 1 && size and (size - 1) == 0)
        var bits = 0
        var rest = size
        while (rest > 1) {
            bits++
            rest = rest shr 1
        }
        for (index in 0 until size) {
            bitReverse[index] = reverseBits(index, bits)
        }
        for (index in 0 until size / 2) {
            val angle = 2.0 * PI * index / size
            cos[index] = cos(angle).toFloat()
            sin[index] = sin(angle).toFloat()
        }
    }

    fun forward(real: FloatArray, imag: FloatArray) = transform(real, imag, inverse = false)

    fun inverse(real: FloatArray, imag: FloatArray) {
        transform(real, imag, inverse = true)
        val scale = 1f / size
        for (index in 0 until size) {
            real[index] *= scale
            imag[index] *= scale
        }
    }

    private fun transform(real: FloatArray, imag: FloatArray, inverse: Boolean) {
        for (index in 0 until size) {
            val swapped = bitReverse[index]
            if (swapped > index) {
                val realSwap = real[index]
                real[index] = real[swapped]
                real[swapped] = realSwap
                val imagSwap = imag[index]
                imag[index] = imag[swapped]
                imag[swapped] = imagSwap
            }
        }
        var length = 2
        while (length <= size) {
            val half = length / 2
            val step = size / length
            var start = 0
            while (start < size) {
                var table = 0
                for (offset in 0 until half) {
                    val wr = cos[table]
                    val wi = if (inverse) sin[table] else -sin[table]
                    val odd = start + offset + half
                    val even = start + offset
                    val vr = real[odd] * wr - imag[odd] * wi
                    val vi = real[odd] * wi + imag[odd] * wr
                    val ur = real[even]
                    val ui = imag[even]
                    real[even] = ur + vr
                    imag[even] = ui + vi
                    real[odd] = ur - vr
                    imag[odd] = ui - vi
                    table += step
                }
                start += length
            }
            length *= 2
        }
    }

    private fun reverseBits(value: Int, bits: Int): Int {
        var source = value
        var reversed = 0
        repeat(bits) {
            reversed = (reversed shl 1) or (source and 1)
            source = source shr 1
        }
        return reversed
    }
}
