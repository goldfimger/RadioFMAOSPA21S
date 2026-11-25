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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FmAudioTrackEngine(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 48000
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioTrack: AudioTrack? = null
    private var pcmStreamProc: Process? = null
    private var streamJob: Job? = null
    private var currentVolume = 0.85f
    private var isSpeakerForced = false
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
        if (streamJob?.isActive == true) return

        val minTrackBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val bufferSize = minTrackBuf * 2

        try {
            // AudioTrack berlatensi rendah untuk memutar output PCM FM langsung (tanpa AudioRecord/recording)
            audioTrack = AudioTrack.Builder()
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

            if (rootFmPcm != null && rootDaemonPath.isNotBlank()) {
                val (card, dev) = rootFmPcm
                val proc = runCatching {
                    ProcessBuilder(
                        "su", "-c",
                        "$rootDaemonPath pcm $card $dev $SAMPLE_RATE 2"
                    ).start()
                }.getOrNull()

                if (proc != null) {
                    pcmStreamProc = proc
                    applyRouting()
                    audioTrack?.setVolume(currentVolume)
                    lastStreamStats = "starting pcmC${card}D${dev}c"
                    onLog("AudioEngine start pcmC${card}D${dev}c route=$alsaRouteStatus")

                    streamJob = scope.launch(Dispatchers.IO) {
                        val errBuf = StringBuilder()
                        val errReaderJob = launch(Dispatchers.IO) {
                            runCatching {
                                proc.errorStream.bufferedReader().useLines { lines ->
                                    lines.forEach { line ->
                                        if (errBuf.length < 2048) errBuf.appendLine(line)
                                    }
                                }
                            }
                        }
                        val byteBuf = ByteArray(3840)
                        val input = proc.inputStream
                        var totalWritten = 0L
                        var peakAbs = 0
                        var writeErr = 0
                        var playbackStarted = false
                        while (isActive) {
                            val readBytes = input.read(byteBuf)
                            if (readBytes <= 0) break
                            if (!playbackStarted) {
                                audioTrack?.play()
                                playbackStarted = true
                                onLog("AudioTrack play() started firstBytes=$readBytes")
                            }
                            var i = 0
                            while (i + 1 < readBytes) {
                                val lo = byteBuf[i].toInt() and 0xFF
                                val hi = byteBuf[i + 1].toInt()
                                val sample = (hi shl 8) or lo
                                val a = if (sample < 0) -sample else sample
                                if (a > peakAbs) peakAbs = a
                                i += 2
                            }
                            val w = audioTrack?.write(byteBuf, 0, readBytes, AudioTrack.WRITE_BLOCKING) ?: -1
                            if (w < 0) {
                                writeErr = w
                                break
                            }
                            totalWritten += w
                            lastStreamStats = "streaming bytes=$totalWritten peak=$peakAbs"
                        }
                        errReaderJob.join()
                        val exitCode = runCatching { proc.waitFor() }.getOrDefault(-1)
                        val stderrText = errBuf.toString().trim().ifBlank { "EOF" }
                        lastStreamStats = "ended bytes=$totalWritten peak=$peakAbs writeErr=$writeErr exit=$exitCode ($stderrText)"
                        onLog("AudioEngine $lastStreamStats")
                        if (isActive) {
                            releaseAudioHandles()
                            withContext(Dispatchers.Main) {
                                onError("Stream ALSA berhenti (bytes=$totalWritten, peak=$peakAbs, writeErr=$writeErr, $stderrText) | Route: $alsaRouteStatus")
                            }
                        }
                    }
                    return
                }
            }

            releaseAudioHandles()
            onError("Node capture ALSA WDMA2 (00-14) belum terbuka. Route: $alsaRouteStatus. Tekan 'Cek Audio HAL' atau 'Bug Report (.txt)'.")
        } catch (ex: Exception) {
            releaseAudioHandles()
            onError(ex.message ?: "Gagal memulai AudioTrack stream")
        }
    }

    fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        audioTrack?.setVolume(currentVolume)
    }

    fun setSpeakerOutput(useSpeaker: Boolean) {
        isSpeakerForced = useSpeaker
        applyRouting()
    }

    private fun applyRouting() {
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
        audioTrack?.preferredDevice = device
    }

    private fun releaseAudioHandles() {
        streamJob?.cancel()
        streamJob = null
        runCatching { pcmStreamProc?.destroy() }
        pcmStreamProc = null
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    fun stopAudioStream() {
        releaseAudioHandles()
    }
}
