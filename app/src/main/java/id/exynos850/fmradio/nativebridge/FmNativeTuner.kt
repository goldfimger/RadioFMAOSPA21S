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

    suspend fun getRootAudioDiagnostic(): String = withContext(Dispatchers.IO) {
        runCatching {
            val script = """
                echo "=== /dev/radio* ==="
                ls -lZ /dev/radio* /dev/fm* 2>&1 || true
                echo "=== /proc/asound/pcm ==="
                cat /proc/asound/pcm 2>&1 || true
                echo "=== audio_policy & mixer FM ==="
                grep -inE "fm|radio" /vendor/etc/*mixer* /vendor/etc/audio_policy* 2>/dev/null | head -n 40 || true
                if command -v tinymix >/dev/null 2>&1; then
                  echo "=== tinymix FM ==="
                  tinymix 2>/dev/null | grep -iE "fm|radio" | head -n 25 || true
                fi
            """.trimIndent()
            val proc = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            out.ifBlank { "Tidak ada output diagnostik root." }
        }.getOrElse { ex ->
            "Gagal membaca diagnostik root: ${ex.message}"
        }
    }

    suspend fun installKsuPrivAppModule(apkSourcePath: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (apkSourcePath.isBlank()) {
            return@withContext false to "Path APK tidak ditemukan."
        }
        runCatching {
            val modDir = "/data/adb/modules/a21s_fm_privapp"
            val privAppDir = "$modDir/system/priv-app/A21sFMRadio"
            val permDir = "$modDir/system/etc/permissions"
            val script = """
                mkdir -p "$privAppDir/lib/arm64" "$permDir"
                cp -f "$apkSourcePath" "$privAppDir/A21sFMRadio.apk"
                chmod 0644 "$privAppDir/A21sFMRadio.apk"
                if [ -d "$nativeLibDir" ]; then
                  cp -f "$nativeLibDir"/*.so "$privAppDir/lib/arm64/" 2>/dev/null || true
                  chmod 0755 "$privAppDir/lib/arm64"/*.so 2>/dev/null || true
                fi
                cat << 'EOF' > "$modDir/module.prop"
id=a21s_fm_privapp
name=A21s FM Radio Priv-App
version=v1.0
versionCode=1
author=exynos850
description=Memberikan izin system priv-app CAPTURE_AUDIO_OUTPUT dan ACCESS_FM_RADIO untuk A21s FM Radio di LineageOS 22.2
EOF
                cat << 'EOF' > "$permDir/privapp-permissions-id.exynos850.fmradio.xml"
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="id.exynos850.fmradio">
        <permission name="android.permission.ACCESS_FM_RADIO" />
        <permission name="android.permission.CAPTURE_AUDIO_OUTPUT" />
        <permission name="android.permission.MODIFY_AUDIO_SETTINGS" />
    </privapp-permissions>
</permissions>
EOF
                chmod 0644 "$modDir/module.prop" "$permDir/privapp-permissions-id.exynos850.fmradio.xml"
                echo "MODUL_OK"
            """.trimIndent()
            val proc = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val code = proc.waitFor()
            val ok = code == 0 && out.contains("MODUL_OK")
            ok to if (ok) {
                "Modul KernelSU/Magisk berhasil dibuat di $modDir. Silakan REBOOT perangkat agar izin CAPTURE_AUDIO_OUTPUT (AudioTrack FM) aktif."
            } else {
                "Gagal membuat modul Priv-App: $out"
            }
        }.getOrElse { ex ->
            false to "Gagal menjalankan su untuk modul Priv-App: ${ex.message}"
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
