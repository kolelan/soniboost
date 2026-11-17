package com.soniboost

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.soniboost.audio.HeadphoneRoute
import com.soniboost.audio.MicrophoneChoice
import com.soniboost.audio.MicrophoneChoiceStore
import com.soniboost.audio.currentHeadphoneRoute
import com.soniboost.disclaimer.DisclaimerStore

class MainActivity : AppCompatActivity() {
    private lateinit var disclaimer: DisclaimerStore
    private lateinit var microphoneStore: MicrophoneChoiceStore
    private lateinit var audioManager: AudioManager

    private lateinit var disclaimerGroup: View
    private lateinit var mainGroup: View
    private lateinit var statusView: TextView
    private lateinit var routeView: TextView
    private lateinit var latencyView: TextView
    private lateinit var toggleButton: Button
    private lateinit var phoneMicButton: Button
    private lateinit var headsetMicButton: Button
    private lateinit var micHint: TextView
    private var connectingMic = false
    private var pendingBluetooth: BluetoothPending? = null

    private val requestStart = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val micGranted = granted[Manifest.permission.RECORD_AUDIO] ?: hasMicPermission()
        if (!micGranted) {
            showStatus(getString(R.string.status_mic_denied))
            return@registerForActivityResult
        }
        beginListening()
    }

    private val requestBluetooth = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingBluetooth
        pendingBluetooth = null
        if (!granted) {
            ListeningService.micNote = getString(R.string.status_bluetooth_denied)
            renderMics()
            return@registerForActivityResult
        }
        when (pending) {
            BluetoothPending.SwitchHeadset -> applyMic(MicrophoneChoice.Headset)
            BluetoothPending.Start -> beginListening()
            null -> Unit
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshRoute()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        disclaimer = DisclaimerStore(this)
        microphoneStore = MicrophoneChoiceStore(this)
        audioManager = getSystemService(AudioManager::class.java)
        bindViews()
        findViewById<Button>(R.id.acceptButton).setOnClickListener {
            disclaimer.accept()
            showMain()
        }
        toggleButton.setOnClickListener { onToggle() }
        phoneMicButton.setOnClickListener { selectMic(MicrophoneChoice.Phone) }
        headsetMicButton.setOnClickListener { selectMic(MicrophoneChoice.Headset) }
        if (disclaimer.isAccepted()) showMain() else showDisclaimer()
    }

    override fun onStart() {
        super.onStart()
        ListeningService.listener = { renderIfAlive() }
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        if (disclaimer.isAccepted()) render()
    }

    override fun onStop() {
        ListeningService.listener = null
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        super.onStop()
    }

    private fun bindViews() {
        disclaimerGroup = findViewById(R.id.disclaimerGroup)
        mainGroup = findViewById(R.id.mainGroup)
        statusView = findViewById(R.id.statusView)
        routeView = findViewById(R.id.routeView)
        latencyView = findViewById(R.id.latencyView)
        toggleButton = findViewById(R.id.toggleButton)
        phoneMicButton = findViewById(R.id.phoneMicButton)
        headsetMicButton = findViewById(R.id.headsetMicButton)
        micHint = findViewById(R.id.micHint)
    }

    private fun showDisclaimer() {
        disclaimerGroup.visibility = View.VISIBLE
        mainGroup.visibility = View.GONE
    }

    private fun showMain() {
        disclaimerGroup.visibility = View.GONE
        mainGroup.visibility = View.VISIBLE
        render()
    }

    private fun onToggle() {
        if (ListeningService.running) {
            ListeningService.stop(this)
            return
        }
        val needed = permissionsNeeded()
        if (needed.isNotEmpty()) {
            requestStart.launch(needed)
            return
        }
        beginListening()
    }

    private fun beginListening() {
        if (microphoneStore.get() == MicrophoneChoice.Headset && needsBluetoothPermission()) {
            pendingBluetooth = BluetoothPending.Start
            requestBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        connectingMic = microphoneStore.get() == MicrophoneChoice.Headset
        ListeningService.failure = null
        ListeningService.micNote = null
        renderMics()
        try {
            ListeningService.start(this)
        } catch (error: Exception) {
            connectingMic = false
            showStatus(getString(R.string.status_start_failed, error.message.orEmpty()))
            return
        }
        maybeAskBatteryExemption()
    }

    private fun selectMic(choice: MicrophoneChoice) {
        if (choice == MicrophoneChoice.Headset && needsBluetoothPermission()) {
            pendingBluetooth = BluetoothPending.SwitchHeadset
            requestBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        applyMic(choice)
    }

    private fun applyMic(choice: MicrophoneChoice) {
        microphoneStore.set(choice)
        connectingMic = ListeningService.engaged
        ListeningService.micNote = null
        renderMics()
        if (ListeningService.engaged) ListeningService.setMicrophone(this, choice)
    }

    private fun needsBluetoothPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED &&
            audioManager.currentHeadphoneRoute() == HeadphoneRoute.Wireless
    }

    private fun maybeAskBatteryExemption() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        ).setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
    }

    private fun permissionsNeeded(): Array<String> {
        val needed = mutableListOf<String>()
        if (!hasMicPermission()) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        return needed.toTypedArray()
    }

    private fun renderIfAlive() {
        if (isFinishing || !::statusView.isInitialized) return
        if (disclaimerGroup.visibility == View.VISIBLE) return
        connectingMic = false
        render()
    }

    private fun render() {
        if (ListeningService.running) {
            statusView.text = getString(R.string.status_running)
            toggleButton.text = getString(R.string.action_stop)
            latencyView.text = getString(R.string.latency_buffer, ListeningService.outputBufferMs)
        } else {
            val error = ListeningService.failure
            if (error != null) showStatus(error) else showStatus(routeStatus())
            latencyView.text = ""
        }
        refreshRoute()
        renderMics()
    }

    private fun renderMics() {
        if (!::phoneMicButton.isInitialized) return
        val choice = microphoneStore.get()
        phoneMicButton.isSelected = choice == MicrophoneChoice.Phone
        headsetMicButton.isSelected = choice == MicrophoneChoice.Headset
        micHint.text = when {
            connectingMic -> getString(R.string.mic_hint_connecting)
            ListeningService.micNote != null -> ListeningService.micNote
            choice == MicrophoneChoice.Headset -> getString(R.string.mic_hint_headset)
            else -> getString(R.string.mic_hint_phone)
        }
    }

    private fun refreshRoute() {
        if (!::routeView.isInitialized || disclaimerGroup.visibility == View.VISIBLE) return
        val route = audioManager.currentHeadphoneRoute()
        routeView.text = when (route) {
            HeadphoneRoute.Wired -> getString(R.string.route_wired)
            HeadphoneRoute.Wireless -> getString(R.string.route_wireless)
            HeadphoneRoute.None -> getString(R.string.route_none)
        }
        if (!ListeningService.running && ListeningService.failure == null) {
            statusView.text = routeStatus(route)
        }
    }

    private fun routeStatus(route: HeadphoneRoute = audioManager.currentHeadphoneRoute()): String {
        return if (route == HeadphoneRoute.None) {
            getString(R.string.status_need_headphones)
        } else {
            getString(R.string.status_ready)
        }
    }

    private fun showStatus(message: String) {
        statusView.text = message
        toggleButton.text = getString(R.string.action_start)
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private enum class BluetoothPending {
        SwitchHeadset,
        Start,
    }

    private fun hasNotificationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
