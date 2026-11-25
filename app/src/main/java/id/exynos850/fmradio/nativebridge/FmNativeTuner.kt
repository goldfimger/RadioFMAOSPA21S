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

    private val daemonLock = Any()
    @Volatile private var isOpen = false
    @Volatile private var usingRootDaemon = false
    @Volatile private var rootProc: Process? = null
    @Volatile private var rootWriter: BufferedWriter? = null
    @Volatile private var rootReader: BufferedReader? = null
    @Volatile private var rootLastError = ""
    private val eventLog = ArrayDeque<String>()

    @Synchronized
    fun logEvent(msg: String) {
        android.util.Log.i("A21sFm", msg)
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        eventLog.addLast("$ts $msg")
        while (eventLog.size > 60) eventLog.removeFirst()
    }

    @Synchronized
    fun getEventLogText(): String = if (eventLog.isEmpty()) "-" else eventLog.joinToString("\n")

    private fun rootCommand(cmd: String): String = synchronized(daemonLock) {
        val w = rootWriter ?: return "ERR no_writer"
        val r = rootReader ?: return "ERR no_reader"
        return try {
            w.write(cmd)
            w.newLine()
            w.flush()
            r.readLine() ?: "ERR eof"
        } catch (ex: Exception) {
            logEvent("rootCommand($cmd) failed: ${ex.javaClass.simpleName}: ${ex.message}")
            "ERR ${ex.message}"
        }
    }

    private fun openViaRootDaemon(): Boolean = synchronized(daemonLock) {
        if (nativeLibDir.isBlank()) {
            rootLastError = "nativeLibDir kosong."
            return false
        }
        val binFile = File(nativeLibDir, "liba21s_fm_rootd.so")
        if (!binFile.exists()) {
            rootLastError = "Helper root tidak ditemukan: ${binFile.absolutePath}"
            return false
        }
        closeRootDaemonLocked()
        return try {
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
                closeRootDaemonLocked()
                false
            }
        } catch (ex: Exception) {
            rootLastError = "Gagal menjalankan su (KSU/Magisk): ${ex.message}"
            logEvent("openViaRootDaemon error: ${ex.message}")
            closeRootDaemonLocked()
            false
        }
    }

    private fun closeRootDaemonLocked() {
        try {
            rootWriter?.write("CLOSE\n")
            rootWriter?.flush()
        } catch (ex: Exception) {
            logEvent("rootDaemon CLOSE flush warning: ${ex.message}")
        }
        try {
            rootWriter?.close()
        } catch (_: Exception) {
            // Stream already closed by child exit
        }
        try {
            rootReader?.close()
        } catch (_: Exception) {
            // Stream already closed by child exit
        }
        try {
            rootProc?.destroy()
        } catch (ex: Exception) {
            logEvent("rootProc destroy warning: ${ex.message}")
        }
        rootWriter = null
        rootReader = null
        rootProc = null
        usingRootDaemon = false
    }

    fun open(allowRootFallback: Boolean = true): Boolean = synchronized(daemonLock) {
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

    fun close() = synchronized(daemonLock) {
        if (!isOpen && rootProc == null) return
        if (usingRootDaemon) {
            rootCommand("MUTE 1")
            rootCommand("DISABLE_FM_ALSA")
            closeRootDaemonLocked()
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

    fun listRootCapturePcms(): String {
        if (!usingRootDaemon) return "Daemon root belum aktif"
        return rootCommand("LIST_PCM_CAPTURE")
    }

    suspend fun getRootAudioDiagnostic(
        packageName: String
    ): String = withContext(Dispatchers.IO) {
        val alsaCtls = if (usingRootDaemon) rootCommand("LIST_FM_CTLS") else "Daemon root belum aktif"
        val pcmDetect = if (usingRootDaemon) rootCommand("DETECT_FM_PCM") else "-"
        val pcmCaptureList = if (usingRootDaemon) rootCommand("LIST_PCM_CAPTURE") else "-"
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
            val combined = "$out\n=== DETECT_FM_PCM ===\n$pcmDetect\n=== LIST_PCM_CAPTURE ===\n$pcmCaptureList\n=== ALSA FM/SPDY/WDMA2 Controls ===\n$alsaCtls"
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
        val liveProbeOutput = if (usingRootDaemon) {
            val pcmDetect = rootCommand("DETECT_FM_PCM")
            val pcmList = rootCommand("LIST_PCM_CAPTURE")
            val v4l2Ctls = rootCommand("LIST_V4L2_CTLS")
            val probeAudio = rootCommand("PROBE_FM_AUDIO")
            val alsaCtls = rootCommand("LIST_ALL_FM_CTLS")
            """
                DETECT_FM_PCM: $pcmDetect
                LIST_PCM_CAPTURE: $pcmList
                PROBE_FM_AUDIO: $probeAudio

                === V4L2 CONTROLS (/dev/radio0) ===
                $v4l2Ctls

                === ALSA CONTROLS (LIST_ALL_FM_CTLS) ===
                $alsaCtls
            """.trimIndent()
        } else {
            runCatching {
                val bin = getRootDaemonBinaryPath()
                val p = ProcessBuilder("su", "-c", bin).redirectErrorStream(true).start()
                p.outputStream.bufferedWriter().use { w ->
                    w.write("OPEN\nTUNE 98700\nLIST_V4L2_CTLS\nDETECT_FM_PCM\nLIST_PCM_CAPTURE\nPROBE_FM_AUDIO\nLIST_ALL_FM_CTLS\nCLOSE\n")
                    w.flush()
                }
                val lines = p.inputStream.bufferedReader().readLines()
                val openResp = lines.getOrNull(0) ?: "-"
                val tuneResp = lines.getOrNull(1) ?: "-"
                val v4l2Resp = lines.getOrNull(2) ?: "-"
                val detectResp = lines.getOrNull(3) ?: "-"
                val pcmListResp = lines.getOrNull(4) ?: "-"
                val probeResp = lines.getOrNull(5) ?: "-"
                val alsaResp = lines.getOrNull(6) ?: "-"
                """
                    OPEN: $openResp | TUNE(98700): $tuneResp
                    DETECT_FM_PCM: $detectResp
                    LIST_PCM_CAPTURE: $pcmListResp
                    PROBE_FM_AUDIO: $probeResp

                    === V4L2 CONTROLS (/dev/radio0) ===
                    $v4l2Resp

                    === ALSA CONTROLS (LIST_ALL_FM_CTLS) ===
                    $alsaResp
                """.trimIndent()
            }.getOrElse { ex ->
                "Gagal menjalankan probe root: ${ex.message}"
            }
        }

        val collected = runCatching {
            val script = """
                echo "=== A21s FM RADIO BUG REPORT ==="
                date
                echo "Model: $(getprop ro.product.model) ($(getprop ro.product.device))"
                echo "Android: $(getprop ro.build.version.release) | SDK: $(getprop ro.build.version.sdk)"
                echo "Kernel: $(uname -a)"
                echo ""
                echo "=== NATIVE DIAGNOSTIC ==="
                cat <<'EOF'
$nativeDiag
EOF
                echo ""
                echo "=== MIXER PATHS (spdy/fm_radio/route-fm/wdma2) ==="
                sed -n '1,120p' /vendor/etc/mixer_paths.xml 2>/dev/null || true
                echo "---"
                grep -n -C 4 -iE "spdy|route-fm|fm_radio|wdma2|sifm2|nsrc2|vpcmindai4|vpcmindai7|bd_srccom6" /vendor/etc/mixer_paths.xml 2>/dev/null || true
                echo ""
                echo "=== AUDIO POLICY (fm/radio_tuner) ==="
                grep -rn -C 3 -iE "fm_tuner|RADIO_TUNER|FM" /vendor/etc/audio_policy* 2>/dev/null | head -n 40 || true
                echo ""
                echo "=== DMESG (s610/14ac0000/mx250/wlbt) ==="
                dmesg 2>/dev/null | grep -iE "s610|14ac0000|mx250|wlbt" | tail -n 80 || true
                echo ""
                echo "=== DMESG (abox/spdy/wdma2/asrc/vpcm) ==="
                dmesg 2>/dev/null | grep -iE "spdy|wdma2|abox|asrc|vpcm|vdma|fm_" | tail -n 140 || true
                echo ""
                echo "=== LOGCAT (Audio/FM) ==="
                logcat -d -t 200 2>/dev/null | grep -iE "A21sFm|AudioTrack|AudioFlinger|audio_hw|fm_" | tail -n 80 || true
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
            appendLine()
            appendLine("=== APP EVENT LOG ===")
            appendLine(getEventLogText())
            appendLine()
            appendLine(liveProbeOutput)
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
