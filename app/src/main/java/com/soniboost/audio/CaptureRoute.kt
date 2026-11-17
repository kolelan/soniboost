package com.soniboost.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HeadsetMicUnavailable : Exception()

/**
 * Выбирает вход: корпус телефона либо микрофон подключённых наушников.
 * Для Bluetooth микрофон наушников — это профиль звонка (SCO): воспроизведение
 * тоже уходит в него, иначе гарнитура не отдаёт свой микрофон.
 */
class CaptureRoute(
    private val context: Context,
    private val audioManager: AudioManager,
) {
    private val scoRequested = AtomicBoolean(false)

    fun prepare(choice: MicrophoneChoice, aborted: () -> Boolean): PreparedCapture {
        val leavingCallProfile = scoRequested.get()
        release()
        if (aborted()) throw RouteAborted()
        return when (choice) {
            MicrophoneChoice.Phone -> preparePhone(aborted, leavingCallProfile)
            MicrophoneChoice.Headset -> prepareHeadset(aborted)
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        stopScoLegacy()
        audioManager.mode = AudioManager.MODE_NORMAL
        scoRequested.set(false)
    }

    private fun preparePhone(aborted: () -> Boolean, leavingCallProfile: Boolean): PreparedCapture {
        if (leavingCallProfile) {
            awaitScoState(aborted, disconnected = true)
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        return PreparedCapture(
            source = android.media.MediaRecorder.AudioSource.MIC,
            device = findInput(AudioDeviceInfo.TYPE_BUILTIN_MIC),
            playbackDevice = awaitMediaOutput(aborted, leavingCallProfile),
            voicePlayback = false,
            requireDevice = false,
        )
    }

    private fun prepareHeadset(aborted: () -> Boolean): PreparedCapture {
        findWiredInput()?.let { wired ->
            return PreparedCapture(
                source = android.media.MediaRecorder.AudioSource.MIC,
                device = wired,
                playbackDevice = null,
                voicePlayback = false,
                requireDevice = true,
            )
        }
        val sco = connectBluetoothMic(aborted) ?: throw HeadsetMicUnavailable()
        return PreparedCapture(
            source = android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            device = sco,
            playbackDevice = null,
            voicePlayback = true,
            requireDevice = true,
        )
    }

    private fun connectBluetoothMic(aborted: () -> Boolean): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val communication = runCatching {
                audioManager.availableCommunicationDevices.firstOrNull { it.isBluetoothMic() }
            }.getOrNull()
            if (communication != null && runCatching {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.setCommunicationDevice(communication)
                }.getOrDefault(false)
            ) {
                scoRequested.set(true)
            }
        }
        if (!scoRequested.get()) startScoLegacy()
        if (aborted()) throw RouteAborted()
        awaitScoState(aborted, disconnected = false)
        val input = findInput(AudioDeviceInfo.TYPE_BLUETOOTH_SCO) ?: findBleInput()
        if (input == null) {
            release()
            return null
        }
        return input
    }

    @Suppress("DEPRECATION")
    private fun awaitScoState(aborted: () -> Boolean, disconnected: Boolean) {
        val targetReached = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (matchesSco(intent, disconnected)) targetReached.countDown()
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        try {
            if (sticky != null && matchesSco(sticky, disconnected)) return
            val deadline = SystemClock.elapsedRealtime() + SCO_WAIT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (aborted() || Thread.currentThread().isInterrupted) throw RouteAborted()
                if (targetReached.await(50, TimeUnit.MILLISECONDS)) return
            }
        } catch (_: InterruptedException) {
            throw RouteAborted()
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    @Suppress("DEPRECATION")
    private fun matchesSco(intent: Intent, disconnected: Boolean): Boolean {
        val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
        return if (disconnected) {
            state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED || state == AudioManager.SCO_AUDIO_STATE_ERROR
        } else {
            state == AudioManager.SCO_AUDIO_STATE_CONNECTED
        }
    }

    private fun awaitMediaOutput(aborted: () -> Boolean, wait: Boolean): AudioDeviceInfo? {
        if (!wait) return findMediaOutput()
        val deadline = SystemClock.elapsedRealtime() + SCO_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (aborted() || Thread.currentThread().isInterrupted) throw RouteAborted()
            findMediaOutput()?.let { return it }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                throw RouteAborted()
            }
        }
        return findMediaOutput()
    }

    private fun findMediaOutput(): AudioDeviceInfo? {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val types = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        )
        return types.firstNotNullOfOrNull { type -> outputs.firstOrNull { it.type == type } }
    }

    @Suppress("DEPRECATION")
    private fun startScoLegacy() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        scoRequested.set(true)
    }

    @Suppress("DEPRECATION")
    private fun stopScoLegacy() {
        runCatching {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        }
    }

    private fun findWiredInput(): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private fun findInput(type: Int): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == type }
    }

    private fun findBleInput(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return findInput(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private fun AudioDeviceInfo.isBluetoothMic(): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    data class PreparedCapture(
        val source: Int,
        val device: AudioDeviceInfo?,
        val playbackDevice: AudioDeviceInfo?,
        val voicePlayback: Boolean,
        val requireDevice: Boolean,
    )

    class RouteAborted : Exception()

    private companion object {
        const val SCO_WAIT_MS = 3_000L
    }
}
