package com.soniboost.audio

class Biquad {
    @Volatile
    var coeffs: Coeff = Coeff.BYPASS

    private var z1 = 0f
    private var z2 = 0f

    fun process(sample: Float): Float {
        val coeff = coeffs
        if (coeff.bypass) {
            z1 = 0f
            z2 = 0f
            return sample
        }
        val output = coeff.b0 * sample + z1
        z1 = coeff.b1 * sample - coeff.a1 * output + z2
        z2 = coeff.b2 * sample - coeff.a2 * output
        return output
    }

    data class Coeff(
        val b0: Float,
        val b1: Float,
        val b2: Float,
        val a1: Float,
        val a2: Float,
        val bypass: Boolean,
    ) {
        companion object {
            val BYPASS = Coeff(1f, 0f, 0f, 0f, 0f, true)
        }
    }
}
