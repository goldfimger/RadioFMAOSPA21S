package id.exynos850.fmradio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import id.exynos850.fmradio.audio.FmAudioTrackEngine
import id.exynos850.fmradio.data.FmStationDbHelper
import id.exynos850.fmradio.data.FmStationEntity
import id.exynos850.fmradio.data.StationJsonExporter
import id.exynos850.fmradio.nativebridge.FmNativeTuner
import id.exynos850.fmradio.nativebridge.HardwareSignalInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var fmTuner: FmNativeTuner
    private lateinit var audioEngine: FmAudioTrackEngine
    private lateinit var dbHelper: FmStationDbHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fmTuner = FmNativeTuner(applicationInfo.nativeLibraryDir)
        audioEngine = FmAudioTrackEngine(this)
        dbHelper = FmStationDbHelper(this)

        setContent {
            var isDarkMode by remember { mutableStateOf(true) }
            A21sFmTheme(darkTheme = isDarkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MonetRadioScreen(
                        isDarkMode = isDarkMode,
                        onToggleDarkMode = { isDarkMode = !isDarkMode }
                    )
                }
            }
        }
    }

    @Composable
    private fun MonetRadioScreen(
        isDarkMode: Boolean,
        onToggleDarkMode: () -> Unit
    ) {
        var freqKhz by remember { mutableIntStateOf(98700) }
        val stepKhz = 100
        var isPlaying by remember { mutableStateOf(false) }
        var isScanning by remember { mutableStateOf(false) }
        var useSpeaker by remember { mutableStateOf(false) }
        var hasHeadset by remember { mutableStateOf(audioEngine.hasWiredHeadsetAntenna()) }
        var signal by remember { mutableStateOf(HardwareSignalInfo(-104, 0, false, 0)) }
        var stations by remember { mutableStateOf(dbHelper.getAllStations()) }
        var tunerError by remember { mutableStateOf<String?>(null) }
        var lastErrorHistory by remember { mutableStateOf<String?>(null) }
        var diagnosticReport by remember { mutableStateOf<String?>(null) }
        var isRootActive by remember { mutableStateOf(false) }
        var hasStartedPlayback by remember { mutableStateOf(false) }

        val activeStation = stations.firstOrNull { it.frequencyKhz == freqKhz }

        val triggerBugReport: () -> Unit = {
            Toast.makeText(this@MainActivity, "Mengumpulkan probe & log...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val statusText = buildString {
                    append(tunerError ?: lastErrorHistory ?: "-")
                    append(" | AudioEngine: ")
                    append(audioEngine.lastStreamStats)
                }
                val (ok, msg) = fmTuner.generateAndSaveBugReport(
                    packageName = packageName,
                    nativeDiag = fmTuner.getDiagnosticReport(),
                    extraStatus = statusText
                )
                if (tunerError != null) {
                    diagnosticReport = msg
                }
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "Disimpan ke /sdcard/A21s_FM_BugReport.txt" else msg,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null) {
                StationJsonExporter.exportToUri(this, uri, stations)
                    .onSuccess { count ->
                        Toast.makeText(this, "$count stasiun diekspor ke JSON", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure {
                        Toast.makeText(this, "Gagal ekspor: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
        }

        DisposableEffect(Unit) {
            val callback = audioEngine.registerAntennaCallback { connected ->
                hasHeadset = connected
            }
            onDispose {
                audioEngine.unregisterAntennaCallback(callback)
                audioEngine.stopAudioStream("dispose")
            }
        }

        LaunchedEffect(isPlaying) {
            if (isPlaying) {
                hasStartedPlayback = true
                tunerError = null
                diagnosticReport = null
                fmTuner.logEvent("Play pressed freq=$freqKhz")
                val opened = withContext(Dispatchers.IO) { fmTuner.open(allowRootFallback = true) }
                if (!isActive || !isPlaying) {
                    withContext(Dispatchers.IO + NonCancellable) {
                        fmTuner.disableFmAudioRoute()
                        fmTuner.close()
                    }
                    return@LaunchedEffect
                }
                if (opened) {
                    isRootActive = fmTuner.isUsingRootMode()
                    fmTuner.logEvent("Tuner opened root=$isRootActive")
                    val (alsaRouteStatus, rootFmPcm) = withContext(Dispatchers.IO) {
                        fmTuner.tuneManual(freqKhz)
                        val route = fmTuner.enableFmAudioRoute()
                        val pcm = fmTuner.detectRootFmPcm()
                        route to pcm
                    }
                    fmTuner.logEvent("Route=$alsaRouteStatus PCM=$rootFmPcm")
                    if (!isActive || !isPlaying) {
                        withContext(Dispatchers.IO + NonCancellable) {
                            fmTuner.disableFmAudioRoute()
                            fmTuner.close()
                        }
                        return@LaunchedEffect
                    }
                    audioEngine.startAudioStream(
                        scope = lifecycleScope,
                        rootFmPcm = rootFmPcm,
                        rootDaemonPath = fmTuner.getRootDaemonBinaryPath(),
                        alsaRouteStatus = alsaRouteStatus,
                        onLog = { m -> fmTuner.logEvent(m) }
                    ) { err ->
                        fmTuner.logEvent("AudioError: $err")
                        tunerError = err
                        lastErrorHistory = err
                        isPlaying = false
                    }
                    while (isActive && isPlaying) {
                        signal = withContext(Dispatchers.IO) { fmTuner.readSignalMetrics() }
                        delay(400)
                    }
                } else {
                    isRootActive = false
                    val err = fmTuner.getLastError().ifBlank { "/dev/radio0 tidak dapat dibuka" }
                    fmTuner.logEvent("Open failed: $err")
                    tunerError = err
                    lastErrorHistory = err
                    diagnosticReport = fmTuner.getDiagnosticReport()
                    isPlaying = false
                }
            } else if (hasStartedPlayback) {
                fmTuner.logEvent("Stop requested -> stopping stream & route")
                isRootActive = false
                audioEngine.stopAudioStream("user_or_error_stop")
                withContext(Dispatchers.IO + NonCancellable) {
                    fmTuner.disableFmAudioRoute()
                    fmTuner.close()
                }
                fmTuner.logEvent("Cleanup complete")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Monet Minimal Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Radio FM",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    softWrap = false
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onToggleDarkMode,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isDarkMode) "Terang" else "Gelap",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    TextButton(
                        onClick = { exportLauncher.launch("a21s_fm_stations.json") },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Ekspor JSON",
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // Diagnostik Hardware Scrollable (tombol rapi dengan FlowRow, tidak terpotong)
            if (tunerError != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isRootActive) "Status Audio FM (Tuner Aktif)" else "Diagnostik Hardware FM",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = tunerError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!diagnosticReport.isNullOrBlank()) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 135.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = diagnosticReport!!,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (!isRootActive) {
                                Button(
                                    onClick = {
                                        lifecycleScope.launch {
                                            val (ok, log) = fmTuner.tryUnlockViaRoot()
                                            if (ok) {
                                                diagnosticReport = null
                                                isPlaying = true
                                            } else {
                                                diagnosticReport = log + "\n" + fmTuner.getDiagnosticReport()
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Text("Coba Root (KSU)", maxLines = 1, softWrap = false, fontSize = 12.sp)
                                }
                            } else {
                                FilledTonalButton(
                                    onClick = {
                                        lifecycleScope.launch {
                                            diagnosticReport = fmTuner.getRootAudioDiagnostic(packageName)
                                        }
                                    },
                                    shape = RoundedCornerShape(18.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text("Cek Audio HAL", maxLines = 1, softWrap = false, fontSize = 12.sp)
                                }
                                if (!diagnosticReport.isNullOrBlank()) {
                                    FilledTonalButton(
                                        onClick = {
                                            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cb.setPrimaryClip(ClipData.newPlainText("A21s FM Log", diagnosticReport))
                                            Toast.makeText(this@MainActivity, "Log disalin", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(18.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text("Salin Log", maxLines = 1, softWrap = false, fontSize = 12.sp)
                                    }
                                }
                            }
                            FilledTonalButton(
                                onClick = triggerBugReport,
                                shape = RoundedCornerShape(18.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("Bug Report (.txt)", maxLines = 1, softWrap = false, fontSize = 12.sp)
                            }
                            TextButton(
                                onClick = { tunerError = null },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text("Tutup", maxLines = 1, softWrap = false, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Kartu Tuner Utama Material You (Monet)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = activeStation?.name ?: "Sinkronisasi Manual",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format(Locale.US, "%.1f", freqKhz / 1000.0),
                            fontSize = 56.sp,
                            fontWeight = FontWeight.Light,
                            fontFamily = FontFamily.SansSerif,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "MHz",
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }

                    // Status Sinyal & Antena Ringkas
                    val antennaLabel = if (hasHeadset) "Headset" else "Tanpa Antena"
                    val modeLabel = if (isPlaying) (if (signal.isStereo) "Stereo" else "Mono") else "Siaga"
                    val accessLabel = if (isPlaying && isRootActive) " · Root" else ""
                    Text(
                        text = "${signal.rssiDbm} dBm · ${signal.qualityPercent}% · $modeLabel · $antennaLabel$accessLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    LinearProgressIndicator(
                        progress = { if (isPlaying) signal.qualityPercent / 100f else 0f },
                        modifier = Modifier
                            .fillMaxWidth(0.65f)
                            .height(3.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    // Skala Dial Garis Gaya One UI
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        val needleColor = MaterialTheme.colorScheme.primary
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                        ) {
                            val totalTicks = 41
                            val stepX = size.width / (totalTicks - 1)
                            for (i in 0 until totalTicks) {
                                val x = i * stepX
                                val isMajor = i % 5 == 0
                                val h = if (isMajor) size.height * 0.72f else size.height * 0.38f
                                drawLine(
                                    color = tickColor,
                                    start = Offset(x, (size.height - h) / 2f),
                                    end = Offset(x, (size.height + h) / 2f),
                                    strokeWidth = if (isMajor) 2.5f else 1.5f
                                )
                            }
                            val ratio = (freqKhz - 87500f) / (108000f - 87500f)
                            val needleX = ratio * size.width
                            drawLine(
                                color = needleColor,
                                start = Offset(needleX, 0f),
                                end = Offset(needleX, size.height),
                                strokeWidth = 5f
                            )
                        }
                        Slider(
                            value = freqKhz.toFloat(),
                            onValueChange = {
                                val nextFreq = (((it.toInt() + stepKhz / 2) / stepKhz) * stepKhz).coerceIn(87500, 108000)
                                freqKhz = nextFreq
                                if (isPlaying) {
                                    lifecycleScope.launch(Dispatchers.IO) { fmTuner.tuneManual(nextFreq) }
                                }
                            },
                            valueRange = 87500f..108000f,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = androidx.compose.ui.graphics.Color.Transparent,
                                inactiveTrackColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Tombol Transport Utama (< Power >)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                val nextFreq = (freqKhz - stepKhz).coerceAtLeast(87500)
                                freqKhz = nextFreq
                                if (isPlaying) {
                                    lifecycleScope.launch(Dispatchers.IO) { fmTuner.tuneManual(nextFreq) }
                                }
                            },
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape
                        ) {
                            Text("‹", fontSize = 24.sp, fontWeight = FontWeight.Medium)
                        }

                        Button(
                            onClick = { isPlaying = !isPlaying },
                            modifier = Modifier.size(64.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isPlaying) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Text(
                                text = if (isPlaying) "■" else "▶",
                                fontSize = 20.sp
                            )
                        }

                        FilledTonalIconButton(
                            onClick = {
                                val nextFreq = (freqKhz + stepKhz).coerceAtMost(108000)
                                freqKhz = nextFreq
                                if (isPlaying) {
                                    lifecycleScope.launch(Dispatchers.IO) { fmTuner.tuneManual(nextFreq) }
                                }
                            },
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape
                        ) {
                            Text("›", fontSize = 24.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    // 3 Tombol Aksi Ringkas
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                if (!isScanning) {
                                    isScanning = true
                                    lifecycleScope.launch {
                                        val found = fmTuner.autoScanStations(stepKhz) { f, sig ->
                                            freqKhz = f
                                            signal = sig
                                        }
                                        found.forEach { (f, sig) ->
                                            dbHelper.upsertStation(
                                                FmStationEntity(
                                                    frequencyKhz = f,
                                                    name = String.format(Locale.US, "FM %.1f", f / 1000.0),
                                                    genre = "Auto-Scan",
                                                    rssiDbm = sig.rssiDbm,
                                                    isFavorite = false,
                                                    isStereo = sig.isStereo
                                                )
                                            )
                                        }
                                        stations = dbHelper.getAllStations()
                                        isScanning = false
                                    }
                                }
                            },
                            enabled = isPlaying && !isScanning,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Text(if (isScanning) "Scan..." else "Auto-Scan", maxLines = 1, softWrap = false, fontSize = 12.sp)
                        }

                        FilledTonalButton(
                            onClick = {
                                useSpeaker = !useSpeaker
                                audioEngine.setSpeakerOutput(useSpeaker)
                            },
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Text(if (useSpeaker) "Speaker" else "Headset", maxLines = 1, softWrap = false, fontSize = 12.sp)
                        }

                        FilledTonalButton(
                            onClick = {
                                dbHelper.upsertStation(
                                    FmStationEntity(
                                        frequencyKhz = freqKhz,
                                        name = activeStation?.name ?: String.format(Locale.US, "FM %.1f", freqKhz / 1000.0),
                                        genre = "Favorit",
                                        rssiDbm = signal.rssiDbm,
                                        isFavorite = true,
                                        isStereo = signal.isStereo
                                    )
                                )
                                stations = dbHelper.getAllStations()
                            },
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Text("★ Simpan", maxLines = 1, softWrap = false, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Daftar Stasiun SQLite (Satu Kartu Monet)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Stasiun (${stations.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = triggerBugReport,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Bug Report (.txt)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    if (stations.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Belum ada stasiun tersimpan",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                        itemsIndexed(stations, key = { _, st -> st.id }) { index, st ->
                            val isSelected = st.frequencyKhz == freqKhz
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        freqKhz = st.frequencyKhz
                                        if (isPlaying) {
                                            lifecycleScope.launch(Dispatchers.IO) { fmTuner.tuneManual(st.frequencyKhz) }
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "%.1f", st.frequencyKhz / 1000.0),
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 16.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Column {
                                        Text(
                                            text = st.name,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "${st.rssiDbm} dBm · ${st.genre}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            dbHelper.toggleFavorite(st.id, !st.isFavorite)
                                            stations = dbHelper.getAllStations()
                                        }
                                    ) {
                                        Text(
                                            text = if (st.isFavorite) "★" else "☆",
                                            fontSize = 18.sp,
                                            color = if (st.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            dbHelper.deleteStation(st.id)
                                            stations = dbHelper.getAllStations()
                                        }
                                    ) {
                                        Text(
                                            text = "×",
                                            fontSize = 20.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            if (index < stations.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioEngine.stopAudioStream("activity_destroy")
        CoroutineScope(Dispatchers.IO).launch {
            fmTuner.close()
        }
        dbHelper.close()
    }
}
