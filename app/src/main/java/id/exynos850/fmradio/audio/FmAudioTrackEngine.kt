package id.exynos850.fmradio.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class FmAudioTrackEngine(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 2
        private const val BYTES_PER_FRAME = CHANNELS * 2
        private const val READ_CHUNK_BYTES = 3840
        private const val SILENT_WINDOW_BYTES = SAMPLE_RATE * BYTES_PER_FRAME * 2L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val stateLock = Any()
    private val streamGeneration = AtomicLong(0L)
    private val stopRequested = AtomicBoolean(false)

    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var pcmStreamProc: Process? = null
    @Volatile private var pcmInputStream: InputStream? = null
    @Volatile private var streamJob: Job? = null
    @Volatile private var currentVolume = 0.85f
    @Volatile private var isSpeakerForced = false
    @Volatile var lastStreamStats: String = "idle"
        private set

    fun hasWiredHeadsetAntenna(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    fun registerAntennaCallback(onHeadsetChanged: (connected: Boolean) -> Unit): AudioDeviceCallback {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                onHeadsetChanged(hasWiredHeadsetAntenna())
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                onHeadsetChanged(hasWiredHeadsetAntenna())
            }
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        return callback
    }

    fun unregisterAntennaCallback(callback: AudioDeviceCallback) {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    fun startAudioStream(
        scope: CoroutineScope,
        rootFmPcm: Pair<Int, Int>? = null,
        rootDaemonPath: String = "",
        alsaRouteStatus: String = "",
        onLog: (String) -> Unit = {},
        onError: (String) -> Unit
    ) {
        if (rootFmPcm == null || rootDaemonPath.isBlank()) {
            stopAudioStream("missing_pcm_or_daemon")
            onError("Node capture PCM FM Card 0 tidak tersedia. Route: $alsaRouteStatus. Tekan 'Cek Audio HAL' atau 'Bug Report (.txt)'.")
            return
        }

        val (card, dev) = rootFmPcm
        val myGen = streamGeneration.incrementAndGet()
        stopRequested.set(false)

        val previousJob: Job?
        synchronized(stateLock) {
            previousJob = streamJob
            closeProcessAndStreamLocked()
            releaseAudioTrackLocked()
        }
        previousJob?.cancel()

        lastStreamStats = "initializing pcmC${card}D${dev}c gen=$myGen"
        onLog("AudioEngine start requested card=$card dev=$dev gen=$myGen route=$alsaRouteStatus")

        val job = scope.launch(Dispatchers.IO) {
            if (previousJob != null && previousJob.isActive) {
                onLog("AudioEngine waiting previous job cleanup before gen=$myGen")
                try {
                    previousJob.cancelAndJoin()
                } catch (ex: Exception) {
                    onLog("Previous job cancelAndJoin note: ${ex.message}")
                }
            }

            if (stopRequested.get() || myGen != streamGeneration.get() || !isActive) {
                onLog("AudioEngine start aborted before launch (gen=$myGen)")
                return@launch
            }

            var localTrack: AudioTrack? = null
            var localProc: Process? = null
            var localInput: InputStream? = null
            var errReaderJob: Job? = null
            val errBuf = StringBuilder()

            var totalBytesRead = 0L
            var totalFramesRead = 0L
            var totalBytesWritten = 0L
            var peakAbs = 0
            var nonZeroSamples = 0L
            var writeErr = 0
            var playbackStarted = false
            var unexpectedIoError: IOException? = null
            var silentStreamDetected = false

            try {
                val minTrackBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)
                val bufferSize = minTrackBuf * 2

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                localTrack = track
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioTrack gagal inisialisasi (state=${track.state}, buf=$bufferSize)")
                }

                var aborted = false
                synchronized(stateLock) {
                    if (stopRequested.get() || myGen != streamGeneration.get()) {
                        aborted = true
                    } else {
                        audioTrack = track
                        applyRoutingLocked(track)
                        track.setVolume(currentVolume)
                    }
                }
                if (aborted) return@launch

                onLog("AudioTrack ready rate=$SAMPLE_RATE ch=$CHANNELS fmt=PCM_16BIT minBuf=$minTrackBuf bufBytes=$bufferSize state=${track.state}")

                val proc = ProcessBuilder(
                    "su", "-c",
                    "$rootDaemonPath pcm $card $dev $SAMPLE_RATE $CHANNELS"
                ).start()
                val input = proc.inputStream
                localProc = proc
                localInput = input

                synchronized(stateLock) {
                    if (stopRequested.get() || myGen != streamGeneration.get()) {
                        aborted = true
                    } else {
                        pcmStreamProc = proc
                        pcmInputStream = input
                    }
                }
                if (aborted) return@launch

                errReaderJob = launch(Dispatchers.IO) {
                    try {
                        proc.errorStream.bufferedReader().use { reader ->
                            while (isActive) {
                                val line = reader.readLine() ?: break
                                if (errBuf.length < 3072) errBuf.appendLine(line)
                                if (line.startsWith("ACTIVE_PCM")) {
                                    onLog("Daemon $line")
                                }
                            }
                        }
                    } catch (ioe: IOException) {
                        val intentional = stopRequested.get() || myGen != streamGeneration.get() || !isActive
                        if (!intentional) {
                            onLog("Daemon stderr read error: ${ioe.message}")
                        }
                    }
                }

                val byteBuf = ByteArray(READ_CHUNK_BYTES)
                lastStreamStats = "reading pcmC${card}D${dev}c"

                while (isActive && !stopRequested.get() && myGen == streamGeneration.get()) {
                    val readBytes = try {
                        input.read(byteBuf)
                    } catch (ioe: IOException) {
                        val intentional = stopRequested.get() || myGen != streamGeneration.get() || !isActive
                        if (intentional) {
                            onLog("AudioEngine read closed cleanly on Stop (${ioe.javaClass.simpleName})")
                        } else {
                            unexpectedIoError = ioe
                            onLog("AudioEngine unexpected read error: ${ioe.javaClass.simpleName}: ${ioe.message}")
                        }
                        break
                    }

                    if (readBytes < 0) break
                    if (readBytes == 0) continue

                    totalBytesRead += readBytes
                    totalFramesRead = totalBytesRead / BYTES_PER_FRAME

                    var i = 0
                    while (i + 1 < readBytes) {
                        val lo = byteBuf[i].toInt() and 0xFF
                        val hi = byteBuf[i + 1].toInt()
                        val sample = ((hi shl 8) or lo).toShort().toInt()
                        val absVal = if (sample < 0) -sample else sample
                        if (absVal > peakAbs) peakAbs = absVal
                        if (absVal > 0) nonZeroSamples++
                        i += 2
                    }

                    if (nonZeroSamples == 0L && totalBytesRead >= SILENT_WINDOW_BYTES) {
                        silentStreamDetected = true
                        onLog("AudioEngine SILENT_PCM_DETECTED frames=$totalFramesRead bytes=$totalBytesRead peak=0 nz=0")
                        break
                    }

                    if (!playbackStarted && nonZeroSamples > 0L) {
                        track.play()
                        playbackStarted = true
                        onLog("AudioTrack play() active playState=${track.playState} firstBytes=$readBytes peak=$peakAbs nz=$nonZeroSamples")
                    }

                    if (playbackStarted) {
                        val w = track.write(byteBuf, 0, readBytes, AudioTrack.WRITE_BLOCKING)
                        if (w < 0) {
                            writeErr = w
                            onLog("AudioTrack write error code=$w")
                            break
                        }
                        totalBytesWritten += w
                    }

                    lastStreamStats = "streaming frames=$totalFramesRead written=$totalBytesWritten peak=$peakAbs nz=$nonZeroSamples playState=${track.playState}"
                }
            } catch (ex: Exception) {
                if (!stopRequested.get() && myGen == streamGeneration.get()) {
                    unexpectedIoError = if (ex is IOException) ex else IOException(ex.message ?: "Stream error", ex)
                    onLog("AudioEngine exception: ${ex.javaClass.simpleName}: ${ex.message}")
                }
            } finally {
                val isIntentionalStop = stopRequested.get() || myGen != streamGeneration.get() || !isActive

                try {
                    localInput?.close()
                } catch (_: IOException) {
                }
                try {
                    localProc?.destroy()
                } catch (ex: Exception) {
                    onLog("Process destroy warning: ${ex.message}")
                }
                try {
                    errReaderJob?.cancel()
                } catch (_: Exception) {
                }

                val exitCode = try {
                    localProc?.waitFor() ?: -1
                } catch (_: Exception) {
                    -1
                }

                try {
                    localTrack?.pause()
                    localTrack?.flush()
                    localTrack?.release()
                } catch (ex: Exception) {
                    onLog("AudioTrack release warning: ${ex.message}")
                }

                synchronized(stateLock) {
                    if (pcmInputStream === localInput) pcmInputStream = null
                    if (pcmStreamProc === localProc) pcmStreamProc = null
                    if (audioTrack === localTrack) audioTrack = null
                }

                val stderrText = errBuf.toString().trim().ifBlank { if (isIntentionalStop) "stopped" else "EOF" }
                lastStreamStats = "ended gen=$myGen frames=$totalFramesRead written=$totalBytesWritten peak=$peakAbs nz=$nonZeroSamples writeErr=$writeErr exit=$exitCode ($stderrText)"
                onLog("AudioEngine cleanup $lastStreamStats intentionalStop=$isIntentionalStop")

                if (!isIntentionalStop) {
                    val reasonMsg = when {
                        silentStreamDetected ->
                            "PCM FM hanya menghasilkan data senyap/nol (frames=$totalFramesRead, peak=0, nz=0). $stderrText | Route: $alsaRouteStatus"
                        unexpectedIoError != null ->
                            "Kegagalan stream PCM: ${unexpectedIoError?.message} (frames=$totalFramesRead, peak=$peakAbs, $stderrText) | Route: $alsaRouteStatus"
                        writeErr < 0 ->
                            "AudioTrack write gagal (err=$writeErr, frames=$totalFramesRead, peak=$peakAbs)"
                        else ->
                            "Stream ALSA tidak menghasilkan audio valid (frames=$totalFramesRead, peak=$peakAbs, nz=$nonZeroSamples, exit=$exitCode, $stderrText) | Route: $alsaRouteStatus"
                    }
                    withContext(Dispatchers.Main) {
                        onError(reasonMsg)
                    }
                }
            }
        }

        synchronized(stateLock) {
            if (myGen == streamGeneration.get() && !stopRequested.get()) {
                streamJob = job
            } else {
                job.cancel()
            }
        }
    }

    fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        synchronized(stateLock) {
            audioTrack?.setVolume(currentVolume)
        }
    }

    fun setSpeakerOutput(useSpeaker: Boolean) {
        isSpeakerForced = useSpeaker
        synchronized(stateLock) {
            audioTrack?.let { applyRoutingLocked(it) }
        }
    }

    private fun applyRoutingLocked(track: AudioTrack) {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val device = if (isSpeakerForced) {
            outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } else {
            outputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        }
        track.preferredDevice = device
    }

    private fun closeProcessAndStreamLocked() {
        try {
            pcmInputStream?.close()
        } catch (_: IOException) {
        }
        pcmInputStream = null
        try {
            pcmStreamProc?.destroy()
        } catch (_: Exception) {
        }
        pcmStreamProc = null
    }

    private fun releaseAudioTrackLocked() {
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            try {
                track.pause()
                track.flush()
                track.release()
            } catch (_: Exception) {
            }
        }
    }

    fun stopAudioStream(reason: String = "user_stop") {
        stopRequested.set(true)
        streamGeneration.incrementAndGet()
        val jobToCancel: Job?
        synchronized(stateLock) {
            jobToCancel = streamJob
            streamJob = null
            closeProcessAndStreamLocked()
            releaseAudioTrackLocked()
        }
        jobToCancel?.cancel()
        lastStreamStats = "stopped ($reason)"
    }
}
