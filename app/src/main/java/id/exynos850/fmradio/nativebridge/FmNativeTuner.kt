package id.exynos850.fmradio.nativebridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class HardwareSignalInfo(
    val rssiDbm: Int,
    val qualityPercent: Int,
    val isStereo: Boolean,
    val afcOffsetHz: Int
)

class FmNativeTuner {

    companion object {
        const val MIN_FREQ_KHZ = 87500
        const val MAX_FREQ_KHZ = 108000

        init {
            System.loadLibrary("a21s_fm_jni")
        }
    }

    private var isOpen = false

    fun open(): Boolean {
        isOpen = nativeOpenTuner()
        return isOpen
    }

    fun close() {
        if (isOpen) {
            nativeSetMute(true)
            nativeCloseTuner()
            isOpen = false
        }
    }

    fun tuneManual(freqKhz: Int): Boolean {
        val clamped = freqKhz.coerceIn(MIN_FREQ_KHZ, MAX_FREQ_KHZ)
        return nativeSetFrequency(clamped)
    }

    fun getCurrentFrequencyKhz(): Int = nativeGetFrequency()

    fun readSignalMetrics(): HardwareSignalInfo {
        val raw = nativeGetSignalInfo()
        return HardwareSignalInfo(
            rssiDbm = raw.getOrElse(0) { -104 },
            qualityPercent = raw.getOrElse(1) { 0 }.coerceIn(0, 100),
            isStereo = raw.getOrElse(2) { 0 } == 1,
            afcOffsetHz = raw.getOrElse(3) { 0 }
        )
    }

    fun setMute(mute: Boolean): Boolean = nativeSetMute(mute)

    /**
     * Melakukan pencarian otomatis di spektrum FM.
     * Jika ioctl VIDIOC_S_HW_FREQ_SEEK tidak didukung kernel, otomatis melakukan sweep software berbasis RSSI.
     */
    suspend fun autoScanStations(
        spacingKhz: Int = 100,
        rssiThresholdDbm: Int = -78,
        onStepProgress: (currentFreqKhz: Int, signal: HardwareSignalInfo) -> Unit
    ): List<Pair<Int, HardwareSignalInfo>> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<Pair<Int, HardwareSignalInfo>>()
        nativeSetMute(true)

        var freq = MIN_FREQ_KHZ
        while (freq <= MAX_FREQ_KHZ) {
            nativeSetFrequency(freq)
            delay(45) // Settling time untuk PLL synthesizer FM Exynos 850
            val signal = readSignalMetrics()
            onStepProgress(freq, signal)

            if (signal.rssiDbm >= rssiThresholdDbm) {
                discovered.add(freq to signal)
                // Lompati bandwidth kanal (200 kHz) untuk menghindari duplikat carrier terdekat
                freq += 200
            } else {
                freq += spacingKhz
            }
        }

        nativeSetMute(false)
        discovered
    }

    private external fun nativeOpenTuner(): Boolean
    private external fun nativeCloseTuner()
    private external fun nativeSetFrequency(freqKhz: Int): Boolean
    private external fun nativeGetFrequency(): Int
    private external fun nativeGetSignalInfo(): IntArray
    private external fun nativeSeekFrequency(seekUp: Boolean, spacingKhz: Int): Int
    private external fun nativeSetMute(mute: Boolean): Boolean
}
