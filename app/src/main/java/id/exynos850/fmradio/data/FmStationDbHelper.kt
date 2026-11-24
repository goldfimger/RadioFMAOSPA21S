package id.exynos850.fmradio.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class FmStationEntity(
    val id: Long = 0L,
    val frequencyKhz: Int,
    val name: String,
    val genre: String,
    val rssiDbm: Int,
    val isFavorite: Boolean,
    val isStereo: Boolean,
    val region: String = "ID",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

class FmStationDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "fm_stations.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_STATIONS = "stations"

        private const val COL_ID = "id"
        private const val COL_FREQ_KHZ = "frequency_khz"
        private const val COL_NAME = "name"
        private const val COL_GENRE = "genre"
        private const val COL_RSSI_DBM = "rssi_dbm"
        private const val COL_IS_FAVORITE = "is_favorite"
        private const val COL_IS_STEREO = "is_stereo"
        private const val COL_REGION = "region"
        private const val COL_NOTES = "notes"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_UPDATED_AT = "updated_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_STATIONS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_FREQ_KHZ INTEGER NOT NULL UNIQUE,
                $COL_NAME TEXT NOT NULL,
                $COL_GENRE TEXT NOT NULL DEFAULT 'Umum',
                $COL_RSSI_DBM INTEGER NOT NULL DEFAULT -75,
                $COL_IS_FAVORITE INTEGER NOT NULL DEFAULT 0,
                $COL_IS_STEREO INTEGER NOT NULL DEFAULT 1,
                $COL_REGION TEXT NOT NULL DEFAULT 'ID',
                $COL_NOTES TEXT DEFAULT '',
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_stations_freq ON $TABLE_STATIONS($COL_FREQ_KHZ)")
        db.execSQL("CREATE INDEX idx_stations_fav ON $TABLE_STATIONS($COL_IS_FAVORITE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_STATIONS")
        onCreate(db)
    }

    fun upsertStation(station: FmStationEntity): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COL_FREQ_KHZ, station.frequencyKhz)
            put(COL_NAME, station.name)
            put(COL_GENRE, station.genre)
            put(COL_RSSI_DBM, station.rssiDbm)
            put(COL_IS_FAVORITE, if (station.isFavorite) 1 else 0)
            put(COL_IS_STEREO, if (station.isStereo) 1 else 0)
            put(COL_REGION, station.region)
            put(COL_NOTES, station.notes)
            put(COL_CREATED_AT, station.createdAt)
            put(COL_UPDATED_AT, now)
        }
        return writableDatabase.insertWithOnConflict(
            TABLE_STATIONS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getAllStations(onlyFavorites: Boolean = false): List<FmStationEntity> {
        val list = mutableListOf<FmStationEntity>()
        val selection = if (onlyFavorites) "$COL_IS_FAVORITE = ?" else null
        val args = if (onlyFavorites) arrayOf("1") else null

        readableDatabase.query(
            TABLE_STATIONS,
            null,
            selection,
            args,
            null,
            null,
            "$COL_FREQ_KHZ ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    FmStationEntity(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                        frequencyKhz = cursor.getInt(cursor.getColumnIndexOrThrow(COL_FREQ_KHZ)),
                        name = cursor.getString(cursor.getColumnIndexOrThrow(COL_NAME)),
                        genre = cursor.getString(cursor.getColumnIndexOrThrow(COL_GENRE)),
                        rssiDbm = cursor.getInt(cursor.getColumnIndexOrThrow(COL_RSSI_DBM)),
                        isFavorite = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_FAVORITE)) == 1,
                        isStereo = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_STEREO)) == 1,
                        region = cursor.getString(cursor.getColumnIndexOrThrow(COL_REGION)),
                        notes = cursor.getString(cursor.getColumnIndexOrThrow(COL_NOTES)) ?: "",
                        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
                        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT))
                    )
                )
            }
        }
        return list
    }

    fun toggleFavorite(stationId: Long, favorite: Boolean): Int {
        val values = ContentValues().apply {
            put(COL_IS_FAVORITE, if (favorite) 1 else 0)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        return writableDatabase.update(
            TABLE_STATIONS,
            values,
            "$COL_ID = ?",
            arrayOf(stationId.toString())
        )
    }

    fun deleteStation(stationId: Long): Int {
        return writableDatabase.delete(
            TABLE_STATIONS,
            "$COL_ID = ?",
            arrayOf(stationId.toString())
        )
    }
}
