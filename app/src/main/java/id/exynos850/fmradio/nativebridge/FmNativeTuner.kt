package id.exynos850.fmradio.nativebridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

data class HardwareSignalInfo(
    val rssiDbm: Int,
    val qualityPercent: Int,
    val isStereo: Boolean,
    val afcOffsetHz: Int
)

class FmNativeTuner(private val nativeLibDir: String = "") {

    companion object {
        const val MIN_FREQ_KHZ = 87500
        const val MAX_FREQ_KHZ = 108000

        init {
            System.loadLibrary("a21s_fm_jni")
        }
    }

    private var isOpen = false
    private var usingRootDaemon = false
    private var rootProc: Process? = null
    private var rootWriter: BufferedWriter? = null
    private var rootReader: BufferedReader? = null
    private var rootLastError = ""

    @Synchronized
    private fun rootCommand(cmd: String): String {
        val w = rootWriter ?: return "ERR no_writer"
        val r = rootReader ?: return "ERR no_reader"
        return runCatching {
            w.write(cmd)
            w.newLine()
            w.flush()
            r.readLine() ?: "ERR eof"
        }.getOrElse { "ERR ${it.message}" }
    }

    private fun openViaRootDaemon(): Boolean {
        if (nativeLibDir.isBlank()) {
            rootLastError = "nativeLibDir kosong."
            return false
        }
        val binFile = File(nativeLibDir, "liba21s_fm_rootd.so")
        if (!binFile.exists()) {
            rootLastError = "Helper root tidak ditemukan: ${binFile.absolutePath}"
            return false
        }
        return runCatching {
            val proc = ProcessBuilder("su", "-c", binFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            rootProc = proc
            rootWriter = proc.outputStream.bufferedWriter()
            rootReader = proc.inputStream.bufferedReader()

            val resp = rootCommand("OPEN")
            if (resp.startsWith("OK")) {
                usingRootDaemon = true
                isOpen = true
                rootLastError = ""
                true
            } else {
                rootLastError = "Root daemon gagal membuka /dev/radio0: $resp"
                closeRootDaemon()
                false
            }
        }.getOrElse { ex ->
            rootLastError = "Gagal menjalankan su (KSU/Magisk): ${ex.message}"
            closeRootDaemon()
            false
        }
    }

    private fun closeRootDaemon() {
        runCatching { rootWriter?.write("CLOSE\n"); rootWriter?.flush() }
        runCatching { rootWriter?.close() }
        runCatching { rootReader?.close() }
        runCatching { rootProc?.destroy() }
        rootWriter = null
        rootReader = null
        rootProc = null
        usingRootDaemon = false
    }

    fun open(allowRootFallback: Boolean = true): Boolean {
        if (isOpen) return true
        if (nativeOpenTuner()) {
            usingRootDaemon = false
            isOpen = true
            return true
        }
        if (allowRootFallback && openViaRootDaemon()) {
            return true
        }
        isOpen = false
        return false
    }

    fun isUsingRootMode(): Boolean = usingRootDaemon

    fun close() {
        if (!isOpen) return
        if (usingRootDaemon) {
            rootCommand("MUTE 1")
            rootCommand("DISABLE_FM_ALSA")
            closeRootDaemon()
        } else {
            nativeSetMute(true)
            nativeCloseTuner()
        }
        isOpen = false
    }

    fun tuneManual(freqKhz: Int): Boolean {
        val clamped = freqKhz.coerceIn(MIN_FREQ_KHZ, MAX_FREQ_KHZ)
        return if (usingRootDaemon) {
            rootCommand("TUNE $clamped").startsWith("OK")
        } else {
            nativeSetFrequency(clamped)
        }
    }

    fun getCurrentFrequencyKhz(): Int {
        if (!usingRootDaemon) return nativeGetFrequency()
        val parts = rootCommand("GETFREQ").split(" ")
        return if (parts.getOrNull(0) == "OK") parts.getOrNull(1)?.toIntOrNull() ?: -1 else -1
    }

    fun readSignalMetrics(): HardwareSignalInfo {
        if (usingRootDaemon) {
            val parts = rootCommand("SIGNAL").split(" ")
            return if (parts.getOrNull(0) == "OK") {
                HardwareSignalInfo(
                    rssiDbm = parts.getOrNull(1)?.toIntOrNull() ?: -104,
                    qualityPercent = (parts.getOrNull(2)?.toIntOrNull() ?: 0).coerceIn(0, 100),
                    isStereo = parts.getOrNull(3) == "1",
                    afcOffsetHz = parts.getOrNull(4)?.toIntOrNull() ?: 0
                )
            } else {
                HardwareSignalInfo(-104, 0, false, 0)
            }
        }
        val raw = nativeGetSignalInfo()
        return HardwareSignalInfo(
            rssiDbm = raw.getOrElse(0) { -104 },
            qualityPercent = raw.getOrElse(1) { 0 }.coerceIn(0, 100),
            isStereo = raw.getOrElse(2) { 0 } == 1,
            afcOffsetHz = raw.getOrElse(3) { 0 }
        )
    }

    fun setMute(mute: Boolean): Boolean {
        return if (usingRootDaemon) {
            rootCommand("MUTE ${if (mute) 1 else 0}").startsWith("OK")
        } else {
            nativeSetMute(mute)
        }
    }

    fun getLastError(): String = rootLastError.ifBlank { nativeGetLastError() }

    fun getDiagnosticReport(): String = nativeGetDiagnosticReport()

    fun getRootDaemonBinaryPath(): String = File(nativeLibDir, "liba21s_fm_rootd.so").absolutePath

    fun enableFmAudioRoute(): String {
        if (!usingRootDaemon) return "ERR daemon_not_active"
        return rootCommand("ENABLE_FM_ALSA")
    }

    fun disableFmAudioRoute() {
        if (usingRootDaemon) {
            rootCommand("DISABLE_FM_ALSA")
        }
    }

    fun detectRootFmPcm(): Pair<Int, Int>? {
        if (!usingRootDaemon) return null
        val parts = rootCommand("DETECT_FM_PCM").split(" ")
        if (parts.getOrNull(0) == "OK") {
            val card = parts.getOrNull(1)?.toIntOrNull()
            val dev = parts.getOrNull(2)?.toIntOrNull()
            if (card != null && dev != null) return card to dev
        }
        return null
    }

    suspend fun getRootAudioDiagnostic(
        packageName: String
    ): String = withContext(Dispatchers.IO) {
        val alsaCtls = if (usingRootDaemon) rootCommand("LIST_FM_CTLS") else "Daemon root belum aktif"
        val pcmDetect = if (usingRootDaemon) rootCommand("DETECT_FM_PCM") else "-"
        runCatching {
            val script = """
                echo "=== capture PCM Card 0 (/proc/asound/pcm) ==="
                grep -iE "^00-.*capture" /proc/asound/pcm 2>&1 || true
                echo "=== Dumpsys Package ==="
                dumpsys package $packageName 2>/dev/null | grep -E "codePath=|pkgFlags=|ACCESS_FM_RADIO" | head -n 8 || true
            """.trimIndent()
            val proc = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            val combined = "$out\n=== DETECT_FM_PCM ===\n$pcmDetect\n=== ALSA FM/SPDY/WDMA2 Controls ===\n$alsaCtls"
            combined.trim().ifBlank { "Tidak ada output diagnostik root." }
        }.getOrElse { ex ->
            "Gagal membaca diagnostik root: ${ex.message}"
        }
    }

    suspend fun generateAndSaveBugReport(
        packageName: String,
        nativeDiag: String,
        extraStatus: String?
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val targetPath = "/sdcard/A21s_FM_BugReport.txt"
        val alsaCtls = if (usingRootDaemon) {
            rootCommand("LIST_FM_CTLS")
        } else {
            runCatching {
                val bin = getRootDaemonBinaryPath()
                val p = ProcessBuilder("su", "-c", bin).redirectErrorStream(true).start()
                p.outputStream.bufferedWriter().use { w ->
                    w.write("LIST_FM_CTLS\nCLOSE\n")
                    w.flush()
                }
                p.inputStream.bufferedReader().readText().trim()
            }.getOrDefault("Daemon root belum berjalan")
        }
        val pcmDetect = if (usingRootDaemon) rootCommand("DETECT_FM_PCM") else "Daemon root belum berjalan"

        val collected = runCatching {
            val script = """
                echo "=== A21s FM RADIO BUG REPORT ==="
                date
                echo "Model: $(getprop ro.product.model) ($(getprop ro.product.device))"
                echo "Android: $(getprop ro.build.version.release) | SDK: $(getprop ro.build.version.sdk)"
                echo "Kernel: $(uname -a)"
                echo ""
                echo "=== PACKAGE DUMPSYS ==="
                dumpsys package $packageName 2>/dev/null | grep -E "codePath=|pkgFlags=|ACCESS_FM_RADIO" || true
                echo ""
                echo "=== DEV & SYSFS RADIO ==="
                ls -lZ /dev/radio* /dev/fm* /sys/class/video4linux/radio* 2>&1 || true
                echo ""
                echo "=== /proc/asound/cards & pcm (Card 0) ==="
                cat /proc/asound/cards 2>&1 || true
                grep -E "^00-" /proc/asound/pcm 2>&1 || true
                echo ""
                echo "=== LOGCAT (Audio/FM) ==="
                logcat -d -t 120 2>/dev/null | grep -iE "A21sFm|AudioTrack|AudioFlinger|audio_hw|fm_" | tail -n 60 || true
            """.trimIndent()
            val proc = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out
        }.getOrElse { ex ->
            "Root diagnostic fallback error: ${ex.message}"
        }

        val fullReport = buildString {
            appendLine("=== STATUS APLIKASI ===")
            appendLine("Error/Status: ${extraStatus ?: "-"}")
            appendLine("DETECT_FM_PCM: $pcmDetect")
            appendLine()
            appendLine("=== NATIVE DIAGNOSTIC ===")
            appendLine(nativeDiag.ifBlank { "-" })
            appendLine()
            appendLine("=== ALSA CONTROLS (LIST_FM_CTLS) ===")
            appendLine(alsaCtls)
            appendLine()
            append(collected)
        }

        val savedViaRoot = runCatching {
            val writeProc = ProcessBuilder(
                "su", "-c",
                "cat > $targetPath && chmod 0666 $targetPath"
            ).redirectErrorStream(true).start()
            writeProc.outputStream.use { os ->
                os.write(fullReport.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val code = writeProc.waitFor()
            code == 0
        }.getOrDefault(false)

        if (savedViaRoot) {
            return@withContext true to "Bug report berhasil disimpan ke:\n$targetPath"
        }

        val savedDirect = runCatching {
            File(targetPath).writeText(fullReport, Charsets.UTF_8)
            true
        }.getOrDefault(false)

        if (savedDirect) {
            true to "Bug report berhasil disimpan ke:\n$targetPath"
        } else {
            false to "Gagal menulis ke $targetPath (aktifkan izin root di KSU)."
        }
    }

    suspend fun tryUnlockViaRoot(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (openViaRootDaemon()) {
            return@withContext true to "Berhasil membuka /dev/radio0 via Root Daemon (KernelSU/Magisk)."
        }
        runCatching {
            val cmd = """
                ls -lZ /dev/radio* 2>&1
                chmod 0666 /dev/radio0 2>/dev/null || true
                if command -v ksud >/dev/null 2>&1; then
                  ksud sepolicy patch "allow untrusted_app device:chr_file { read write open ioctl getattr }" 2>&1 || true
                elif command -v magiskpolicy >/dev/null 2>&1; then
                  magiskpolicy --live "allow untrusted_app device chr_file { read write open ioctl getattr }" 2>&1 || true
                fi
            """.trimIndent()
            val proc = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            (code == 0) to (rootLastError + "\n" + out).trim()
        }.getOrElse { ex ->
            false to "Root (su) gagal: ${ex.message}"
        }
    }

    suspend fun autoScanStations(
        spacingKhz: Int = 100,
        rssiThresholdDbm: Int = -78,
        onStepProgress: (currentFreqKhz: Int, signal: HardwareSignalInfo) -> Unit
    ): List<Pair<Int, HardwareSignalInfo>> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<Pair<Int, HardwareSignalInfo>>()
        setMute(true)

        var freq = MIN_FREQ_KHZ
        while (freq <= MAX_FREQ_KHZ) {
            tuneManual(freq)
            delay(45)
            val signal = readSignalMetrics()
            onStepProgress(freq, signal)

            if (signal.rssiDbm >= rssiThresholdDbm) {
                discovered.add(freq to signal)
                freq += 200
            } else {
                freq += spacingKhz
            }
        }

        setMute(false)
        discovered
    }

    private external fun nativeOpenTuner(): Boolean
    private external fun nativeCloseTuner()
    private external fun nativeSetFrequency(freqKhz: Int): Boolean
    private external fun nativeGetFrequency(): Int
    private external fun nativeGetSignalInfo(): IntArray
    private external fun nativeSeekFrequency(seekUp: Boolean, spacingKhz: Int): Int
    private external fun nativeSetMute(mute: Boolean): Boolean
    private external fun nativeGetLastError(): String
    private external fun nativeGetDiagnosticReport(): String
}
