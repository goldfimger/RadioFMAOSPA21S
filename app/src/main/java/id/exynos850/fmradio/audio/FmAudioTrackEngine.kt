package id.exynos850.fmradio.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FmAudioTrackEngine(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 48000
        // AudioSource internal Android untuk FM Tuner (MediaRecorder.AudioSource.RADIO_TUNER = 1998)
        private const val AUDIO_SOURCE_RADIO_TUNER = 1998
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var tinycapProc: Process? = null
    private var streamJob: Job? = null
    private var currentVolume = 0.85f
    private var isSpeakerForced = false

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

    @SuppressLint("MissingPermission")
    fun startAudioStream(
        scope: CoroutineScope,
        rootFmPcm: Pair<Int, Int>? = null,
        rootDaemonPath: String = "",
        onError: (String) -> Unit
    ) {
        if (streamJob?.isActive == true) return

        val hasCapturePerm = context.checkSelfPermission(android.Manifest.permission.CAPTURE_AUDIO_OUTPUT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        val minRecordBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val minTrackBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val bufferSize = maxOf(minRecordBuf, minTrackBuf) * 2

        try {
            // AudioTrack berlatensi rendah untuk memutar output PCM FM
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

            audioRecord = runCatching {
                AudioRecord(
                    AUDIO_SOURCE_RADIO_TUNER,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }.getOrNull()

            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                applyRouting()
                runCatching {
                    audioManager.setParameters("fm_mode=on")
                    audioManager.setParameters("fm_radio_mute=0")
                }
                audioTrack?.setVolume(currentVolume)
                audioTrack?.play()
                audioRecord?.startRecording()

                streamJob = scope.launch(Dispatchers.IO) {
                    val pcmBuffer = ShortArray(2048)
                    while (isActive) {
                        val readCount = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: -1
                        if (readCount > 0) {
                            audioTrack?.write(pcmBuffer, 0, readCount, AudioTrack.WRITE_BLOCKING)
                        }
                    }
                }
                return
            }

            // Fallback Root ALSA PCM (liba21s_fm_rootd.so pcm -> AudioTrack) tanpa butuh tinycap
            if (rootFmPcm != null && rootDaemonPath.isNotBlank()) {
                val (card, dev) = rootFmPcm
                val proc = runCatching {
                    ProcessBuilder(
                        "su", "-c",
                        "$rootDaemonPath pcm $card $dev $SAMPLE_RATE 2"
                    ).start()
                }.getOrNull()

                if (proc != null) {
                    tinycapProc = proc
                    applyRouting()
                    runCatching { audioManager.setParameters("fm_mode=on") }
                    audioTrack?.setVolume(currentVolume)
                    audioTrack?.play()

                    streamJob = scope.launch(Dispatchers.IO) {
                        val byteBuf = ByteArray(4096)
                        val input = proc.inputStream
                        var totalWritten = 0L
                        while (isActive) {
                            val readBytes = input.read(byteBuf)
                            if (readBytes <= 0) break
                            audioTrack?.write(byteBuf, 0, readBytes, AudioTrack.WRITE_BLOCKING)
                            totalWritten += readBytes
                        }
                        if (isActive && totalWritten == 0L) {
                            releaseAudioHandles()
                            onError("Node PCM ALSA tidak mengirim data. Tekan 'Cek Audio HAL' lalu 'Salin Log'.")
                        }
                    }
                    return
                }
            }

            // Aktifkan routing HAL FM sebagai fallback dan beri diagnosa izin yang spesifik
            runCatching {
                audioManager.setParameters("fm_mode=on")
                audioManager.setParameters("fm_radio_mute=0")
            }
            releaseAudioHandles()
            val permNote = if (hasCapturePerm) {
                "Izin CAPTURE_AUDIO_OUTPUT sudah AKTIF, tetapi audio_policy LineageOS belum mendaftarkan AUDIO_DEVICE_IN_FM_TUNER."
            } else {
                "Izin CAPTURE_AUDIO_OUTPUT masih DENIED (APK user di /data/app menimpa priv-app atau modul KSU belum termount)."
            }
            onError("Tuner /dev/radio0 aktif! $permNote Tekan 'Cek Audio HAL' lalu 'Salin Log'.")
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
        val targetType = if (isSpeakerForced) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            AudioDeviceInfo.TYPE_WIRED_HEADSET
        }
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == targetType }
        audioTrack?.preferredDevice = device
    }

    private fun releaseAudioHandles() {
        streamJob?.cancel()
        streamJob = null
        runCatching { tinycapProc?.destroy() }
        tinycapProc = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioRecord = null
        audioTrack = null
    }

    fun stopAudioStream() {
        runCatching {
            audioManager.setParameters("fm_radio_mute=1")
            audioManager.setParameters("fm_mode=off")
        }
        releaseAudioHandles()
    }
}
