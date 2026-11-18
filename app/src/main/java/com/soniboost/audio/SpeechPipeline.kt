package com.soniboost.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.audiofx.DynamicsProcessing
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.atomic.AtomicInteger

enum class PipelineFailure {
    NoHeadphones,
    FocusDenied,
    HeadsetMicUnavailable,
    StartFailed,
}

sealed interface StartResult {
    data class Started(val outputBufferMs: Int) : StartResult
    data class Rejected(val failure: PipelineFailure, val detail: String? = null) : StartResult
    data object Cancelled : StartResult
}

/**
 * Микрофон → точные полосы → перенос высоких частот → адаптивный буст → графический эквалайзер → лимитер → наушники.
 * Лимитер системный и вешается на сессию [AudioTrack]: эксклюзивный Oboe часто обходит этот путь.
 */
class SpeechPipeline(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val captureRoute = CaptureRoute(appContext, audioManager)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val epoch = AtomicInteger(0)

    @Volatile
    private var running = false

    @Volatile
    private var capturing = false

    @Volatile
    private var switching = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var effect: DynamicsProcessing? = null
    private var sampleRateHz = 0
    private var graphicGains = EqCurves.mild().graphic
    private var toneBands = EqCurves.mild().tones
    private val toneFilters = Array(EqCurve.TONE_COUNT) { Biquad() }
    private val lowerer = FrequencyLowerer()
    private val booster = AdaptiveBooster()
    private var loweringSettings = LoweringSettings()
    private var loweringAllowed = true
    private var adaptiveSettings = AdaptiveSettings()
    private var adaptiveAllowed = true
    private var adaptivePace = 1f
    private var focusRequest: AudioFocusRequest? = null

    var microphone: MicrophoneChoice = MicrophoneChoice.Phone
        private set

    var outputBufferMs: Int = 0
        private set

    var onStopped: (() -> Unit)? = null

    fun isRunning(): Boolean = running

    fun isSwitching(): Boolean = switching

    fun start(choice: MicrophoneChoice): StartResult {
        val epochAtStart = epoch.get()
        if (audioManager.currentHeadphoneRoute() == HeadphoneRoute.None) {
            return StartResult.Rejected(PipelineFailure.NoHeadphones)
        }
        val prepared = try {
            prepareRoute(choice) { epoch.get() != epochAtStart } ?: return StartResult.Cancelled
        } catch (error: Exception) {
            return StartResult.Rejected(failureOf(error), error.message)
        }
        synchronized(lock) {
            if (epoch.get() != epochAtStart) {
                captureRoute.release()
                return StartResult.Cancelled
            }
            return try {
                openGraph(prepared)
                microphone = choice
                running = true
                capturing = true
                thread = Thread(::captureLoop, "soniboost-audio").also {
                    it.priority = Thread.MAX_PRIORITY
                    it.start()
                }
                StartResult.Started(outputBufferMs)
            } catch (error: Exception) {
                releaseGraph()
                running = false
                StartResult.Rejected(failureOf(error), error.message)
            }
        }
    }

    fun applyMicrophone(choice: MicrophoneChoice): StartResult {
        if (!running) {
            microphone = choice
            return StartResult.Started(outputBufferMs)
        }
        switching = true
        try {
            val previous = microphone
            val switched = rebuild(choice)
            if (switched is StartResult.Started || switched is StartResult.Cancelled) return switched
            if (!running) return switched
            val rolled = rebuild(previous)
            if (rolled is StartResult.Started || rolled is StartResult.Cancelled) {
                return if (rolled is StartResult.Started) switched else rolled
            }
            running = false
            capturing = false
            releaseGraph()
            return rolled
        } finally {
            switching = false
        }
    }

    fun stop() {
        epoch.incrementAndGet()
        val worker: Thread?
        val shouldNotify: Boolean
        synchronized(lock) {
            shouldNotify = running || thread != null
            running = false
            capturing = false
            worker = thread
            stopRecorder()
        }
        if (worker != null && Thread.currentThread() != worker) {
            worker.join(1_000)
        }
        synchronized(lock) {
            if (thread === worker) thread = null
            releaseGraph()
        }
        if (shouldNotify) mainHandler.post { onStopped?.invoke() }
    }

    private fun rebuild(choice: MicrophoneChoice): StartResult {
        val epochAtStart = epoch.get()
        pauseCapture()
        if (epoch.get() != epochAtStart || !running) return StartResult.Cancelled
        val prepared = try {
            prepareRoute(choice) { epoch.get() != epochAtStart } ?: return StartResult.Cancelled
        } catch (error: Exception) {
            return StartResult.Rejected(failureOf(error), error.message)
        }
        synchronized(lock) {
            if (epoch.get() != epochAtStart || !running) {
                captureRoute.release()
                return StartResult.Cancelled
            }
            return try {
                closeDevices()
                openGraph(prepared)
                microphone = choice
                capturing = true
                thread = Thread(::captureLoop, "soniboost-audio").also {
                    it.priority = Thread.MAX_PRIORITY
                    it.start()
                }
                StartResult.Started(outputBufferMs)
            } catch (error: Exception) {
                closeDevices()
                captureRoute.release()
                StartResult.Rejected(failureOf(error), error.message)
            }
        }
    }

    private fun prepareRoute(
        choice: MicrophoneChoice,
        aborted: () -> Boolean,
    ): CaptureRoute.PreparedCapture? {
        return try {
            captureRoute.prepare(choice, aborted)
        } catch (_: CaptureRoute.RouteAborted) {
            captureRoute.release()
            null
        } catch (error: Exception) {
            captureRoute.release()
            throw error
        }
    }

    private fun failureOf(error: Exception): PipelineFailure {
        return when (error) {
            is HeadsetMicUnavailable -> PipelineFailure.HeadsetMicUnavailable
            is FocusDeniedException -> PipelineFailure.FocusDenied
            else -> PipelineFailure.StartFailed
        }
    }

    private fun pauseCapture() {
        val worker: Thread?
        synchronized(lock) {
            capturing = false
            worker = thread
            stopRecorder()
            runCatching { track?.pause() }
        }
        if (worker != null && Thread.currentThread() != worker) {
            worker.join(1_000)
        }
        synchronized(lock) {
            if (thread === worker) thread = null
        }
    }

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ShortArray(CAPTURE_FRAMES)
        while (capturing) {
            val recorder = record ?: break
            val player = track ?: break
            val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (!capturing) break
            if (read < 0) break
            if (read == 0) continue
            shape(buffer, read)
            lowerer.process(buffer, read)
            booster.process(buffer, read)
            val written = player.write(buffer, 0, read, AudioTrack.WRITE_BLOCKING)
            if (written < 0) break
        }
        if (capturing && running) stop()
    }

    private fun openGraph(prepared: CaptureRoute.PreparedCapture) {
        val sampleRate = chooseSampleRate(prepared.device)
        val channelIn = AudioFormat.CHANNEL_IN_MONO
        val channelOut = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minRecord = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)
        val minTrack = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding)
        if (minRecord <= 0 || minTrack <= 0) error("Нет поддерживаемой частоты дискретизации")
        val burstFrames = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()
            ?: 192
        val recordBytes = maxOf(minRecord, burstFrames * BYTES_PER_FRAME * 2)
        val trackBytes = maxOf(minTrack, burstFrames * BYTES_PER_FRAME * 2)
        val attributes = playbackAttributes(prepared.voicePlayback)

        track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(channelOut)
                    .build()
            )
            .setBufferSizeInBytes(trackBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        val player = track ?: error("AudioTrack не создан")
        prepared.playbackDevice?.let { player.setPreferredDevice(it) }
        outputBufferMs = player.bufferSizeInFrames * 1_000 / sampleRate
        sampleRateHz = sampleRate
        lowerer.configure(loweringSettings, loweringAllowed, sampleRate)
        booster.configure(adaptiveSettings, adaptiveAllowed, adaptivePace, sampleRate)
        pushTones()
        effect = speechEffect(player.audioSessionId)
        record = openMicrophone(prepared, sampleRate, encoding, channelIn, recordBytes)
        if (!requestFocus(attributes)) throw FocusDeniedException()
        player.play()
        record?.startRecording()
    }

    private fun openMicrophone(
        prepared: CaptureRoute.PreparedCapture,
        sampleRate: Int,
        encoding: Int,
        channelMask: Int,
        bufferBytes: Int,
    ): AudioRecord {
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(encoding)
            .setChannelMask(channelMask)
            .build()
        val recorder = AudioRecord.Builder()
            .setAudioSource(prepared.source)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("Микрофон не инициализировался")
        }
        val device = prepared.device
        if (device != null && !recorder.setPreferredDevice(device) && prepared.requireDevice) {
            recorder.release()
            throw HeadsetMicUnavailable()
        }
        return recorder
    }

    private fun chooseSampleRate(device: AudioDeviceInfo?): Int {
        val supported = device?.sampleRates?.filter { it > 0 }?.toSet().orEmpty()
        val native = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        val candidates = listOfNotNull(native, 48_000, 44_100, 16_000, 8_000).distinct()
        return candidates.firstOrNull { rate ->
            (supported.isEmpty() || rate in supported) &&
                AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0 &&
                AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
        } ?: error("Нет поддерживаемой частоты дискретизации")
    }

    private fun speechEffect(sessionId: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            1,
            true,
            EqCurve.BAND_COUNT,
            false,
            0,
            false,
            0,
            true,
        ).build()
        val processing = DynamicsProcessing(0, sessionId, config)
        processing.setPreEqAllChannelsTo(graphicEq())
        processing.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                true,
                true,
                0,
                LIMITER_ATTACK_MS,
                LIMITER_RELEASE_MS,
                LIMITER_RATIO,
                LIMITER_THRESHOLD_DB,
                0f,
            )
        )
        processing.enabled = true
        return processing
    }

    fun applyLowering(settings: LoweringSettings, allowed: Boolean) {
        loweringSettings = settings
        loweringAllowed = allowed
        lowerer.configure(settings, allowed, sampleRateHz)
    }

    fun applyAdaptive(settings: AdaptiveSettings, allowed: Boolean, pace: Float) {
        adaptiveSettings = settings
        adaptiveAllowed = allowed
        adaptivePace = pace
        booster.configure(settings, allowed, pace, sampleRateHz)
    }

    fun updateCurve(curve: EqCurve) {
        graphicGains = curve.graphic
        toneBands = curve.tones
        effect?.let { it.setPreEqAllChannelsTo(graphicEq()) }
        if (sampleRateHz > 0) pushTones()
    }

    private fun shape(buffer: ShortArray, count: Int) {
        for (index in 0 until count) {
            var sample = buffer[index].toFloat()
            sample = toneFilters[0].process(sample)
            sample = toneFilters[1].process(sample)
            sample = toneFilters[2].process(sample)
            buffer[index] = sample.coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }

    private fun pushTones() {
        toneBands.forEachIndexed { index, band ->
            toneFilters[index].coeffs = band.coefficients(sampleRateHz)
        }
    }

    private fun graphicEq(): DynamicsProcessing.Eq {
        val eq = DynamicsProcessing.Eq(true, true, EqCurve.BAND_COUNT)
        for (index in EqCurve.BAND_COUNT - 1 downTo 0) {
            eq.setBand(
                index,
                DynamicsProcessing.EqBand(true, EqCurve.CUTOFFS[index], graphicGains[index].toFloat()),
            )
        }
        return eq
    }

    private fun requestFocus(attributes: AudioAttributes): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                if (switching || change != AudioManager.AUDIOFOCUS_LOSS) return@setOnAudioFocusChangeListener
                mainHandler.post { stop() }
            }, mainHandler)
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun playbackAttributes(voicePlayback: Boolean): AudioAttributes {
        val usage = if (voicePlayback) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private fun stopRecorder() {
        try {
            if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record?.stop()
            }
        } catch (_: IllegalStateException) {
        }
    }

    private fun closeDevices() {
        effect?.release()
        effect = null
        record?.release()
        record = null
        track?.let { player ->
            if (player.playState == AudioTrack.PLAYSTATE_PLAYING) {
                player.pause()
                player.flush()
                player.stop()
            }
            player.release()
        }
        track = null
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun releaseGraph() {
        closeDevices()
        captureRoute.release()
    }

    private class FocusDeniedException : Exception()

    private companion object {
        const val CAPTURE_FRAMES = 256
        const val BYTES_PER_FRAME = 2
        const val LIMITER_ATTACK_MS = 5f
        const val LIMITER_RELEASE_MS = 60f
        const val LIMITER_RATIO = 20f
        const val LIMITER_THRESHOLD_DB = -6f
    }
}
