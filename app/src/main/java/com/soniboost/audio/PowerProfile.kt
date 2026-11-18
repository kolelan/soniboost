package com.soniboost.audio

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

enum class PowerProfile {
    Max,
    Balanced,
    Saver,
}

fun PowerProfile.allowsFrequencyLowering(): Boolean = this == PowerProfile.Max

fun PowerProfile.allowsAdaptiveBoost(): Boolean = this != PowerProfile.Saver

fun PowerProfile.adaptivePace(): Float = when (this) {
    PowerProfile.Max -> 1f
    PowerProfile.Balanced -> 0.35f
    PowerProfile.Saver -> 0f
}

@Suppress("DEPRECATION")
fun Context.currentPowerProfile(): PowerProfile {
    val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    return powerProfileOf(battery)
}

fun powerProfileOf(intent: Intent?): PowerProfile {
    if (intent == null) return PowerProfile.Max
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
    val celsius = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) / 10f
    return when {
        percent < 20 || celsius > 42f -> PowerProfile.Saver
        percent <= 50 || celsius >= 38f -> PowerProfile.Balanced
        else -> PowerProfile.Max
    }
}
