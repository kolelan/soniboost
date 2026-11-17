package com.soniboost

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.soniboost.audio.HeadphoneRoute
import com.soniboost.audio.MicrophoneChoice
import com.soniboost.audio.MicrophoneChoiceStore
import com.soniboost.audio.PipelineFailure
import com.soniboost.audio.SpeechPipeline
import com.soniboost.audio.StartResult
import com.soniboost.audio.currentHeadphoneRoute
import java.util.concurrent.Executors

/**
 * Держит обработку живой при выключенном экране.
 * Служба переднего плана с типом microphone не даёт системе забрать микрофон,
 * а частичная блокировка сна не даёт процессору уснуть вместе с дисплеем.
 */
class ListeningService : Service() {
    private lateinit var pipeline: SpeechPipeline
    private lateinit var audioManager: AudioManager
    private lateinit var microphoneStore: MicrophoneChoiceStore
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioJobs = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "soniboost-route")
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private var routeWatching = false
    private var stopping = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = watchRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = watchRoute()
    }

    override fun onCreate() {
        super.onCreate()
        pipeline = SpeechPipeline(this)
        audioManager = getSystemService(AudioManager::class.java)
        microphoneStore = MicrophoneChoiceStore(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopProcessing()
            ACTION_SET_MIC -> switchMicrophone(intent)
            else -> startProcessing()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pipeline.onStopped = null
        if (pipeline.isRunning()) pipeline.stop()
        audioJobs.shutdownNow()
        unregisterRouteWatch()
        releaseWakeLock()
        running = false
        publish()
        super.onDestroy()
    }

    private fun startProcessing() {
        stopping = false
        failure = null
        micNote = null
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            foregroundType(),
        )
        engaged = true
        audioJobs.execute {
            pipeline.onStopped = { mainHandler.post { stopProcessing() } }
            val result = pipeline.start(microphoneStore.get())
            mainHandler.post { if (!stopping) applyResult(result) }
        }
    }

    private fun switchMicrophone(intent: Intent) {
        val choice = intent.getStringExtra(EXTRA_MIC)
            ?.let { runCatching { MicrophoneChoice.valueOf(it) }.getOrNull() }
            ?: microphoneStore.get()
        microphoneStore.set(choice)
        audioJobs.execute {
            if (stopping || !pipeline.isRunning()) {
                mainHandler.post { if (!stopping) publish() }
                return@execute
            }
            val result = pipeline.applyMicrophone(choice)
            mainHandler.post {
                if (result is StartResult.Rejected && pipeline.isRunning()) {
                    microphoneStore.set(pipeline.microphone)
                }
                if (!stopping) applyResult(result)
            }
        }
    }

    private fun applyResult(result: StartResult) {
        when (result) {
            StartResult.Cancelled -> Unit
            is StartResult.Started -> {
                if (!pipeline.isRunning()) {
                    publish()
                    return
                }
                running = true
                outputBufferMs = pipeline.outputBufferMs
                failure = null
                micNote = null
                acquireWakeLock()
                registerRouteWatch()
                updateNotification()
                publish()
            }
            is StartResult.Rejected -> {
                if (pipeline.isRunning()) {
                    outputBufferMs = pipeline.outputBufferMs
                    micNote = failureText(result)
                    updateNotification()
                    publish()
                    return
                }
                failure = failureText(result)
                micNote = null
                engaged = false
                running = false
                outputBufferMs = 0
                publish()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopProcessing() {
        if (stopping) return
        stopping = true
        mainHandler.removeCallbacks(confirmUnplugged)
        pipeline.onStopped = null
        pipeline.stop()
        unregisterRouteWatch()
        releaseWakeLock()
        engaged = false
        running = false
        outputBufferMs = 0
        publish()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val confirmUnplugged: Runnable = object : Runnable {
        override fun run() {
            if (stopping) return
            if (pipeline.isSwitching()) {
                mainHandler.postDelayed(this, UNPLUG_CONFIRM_MS)
                return
            }
            if (pipeline.isRunning() && audioManager.currentHeadphoneRoute() == HeadphoneRoute.None) {
                stopProcessing()
            }
        }
    }

    private fun watchRoute() {
        mainHandler.removeCallbacks(confirmUnplugged)
        if (audioManager.currentHeadphoneRoute() != HeadphoneRoute.None) return
        if (!pipeline.isRunning() && !pipeline.isSwitching()) return
        mainHandler.postDelayed(confirmUnplugged, UNPLUG_CONFIRM_MS)
    }

    private fun registerRouteWatch() {
        if (routeWatching) return
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        routeWatching = true
    }

    private fun unregisterRouteWatch() {
        if (!routeWatching) return
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        routeWatching = false
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "soniboost:listening").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun notification(): android.app.Notification {
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ListeningService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notificationText())
            .setContentIntent(content)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notification_stop), stop)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun foregroundType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification())
    }

    private fun notificationText(): String {
        val mic = if (::pipeline.isInitialized && pipeline.isRunning()) {
            when (pipeline.microphone) {
                MicrophoneChoice.Phone -> getString(R.string.notification_mic_phone)
                MicrophoneChoice.Headset -> getString(R.string.notification_mic_headset)
            }
        } else {
            ""
        }
        val listening = getString(R.string.notification_listening)
        return if (mic.isEmpty()) listening else "$listening $mic"
    }

    private fun failureText(result: StartResult.Rejected): String {
        return when (result.failure) {
            PipelineFailure.NoHeadphones -> getString(R.string.status_need_headphones)
            PipelineFailure.FocusDenied -> getString(R.string.status_focus_denied)
            PipelineFailure.HeadsetMicUnavailable -> getString(R.string.status_headset_mic_unavailable)
            PipelineFailure.StartFailed -> getString(R.string.status_start_failed, result.detail.orEmpty())
        }
    }

    companion object {
        private const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 42
        private const val UNPLUG_CONFIRM_MS = 1_500L
        private const val ACTION_STOP = "com.soniboost.action.STOP"
        private const val ACTION_SET_MIC = "com.soniboost.action.SET_MIC"
        private const val EXTRA_MIC = "microphone"

        @Volatile
        var running: Boolean = false

        @Volatile
        var outputBufferMs: Int = 0

        @Volatile
        var failure: String? = null

        @Volatile
        var micNote: String? = null

        @Volatile
        var engaged: Boolean = false

        var listener: (() -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, ListeningService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ListeningService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun setMicrophone(context: Context, choice: MicrophoneChoice) {
            MicrophoneChoiceStore(context).set(choice)
            if (!engaged) {
                publish()
                return
            }
            val intent = Intent(context, ListeningService::class.java)
                .setAction(ACTION_SET_MIC)
                .putExtra(EXTRA_MIC, choice.name)
            context.startService(intent)
        }

        fun publish() {
            val callback = listener ?: return
            callback()
        }
    }
}
