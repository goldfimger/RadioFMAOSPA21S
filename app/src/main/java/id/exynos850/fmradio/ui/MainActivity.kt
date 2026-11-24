package id.exynos850.fmradio.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import id.exynos850.fmradio.audio.FmAudioTrackEngine
import id.exynos850.fmradio.data.FmStationDbHelper
import id.exynos850.fmradio.data.FmStationEntity
import id.exynos850.fmradio.data.StationJsonExporter
import id.exynos850.fmradio.nativebridge.FmNativeTuner
import id.exynos850.fmradio.nativebridge.HardwareSignalInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var fmTuner: FmNativeTuner
    private lateinit var audioEngine: FmAudioTrackEngine
    private lateinit var dbHelper: FmStationDbHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fmTuner = FmNativeTuner()
        audioEngine = FmAudioTrackEngine(this)
        dbHelper = FmStationDbHelper(this)

        setContent {
            var isDarkMode by remember { mutableStateOf(true) }
            A21sFmTheme(darkTheme = isDarkMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FmRadioScreen(
                        isDarkMode = isDarkMode,
                        onToggleDarkMode = { isDarkMode = !isDarkMode }
                    )
                }
            }
        }
    }

    @Composable
    private fun FmRadioScreen(
        isDarkMode: Boolean,
        onToggleDarkMode: () -> Unit
    ) {
        var freqKhz by remember { mutableIntStateOf(98700) }
        var stepKhz by remember { mutableIntStateOf(100) }
        var isPlaying by remember { mutableStateOf(false) }
        var isScanning by remember { mutableStateOf(false) }
        var useSpeaker by remember { mutableStateOf(false) }
        var hasHeadset by remember { mutableStateOf(audioEngine.hasWiredHeadsetAntenna()) }
        var signal by remember { mutableStateOf(HardwareSignalInfo(-104, 0, false, 0)) }
        var stations by remember { mutableStateOf(dbHelper.getAllStations()) }

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

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) {
                Toast.makeText(this, "Izin RECORD_AUDIO diperlukan untuk AudioTrack FM", Toast.LENGTH_SHORT).show()
            }
        }

        DisposableEffect(Unit) {
            val callback = audioEngine.registerAntennaCallback { connected ->
                hasHeadset = connected
            }
            onDispose {
                audioEngine.unregisterAntennaCallback(callback)
            }
        }

        LaunchedEffect(isPlaying) {
            if (isPlaying) {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                if (fmTuner.open()) {
                    fmTuner.tuneManual(freqKhz)
                    audioEngine.startAudioStream(lifecycleScope) { err ->
                        Toast.makeText(this@MainActivity, err, Toast.LENGTH_LONG).show()
                    }
                    while (isActive && isPlaying) {
                        signal = fmTuner.readSignalMetrics()
                        delay(400)
                    }
                } else {
                    Toast.makeText(this@MainActivity, "/dev/radio0 tidak dapat dibuka", Toast.LENGTH_LONG).show()
                    isPlaying = false
                }
            } else {
                audioEngine.stopAudioStream()
                fmTuner.close()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("A21s FM Radio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onToggleDarkMode) {
                        Text(if (isDarkMode) "Mode Terang" else "Mode Gelap")
                    }
                    OutlinedButton(onClick = { exportLauncher.launch("a21s_fm_stations.json") }) {
                        Text("Ekspor JSON")
                    }
                }
            }

            // Panel Frekuensi Utama & Indikator Sinyal
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = String.format(Locale.US, "%.1f MHz", freqKhz / 1000.0),
                        fontSize = 44.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "RSSI: ${signal.rssiDbm} dBm · Kualitas: ${signal.qualityPercent}% · ${if (signal.isStereo) "Stereo" else "Mono"} · Antena: ${if (hasHeadset) "Headset Terpasang" else "Pasang Kabel 3.5mm"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    LinearProgressIndicator(
                        progress = { signal.qualityPercent / 100f },
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                    )
                    Slider(
                        value = freqKhz.toFloat(),
                        onValueChange = {
                            freqKhz = ((it.toInt() + stepKhz / 2) / stepKhz) * stepKhz
                            if (isPlaying) fmTuner.tuneManual(freqKhz)
                        },
                        valueRange = 87500f..108000f
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { isPlaying = !isPlaying },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isPlaying) "Matikan Tuner" else "Nyalakan Tuner")
                        }
                        OutlinedButton(
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
                                                    genre = "Hasil Scan",
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
                            enabled = isPlaying && !isScanning
                        ) {
                            Text(if (isScanning) "Memindai..." else "Auto-Scan")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            useSpeaker = !useSpeaker
                            audioEngine.setSpeakerOutput(useSpeaker)
                        }) {
                            Text(if (useSpeaker) "Output: Speaker" else "Output: Headset")
                        }
                        FilledTonalButton(onClick = {
                            dbHelper.upsertStation(
                                FmStationEntity(
                                    frequencyKhz = freqKhz,
                                    name = String.format(Locale.US, "Favorit %.1f", freqKhz / 1000.0),
                                    genre = "Favorit",
                                    rssiDbm = signal.rssiDbm,
                                    isFavorite = true,
                                    isStereo = signal.isStereo
                                )
                            )
                            stations = dbHelper.getAllStations()
                        }) {
                            Text("Simpan Favorit")
                        }
                    }
                }
            }

            // Daftar Stasiun SQLite
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(stations, key = { it.id }) { st ->
                    OutlinedCard(
                        onClick = {
                            freqKhz = st.frequencyKhz
                            if (isPlaying) fmTuner.tuneManual(freqKhz)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(st.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    String.format(Locale.US, "%.1f MHz · %s · %d dBm", st.frequencyKhz / 1000.0, st.genre, st.rssiDbm),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Row {
                                TextButton(onClick = {
                                    dbHelper.toggleFavorite(st.id, !st.isFavorite)
                                    stations = dbHelper.getAllStations()
                                }) {
                                    Text(if (st.isFavorite) "★ Favorit" else "☆")
                                }
                                TextButton(onClick = {
                                    dbHelper.deleteStation(st.id)
                                    stations = dbHelper.getAllStations()
                                }) {
                                    Text("Hapus")
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
        audioEngine.stopAudioStream()
        fmTuner.close()
        dbHelper.close()
    }
}
