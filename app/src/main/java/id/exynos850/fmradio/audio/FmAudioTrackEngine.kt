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
    fun startAudioStream(scope: CoroutineScope, onError: (String) -> Unit) {
        if (streamJob?.isActive == true) return

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

            audioRecord = AudioRecord(
                AUDIO_SOURCE_RADIO_TUNER,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError("AudioRecord RADIO_TUNER gagal diinisialisasi. Pastikan permission CAPTURE_AUDIO_OUTPUT & audio_policy A21s aktif.")
                stopAudioStream()
                return
            }

            applyRouting()
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
        } catch (ex: Exception) {
            onError(ex.message ?: "Gagal memulai AudioTrack stream")
            stopAudioStream()
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

    fun stopAudioStream() {
        streamJob?.cancel()
        streamJob = null
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
}
