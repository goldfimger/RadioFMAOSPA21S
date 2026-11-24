package id.exynos850.fmradio.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object StationJsonExporter {

    fun toJsonString(stations: List<FmStationEntity>): String {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val root = JSONObject().apply {
            put("schemaVersion", 1)
            put("deviceTarget", "samsung_a21s_exynos850")
            put("romBranch", "lineage-22.2")
            put("exportedAt", isoFormat.format(Date()))
            put("totalStations", stations.size)
            put("favoritesCount", stations.count { it.isFavorite })
        }

        val array = JSONArray()
        stations.sortedBy { it.frequencyKhz }.forEach { station ->
            array.put(
                JSONObject().apply {
                    put("id", station.id)
                    put("frequencyKhz", station.frequencyKhz)
                    put("frequencyMhz", String.format(Locale.US, "%.1f", station.frequencyKhz / 1000.0))
                    put("name", station.name)
                    put("genre", station.genre)
                    put("rssiDbm", station.rssiDbm)
                    put("isFavorite", station.isFavorite)
                    put("isStereo", station.isStereo)
                    put("region", station.region)
                    put("notes", station.notes)
                    put("createdAt", station.createdAt)
                    put("updatedAt", station.updatedAt)
                }
            )
        }
        root.put("stations", array)
        return root.toString(2)
    }

    fun exportToUri(context: Context, uri: Uri, stations: List<FmStationEntity>): Result<Int> {
        return runCatching {
            val jsonPayload = toJsonString(stations)
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(jsonPayload.toByteArray(Charsets.UTF_8))
                output.flush()
            } ?: error("Tidak dapat membuka OutputStream untuk URI ekspor")
            stations.size
        }
    }
}
