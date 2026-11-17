package com.soniboost.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager

enum class HeadphoneRoute {
    Wired,
    Wireless,
    None,
}

fun headphoneRouteOf(deviceTypes: Collection<Int>): HeadphoneRoute {
    if (deviceTypes.any { it in WIRED_OUTPUTS }) return HeadphoneRoute.Wired
    if (deviceTypes.any { it in WIRELESS_OUTPUTS }) return HeadphoneRoute.Wireless
    return HeadphoneRoute.None
}

fun AudioManager.currentHeadphoneRoute(): HeadphoneRoute {
    val types = getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
    return headphoneRouteOf(types)
}

private val WIRED_OUTPUTS = intArrayOf(
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
)

private val WIRELESS_OUTPUTS = intArrayOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_HEARING_AID,
)
