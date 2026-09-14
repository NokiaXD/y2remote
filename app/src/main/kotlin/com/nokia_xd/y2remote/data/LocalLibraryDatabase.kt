package com.nokia_xd.y2remote.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nokia_xd.y2remote.protocol.TrackRow

class LocalLibraryDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        const val DATABASE_NAME = "y2remote_library.db"
        const val DATABASE_VERSION = 1

        const val TABLE_TRACKS = "tracks"
        const val COL_ID = "id"
        const val COL_TITLE = "title"
        const val COL_ARTIST = "artist"
        const val COL_ALBUM = "album"
        const val COL_DURATION_MS = "duration_ms"
        const val COL_FAVORITE = "favorite"
        const val COL_HAS_ARTWORK = "has_artwork"
        const val COL_TITLE_LOWER = "title_lower"
        const val COL_ARTIST_LOWER = "artist_lower"
        const val COL_ALBUM_LOWER = "album_lower"

        const val TABLE_SYNC_INFO = "sync_info"
        const val COL_KEY = "key"
        const val COL_VALUE = "value"

        const val KEY_LAST_SYNC_TIME = "last_sync_timestamp"
        const val KEY_LAST_SYNC_COUNT = "last_sync_count"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_TRACKS (
                $COL_ID INTEGER PRIMARY KEY,
                $COL_TITLE TEXT NOT NULL,
                $COL_ARTIST TEXT NOT NULL,
                $COL_ALBUM TEXT NOT NULL,
                $COL_DURATION_MS INTEGER NOT NULL,
                $COL_FAVORITE INTEGER NOT NULL DEFAULT 0,
                $COL_HAS_ARTWORK INTEGER NOT NULL DEFAULT 0,
                $COL_TITLE_LOWER TEXT NOT NULL,
                $COL_ARTIST_LOWER TEXT NOT NULL,
                $COL_ALBUM_LOWER TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_tracks_title ON $TABLE_TRACKS($COL_TITLE_LOWER)")
        db.execSQL("CREATE INDEX idx_tracks_artist ON $TABLE_TRACKS($COL_ARTIST_LOWER)")
        db.execSQL("CREATE INDEX idx_tracks_album ON $TABLE_TRACKS($COL_ALBUM_LOWER)")

        db.execSQL(
            """
            CREATE TABLE $TABLE_SYNC_INFO (
                $COL_KEY TEXT PRIMARY KEY,
                $COL_VALUE TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TRACKS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SYNC_INFO")
        onCreate(db)
    }

    fun saveTracks(tracks: List<TrackRow>, replaceAll: Boolean = true) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (replaceAll) {
                db.delete(TABLE_TRACKS, null, null)
            }
            val insertSql = """
                INSERT OR REPLACE INTO $TABLE_TRACKS (
                    $COL_ID, $COL_TITLE, $COL_ARTIST, $COL_ALBUM, $COL_DURATION_MS,
                    $COL_FAVORITE, $COL_HAS_ARTWORK, $COL_TITLE_LOWER, $COL_ARTIST_LOWER, $COL_ALBUM_LOWER
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

            val statement = db.compileStatement(insertSql)
            for (track in tracks) {
                statement.bindLong(1, track.id)
                statement.bindString(2, track.title)
                statement.bindString(3, track.artist)
                statement.bindString(4, track.album)
                statement.bindLong(5, track.durationMs)
                statement.bindLong(6, if (track.favorite) 1L else 0L)
                statement.bindLong(7, if (track.hasArtwork) 1L else 0L)
                statement.bindString(8, track.title.lowercase())
                statement.bindString(9, track.artist.lowercase())
                statement.bindString(10, track.album.lowercase())
                statement.executeInsert()
                statement.clearBindings()
            }

            val now = System.currentTimeMillis()
            setSyncMeta(KEY_LAST_SYNC_TIME, now.toString(), db)
            setSyncMeta(KEY_LAST_SYNC_COUNT, tracks.size.toString(), db)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun setSyncMeta(key: String, value: String, db: SQLiteDatabase = writableDatabase) {
        val cv = ContentValues().apply {
            put(COL_KEY, key)
            put(COL_VALUE, value)
        }
        db.insertWithOnConflict(TABLE_SYNC_INFO, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getLastSyncTimestamp(): Long {
        val db = readableDatabase
        db.query(
            TABLE_SYNC_INFO,
            arrayOf(COL_VALUE),
            "$COL_KEY = ?",
            arrayOf(KEY_LAST_SYNC_TIME),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0).toLongOrNull() ?: 0L
            }
        }
        return 0L
    }

    fun getTrackCount(query: String = ""): Int {
        val db = readableDatabase
        val (selection, selectionArgs) = buildSearchSelection(query)
        db.query(
            TABLE_TRACKS,
            arrayOf("COUNT(*)"),
            selection,
            selectionArgs,
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(0)
            }
        }
        return 0
    }

    fun getPagedTracks(sort: String, query: String, offset: Int, limit: Int): List<TrackRow> {
        val db = readableDatabase
        val (selection, selectionArgs) = buildSearchSelection(query)
        val orderBy = buildOrderBy(sort)

        val result = mutableListOf<TrackRow>()
        db.query(
            TABLE_TRACKS,
            arrayOf(COL_ID, COL_TITLE, COL_ARTIST, COL_ALBUM, COL_DURATION_MS, COL_FAVORITE, COL_HAS_ARTWORK),
            selection,
            selectionArgs,
            null, null,
            orderBy,
            "$offset, $limit"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(cursorToTrackRow(cursor))
            }
        }
        return result
    }

    fun getAllTracks(sort: String = "title", query: String = ""): List<TrackRow> {
        val db = readableDatabase
        val (selection, selectionArgs) = buildSearchSelection(query)
        val orderBy = buildOrderBy(sort)

        val result = mutableListOf<TrackRow>()
        db.query(
            TABLE_TRACKS,
            arrayOf(COL_ID, COL_TITLE, COL_ARTIST, COL_ALBUM, COL_DURATION_MS, COL_FAVORITE, COL_HAS_ARTWORK),
            selection,
            selectionArgs,
            null, null,
            orderBy
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(cursorToTrackRow(cursor))
            }
        }
        return result
    }

    fun getTrack(trackId: Long): TrackRow? {
        val db = readableDatabase
        db.query(
            TABLE_TRACKS,
            arrayOf(COL_ID, COL_TITLE, COL_ARTIST, COL_ALBUM, COL_DURATION_MS, COL_FAVORITE, COL_HAS_ARTWORK),
            "$COL_ID = ?",
            arrayOf(trackId.toString()),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursorToTrackRow(cursor)
            }
        }
        return null
    }

    fun clear() {
        val db = writableDatabase
        db.delete(TABLE_TRACKS, null, null)
        db.delete(TABLE_SYNC_INFO, null, null)
    }

    private fun buildSearchSelection(query: String): Pair<String?, Array<String>?> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return Pair(null, null)
        val selection = "($COL_TITLE_LOWER LIKE ? OR $COL_ARTIST_LOWER LIKE ? OR $COL_ALBUM_LOWER LIKE ?)"
        val arg = "%$q%"
        return Pair(selection, arrayOf(arg, arg, arg))
    }

    private fun buildOrderBy(sort: String): String {
        return when (sort.lowercase()) {
            "artist" -> "$COL_ARTIST_LOWER ASC, $COL_ALBUM_LOWER ASC, $COL_TITLE_LOWER ASC"
            "album" -> "$COL_ALBUM_LOWER ASC, $COL_TITLE_LOWER ASC"
            "title" -> "$COL_TITLE_LOWER ASC"
            "duration" -> "$COL_DURATION_MS ASC"
            else -> "$COL_TITLE_LOWER ASC"
        }
    }

    private fun cursorToTrackRow(cursor: Cursor): TrackRow {
        return TrackRow(
            id = cursor.getLong(0),
            title = cursor.getString(1),
            artist = cursor.getString(2),
            album = cursor.getString(3),
            durationMs = cursor.getLong(4),
            favorite = cursor.getInt(5) == 1,
            hasArtwork = cursor.getInt(6) == 1
        )
    }
}
