package com.nokia_xd.y2remote.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

sealed class RemoteCommand {
    object Toggle : RemoteCommand() {
        override fun toString(): String = "Toggle"
    }
    object Play : RemoteCommand() {
        override fun toString(): String = "Play"
    }
    object Pause : RemoteCommand() {
        override fun toString(): String = "Pause"
    }
    object Next : RemoteCommand() {
        override fun toString(): String = "Next"
    }
    object Previous : RemoteCommand() {
        override fun toString(): String = "Previous"
    }
    object VolumeUp : RemoteCommand() {
        override fun toString(): String = "VolumeUp"
    }
    object VolumeDown : RemoteCommand() {
        override fun toString(): String = "VolumeDown"
    }
    data class SetVolume(val percent: Int) : RemoteCommand()
    data class Rewind(val amountMs: Long = RemoteProtocol.DEFAULT_SEEK_STEP_MS) : RemoteCommand()
    data class Forward(val amountMs: Long = RemoteProtocol.DEFAULT_SEEK_STEP_MS) : RemoteCommand()
    data class Seek(val positionMs: Long) : RemoteCommand()
}

data class TrackRow(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val favorite: Boolean,
    val hasArtwork: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("duration", durationMs)
        put("favorite", favorite)
        put("hasArtwork", hasArtwork)
    }

    companion object {
        fun fromJson(json: JSONObject): TrackRow = TrackRow(
            id = json.optLong("id", 0L),
            title = json.optString("title", ""),
            artist = json.optString("artist", ""),
            album = json.optString("album", ""),
            durationMs = json.optLong("duration", 0L),
            favorite = json.optBoolean("favorite", false),
            hasArtwork = json.optBoolean("hasArtwork", false)
        )
    }
}

data class GenreCount(val key: String, val label: String, val count: Int) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("label", label)
        put("count", count)
    }

    companion object {
        fun fromJson(json: JSONObject): GenreCount = GenreCount(
            key = json.optString("key", ""),
            label = json.optString("label", ""),
            count = json.optInt("count", 0)
        )
    }
}

data class YearCount(val year: Int?, val count: Int, val artworkTrackId: Long? = null) {
    fun toJson(): JSONObject = JSONObject().apply {
        if (year != null) put("year", year) else put("year", JSONObject.NULL)
        put("count", count)
        if (artworkTrackId != null) put("artworkTrackId", artworkTrackId)
    }

    companion object {
        fun fromJson(json: JSONObject): YearCount = YearCount(
            year = if (json.has("year") && !json.isNull("year")) json.optInt("year") else null,
            count = json.optInt("count", 0),
            artworkTrackId = if (json.has("artworkTrackId") && !json.isNull("artworkTrackId")) json.optLong("artworkTrackId") else null
        )
    }
}

data class PlaylistRow(val id: Long, val name: String, val trackCount: Int) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("trackCount", trackCount)
    }

    companion object {
        fun fromJson(json: JSONObject): PlaylistRow = PlaylistRow(
            id = json.optLong("id", 0L),
            name = json.optString("name", ""),
            trackCount = json.optInt("trackCount", 0)
        )
    }
}

data class QueueEntryRow(
    val entryId: Long,
    val trackId: Long,
    val origin: String,
    val track: TrackRow?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("entryId", entryId)
        put("trackId", trackId)
        put("origin", origin)
        if (track != null) put("track", track.toJson())
    }

    companion object {
        fun fromJson(json: JSONObject): QueueEntryRow = QueueEntryRow(
            entryId = json.optLong("entryId", 0L),
            trackId = json.optLong("trackId", 0L),
            origin = json.optString("origin", "context"),
            track = json.optJSONObject("track")?.let { TrackRow.fromJson(it) }
        )
    }
}

sealed class RemoteMessage {
    data class Hello(
        val device: String,
        val protocol: Int
    ) : RemoteMessage()

    data class PlayerState(
        val title: String,
        val artist: String,
        val album: String,
        val status: String,
        val positionMs: Long,
        val durationMs: Long,
        val volumePercent: Int = 100
    ) : RemoteMessage()

    data class Artwork(
        val base64: String
    ) : RemoteMessage()

    data class Command(
        val command: RemoteCommand
    ) : RemoteMessage()

    // --- Protocol v2 Library Messages ---
    data class LibraryPage(
        val scope: String,
        val sort: String,
        val query: String,
        val offset: Int,
        val limit: Int,
        val requestId: Long
    ) : RemoteMessage()

    data class LibraryPageResult(
        val requestId: Long,
        val total: Int,
        val hasMore: Boolean,
        val rows: List<TrackRow>
    ) : RemoteMessage()

    data class LibrarySummaryRequest(
        val requestId: Long
    ) : RemoteMessage()

    data class LibrarySummary(
        val genres: List<GenreCount>,
        val years: List<YearCount>,
        val total: Int,
        val requestId: Long
    ) : RemoteMessage()

    data class LibraryArtworkRequest(
        val trackId: Long,
        val requestId: Long
    ) : RemoteMessage()

    data class LibraryArtworkResult(
        val trackId: Long,
        val base64: String,
        val width: Int,
        val height: Int,
        val requestId: Long
    ) : RemoteMessage()

    // --- Protocol v2 Playlist Messages ---
    data class PlaylistsListRequest(
        val requestId: Long
    ) : RemoteMessage()

    data class PlaylistsList(
        val items: List<PlaylistRow>,
        val total: Int = items.size,
        val requestId: Long
    ) : RemoteMessage()

    data class PlaylistsTracksRequest(
        val playlistId: Long,
        val offset: Int,
        val limit: Int,
        val requestId: Long
    ) : RemoteMessage()

    data class PlaylistsTracks(
        val playlistId: Long,
        val rows: List<TrackRow>,
        val total: Int,
        val hasMore: Boolean,
        val requestId: Long,
        val trackIds: List<Long> = emptyList()
    ) : RemoteMessage()

    data class PlaylistsCreateRequest(
        val name: String,
        val requestId: Long
    ) : RemoteMessage()

    data class PlaylistsRenameRequest(
        val playlistId: Long,
        val name: String,
        val requestId: Long
    ) : RemoteMessage()

    data class PlaylistsDeleteRequest(
        val playlistId: Long,
        val requestId: Long
    ) : RemoteMessage()

    data class PlaylistsAddTrackRequest(
        val playlistId: Long,
        val trackId: Long,
        val requestId: Long
    ) : RemoteMessage()

    data class PlaylistsRemoveTrackRequest(
        val playlistId: Long,
        val trackId: Long,
        val requestId: Long
    ) : RemoteMessage()

    data class PlaylistsMutate(
        val ok: Boolean,
        val playlist: PlaylistRow?,
        val requestId: Long
    ) : RemoteMessage()

    // --- Protocol v2 Queue Messages ---
    data class QueueStateRequest(
        val requestId: Long,
        val offset: Int = 0,
        val limit: Int = 20
    ) : RemoteMessage()

    data class QueueState(
        val requestId: Long,
        val entries: List<QueueEntryRow>,
        val currentEntryId: Long?,
        val repeatMode: String,
        val shuffleEnabled: Boolean,
        val revision: Long = 0L,
        val totalCount: Int = entries.size,
        val offset: Int = 0
    ) : RemoteMessage()

    data class QueueReplaceRequest(
        val trackIds: List<Long>,
        val startIndex: Int,
        val shuffled: Boolean,
        val requestId: Long
    ) : RemoteMessage()

    data class QueuePlayNextRequest(
        val trackIds: List<Long>,
        val requestId: Long
    ) : RemoteMessage()

    data class QueueAddToUpNextRequest(
        val trackIds: List<Long>,
        val requestId: Long
    ) : RemoteMessage()

    data class QueueRemoveEntryRequest(
        val entryId: Long,
        val requestId: Long
    ) : RemoteMessage()

    data class QueueMoveEntryRequest(
        val entryId: Long,
        val delta: Int,
        val requestId: Long
    ) : RemoteMessage()

    data class QueuePromoteEntryRequest(
        val entryId: Long,
        val requestId: Long
    ) : RemoteMessage()

    data class QueueShuffleToggleRequest(
        val requestId: Long
    ) : RemoteMessage()

    data class QueueRepeatCycleRequest(
        val requestId: Long
    ) : RemoteMessage()

    data class QueueClearUpNextRequest(
        val requestId: Long
    ) : RemoteMessage()

    data class QueueClearRemainingRequest(
        val requestId: Long
    ) : RemoteMessage()

    data class QueueClearRequest(
        val requestId: Long
    ) : RemoteMessage()

    data class QueueMutate(
        val ok: Boolean,
        val revision: Long,
        val requestId: Long
    ) : RemoteMessage()

    // --- Protocol v2 Push Events ---
    data class EventQueueChanged(
        val reason: String,
        val revision: Long
    ) : RemoteMessage()

    data class EventLibraryChanged(
        val revision: Long
    ) : RemoteMessage()

    data class Unknown(val raw: String) : RemoteMessage()
}

object RemoteProtocol {
    const val PROTOCOL_VERSION = 2
    const val SERVICE_NAME = "Y2Remote"
    const val CONTROLLER_DEVICE_NAME = "Y2 Controller"
    const val DEFAULT_SEEK_STEP_MS = 10000L
    const val MAX_LINE_LENGTH = 262144

    val UUID_Y2_REMOTE: UUID = UUID.fromString("a9c336b4-2dfb-4f9e-a89e-21ef1c60f4e1")
    val UUID_STANDARD_SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    const val STATUS_PLAYING = "playing"
    const val STATUS_PAUSED = "paused"
    const val STATUS_STOPPED = "stopped"

    // V1 Message Types
    const val TYPE_HELLO = "hello"
    const val TYPE_STATE = "state"
    const val TYPE_ARTWORK = "artwork"
    const val TYPE_COMMAND = "command"

    // V2 Library Types
    const val TYPE_LIBRARY_PAGE = "library.page"
    const val TYPE_LIBRARY_PAGE_RESULT = "library.page_result"
    const val TYPE_LIBRARY_SUMMARY = "library.summary"
    const val TYPE_LIBRARY_SUMMARY_RESULT = "library.summary_result"
    const val TYPE_LIBRARY_ARTWORK = "library.artwork"
    const val TYPE_LIBRARY_ARTWORK_RESULT = "library.artwork_result"

    // V2 Playlist Types
    const val TYPE_PLAYLISTS_LIST = "playlists.list"
    const val TYPE_PLAYLISTS_LIST_RESULT = "playlists.list_result"
    const val TYPE_PLAYLISTS_TRACKS = "playlists.tracks"
    const val TYPE_PLAYLISTS_TRACKS_RESULT = "playlists.tracks_result"
    const val TYPE_PLAYLISTS_CREATE = "playlists.create"
    const val TYPE_PLAYLISTS_RENAME = "playlists.rename"
    const val TYPE_PLAYLISTS_DELETE = "playlists.delete"
    const val TYPE_PLAYLISTS_ADD_TRACK = "playlists.add_track"
    const val TYPE_PLAYLISTS_REMOVE_TRACK = "playlists.remove_track"
    const val TYPE_PLAYLISTS_MUTATE = "playlists.mutate"

    // V2 Queue Types
    const val TYPE_QUEUE_STATE = "queue.state"
    const val TYPE_QUEUE_STATE_RESULT = "queue.state_result"
    const val TYPE_QUEUE_REPLACE = "queue.replace"
    const val TYPE_QUEUE_PLAY_NEXT = "queue.play_next"
    const val TYPE_QUEUE_ADD_TO_UP_NEXT = "queue.add_to_up_next"
    const val TYPE_QUEUE_REMOVE = "queue.remove"
    const val TYPE_QUEUE_MOVE = "queue.move"
    const val TYPE_QUEUE_PROMOTE = "queue.promote"
    const val TYPE_QUEUE_SHUFFLE_TOGGLE = "queue.shuffle.toggle"
    const val TYPE_QUEUE_REPEAT_CYCLE = "queue.repeat.cycle"
    const val TYPE_QUEUE_CLEAR_UP_NEXT = "queue.clear_up_next"
    const val TYPE_QUEUE_CLEAR_REMAINING = "queue.clear_remaining"
    const val TYPE_QUEUE_CLEAR = "queue.clear"
    const val TYPE_QUEUE_MUTATE = "queue.mutate"

    // V2 Events
    const val TYPE_EVENT_QUEUE_CHANGED = "event.queue_changed"
    const val TYPE_EVENT_LIBRARY_CHANGED = "event.library_changed"

    // Commands
    const val CMD_TOGGLE = "toggle"
    const val CMD_PLAY = "play"
    const val CMD_PAUSE = "pause"
    const val CMD_NEXT = "next"
    const val CMD_PREVIOUS = "previous"
    const val CMD_VOLUME_UP = "volume_up"
    const val CMD_VOLUME_DOWN = "volume_down"
    const val CMD_SET_VOLUME = "set_volume"
    const val CMD_REWIND = "rewind"
    const val CMD_FORWARD = "forward"
    const val CMD_SEEK = "seek"

    fun encodeHello(device: String = CONTROLLER_DEVICE_NAME, protocol: Int = PROTOCOL_VERSION): String {
        val json = JSONObject()
        json.put("type", TYPE_HELLO)
        json.put("device", device)
        json.put("protocol", protocol)
        return json.toString()
    }

    fun encodeCommand(command: RemoteCommand): String {
        val json = JSONObject()
        json.put("type", TYPE_COMMAND)
        when (command) {
            is RemoteCommand.Toggle -> json.put("command", CMD_TOGGLE)
            is RemoteCommand.Play -> json.put("command", CMD_PLAY)
            is RemoteCommand.Pause -> json.put("command", CMD_PAUSE)
            is RemoteCommand.Next -> json.put("command", CMD_NEXT)
            is RemoteCommand.Previous -> json.put("command", CMD_PREVIOUS)
            is RemoteCommand.VolumeUp -> json.put("command", CMD_VOLUME_UP)
            is RemoteCommand.VolumeDown -> json.put("command", CMD_VOLUME_DOWN)
            is RemoteCommand.SetVolume -> {
                json.put("command", CMD_SET_VOLUME)
                json.put("percent", command.percent.coerceIn(0, 100))
            }
            is RemoteCommand.Rewind -> {
                json.put("command", CMD_REWIND)
                json.put("amount", command.amountMs)
            }
            is RemoteCommand.Forward -> {
                json.put("command", CMD_FORWARD)
                json.put("amount", command.amountMs)
            }
            is RemoteCommand.Seek -> {
                json.put("command", CMD_SEEK)
                json.put("position", command.positionMs)
            }
        }
        return json.toString()
    }

    // --- V2 Encoders ---

    fun encodeLibraryPage(scope: String, sort: String, query: String, offset: Int, limit: Int, requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_LIBRARY_PAGE)
            put("id", requestId)
            put("scope", scope)
            put("sort", sort)
            put("query", query)
            put("offset", offset)
            put("limit", limit)
        }.toString()
    }

    fun encodeLibraryPageResult(requestId: Long, total: Int, hasMore: Boolean, rows: List<TrackRow>): String {
        val rowsArray = JSONArray()
        rows.forEach { rowsArray.put(it.toJson()) }
        return JSONObject().apply {
            put("type", TYPE_LIBRARY_PAGE_RESULT)
            put("id", requestId)
            put("total", total)
            put("hasMore", hasMore)
            put("rows", rowsArray)
        }.toString()
    }

    fun encodeLibrarySummaryRequest(requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_LIBRARY_SUMMARY)
            put("id", requestId)
        }.toString()
    }

    fun encodeLibrarySummary(genres: List<GenreCount>, years: List<YearCount>, total: Int, requestId: Long): String {
        val genresArray = JSONArray().apply { genres.forEach { put(it.toJson()) } }
        val yearsArray = JSONArray().apply { years.forEach { put(it.toJson()) } }
        return JSONObject().apply {
            put("type", TYPE_LIBRARY_SUMMARY_RESULT)
            put("id", requestId)
            put("genres", genresArray)
            put("years", yearsArray)
            put("total", total)
        }.toString()
    }

    fun encodeLibraryArtworkRequest(trackId: Long, requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_LIBRARY_ARTWORK)
            put("id", requestId)
            put("trackId", trackId)
        }.toString()
    }

    fun encodeLibraryArtworkResult(trackId: Long, base64: String, width: Int, height: Int, requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_LIBRARY_ARTWORK_RESULT)
            put("id", requestId)
            put("trackId", trackId)
            put("data", base64)
            put("width", width)
            put("height", height)
        }.toString()
    }

    fun encodePlaylistsListRequest(requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_PLAYLISTS_LIST)
            put("id", requestId)
        }.toString()
    }

    fun encodePlaylistsList(items: List<PlaylistRow>, requestId: Long, total: Int = items.size): String {
        val itemsArray = JSONArray().apply { items.forEach { put(it.toJson()) } }
        return JSONObject().apply {
            put("type", TYPE_PLAYLISTS_LIST_RESULT)
            put("id", requestId)
            put("items", itemsArray)
            put("total", total)
        }.toString()
    }

    fun encodePlaylistsTracksRequest(playlistId: Long, offset: Int, limit: Int, requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_PLAYLISTS_TRACKS)
            put("id", requestId)
            put("playlistId", playlistId)
            put("offset", offset)
            put("limit", limit)
        }.toString()
    }

    fun encodePlaylistsTracks(
        playlistId: Long,
        rows: List<TrackRow>,
        total: Int,
        hasMore: Boolean,
        requestId: Long,
        trackIds: List<Long> = emptyList()
    ): String {
        return JSONObject().apply {
            put("type", TYPE_PLAYLISTS_TRACKS_RESULT)
            put("id", requestId)
            put("playlistId", playlistId)
            if (trackIds.isNotEmpty()) {
                put("trackIds", JSONArray().apply { trackIds.forEach { put(it) } })
            }
            if (rows.isNotEmpty()) {
                put("rows", JSONArray().apply { rows.forEach { put(it.toJson()) } })
            }
            put("total", total)
            put("hasMore", hasMore)
        }.toString()
    }

    fun encodePlaylistsCreate(name: String, requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_PLAYLISTS_CREATE)
            put("id", requestId)
            put("name", name)
        }.toString()
    }

    fun encodePlaylistsRename(playlistId: Long, name: String, requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_PLAYLISTS_RENAME)
            put("id", requestId)
            put("playlistId", playlistId)
            put("name", name)
        }.toString()
    }

    fun encodePlaylistsDelete(playlistId: Long, requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_PLAYLISTS_DELETE)
            put("id", requestId)
            put("playlistId", playlistId)
        }.toString()
    }

    fun encodePlaylistsAddTrack(playlistId: Long, trackId: Long, requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_PLAYLISTS_ADD_TRACK)
            put("id", requestId)
            put("playlistId", playlistId)
            put("trackId", trackId)
        }.toString()
    }

    fun encodePlaylistsRemoveTrack(playlistId: Long, trackId: Long, requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_PLAYLISTS_REMOVE_TRACK)
            put("id", requestId)
            put("playlistId", playlistId)
            put("trackId", trackId)
        }.toString()
    }

    fun encodePlaylistsMutate(ok: Boolean, playlist: PlaylistRow?, requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_PLAYLISTS_MUTATE)
            put("id", requestId)
            put("ok", ok)
            if (playlist != null) put("playlist", playlist.toJson())
        }.toString()
    }

    fun encodeQueueStateRequest(requestId: Long, offset: Int = 0, limit: Int = 100): String {
        return JSONObject().apply {
            put("type", TYPE_QUEUE_STATE)
            put("id", requestId)
            put("offset", offset)
            put("limit", limit)
        }.toString()
    }

    fun encodeQueueState(
        requestId: Long,
        entries: List<QueueEntryRow>,
        currentEntryId: Long?,
        repeatMode: String,
        shuffleEnabled: Boolean,
        revision: Long,
        totalCount: Int = entries.size,
        offset: Int = 0
    ): String {
        val entriesArray = JSONArray().apply { entries.forEach { put(it.toJson()) } }
        return JSONObject().apply {
            put("type", TYPE_QUEUE_STATE_RESULT)
            put("id", requestId)
            put("entries", entriesArray)
            if (currentEntryId != null) put("currentEntryId", currentEntryId) else put("currentEntryId", JSONObject.NULL)
            put("repeatMode", repeatMode)
            put("shuffleEnabled", shuffleEnabled)
            put("revision", revision)
            put("total", totalCount)
            put("offset", offset)
        }.toString()
    }

    fun encodeQueueReplace(trackIds: List<Long>, startIndex: Int, shuffled: Boolean, requestId: Long): String {
        val idsArray = JSONArray().apply { trackIds.forEach { put(it) } }
        return JSONObject().apply {
            put("type", TYPE_QUEUE_REPLACE)
            put("id", requestId)
            put("trackIds", idsArray)
            put("startIndex", startIndex)
            put("shuffled", shuffled)
        }.toString()
    }

    fun encodeQueuePlayNext(trackIds: List<Long>, requestId: Long): String {
        val idsArray = JSONArray().apply { trackIds.forEach { put(it) } }
        return JSONObject().apply {
            put("type", TYPE_QUEUE_PLAY_NEXT)
            put("id", requestId)
            put("trackIds", idsArray)
        }.toString()
    }

    fun encodeQueueAddToUpNext(trackIds: List<Long>, requestId: Long): String {
        val idsArray = JSONArray().apply { trackIds.forEach { put(it) } }
        return JSONObject().apply {
            put("type", TYPE_QUEUE_ADD_TO_UP_NEXT)
            put("id", requestId)
            put("trackIds", idsArray)
        }.toString()
    }

    fun encodeQueueRemoveEntry(entryId: Long, requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_QUEUE_REMOVE)
            put("id", requestId)
            put("entryId", entryId)
        }.toString()
    }

    fun encodeQueueMoveEntry(entryId: Long, delta: Int, requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_QUEUE_MOVE)
            put("id", requestId)
            put("entryId", entryId)
            put("delta", delta)
        }.toString()
    }

    fun encodeQueuePromoteEntry(entryId: Long, requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_QUEUE_PROMOTE)
            put("id", requestId)
            put("entryId", entryId)
        }.toString()
    }

    fun encodeQueueShuffleToggle(requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_QUEUE_SHUFFLE_TOGGLE)
            put("id", requestId)
        }.toString()
    }

    fun encodeQueueRepeatCycle(requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_QUEUE_REPEAT_CYCLE)
            put("id", requestId)
        }.toString()
    }

    fun encodeQueueClearUpNext(requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_QUEUE_CLEAR_UP_NEXT)
            put("id", requestId)
        }.toString()
    }

    fun encodeQueueClearRemaining(requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_QUEUE_CLEAR_REMAINING)
            put("id", requestId)
        }.toString()
    }

    fun encodeQueueClear(requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_QUEUE_CLEAR)
            put("id", requestId)
        }.toString()
    }

    fun encodeQueueMutate(ok: Boolean, revision: Long, requestId: Long): String {
        return JSONObject().apply {
            put("type", TYPE_QUEUE_MUTATE)
            put("id", requestId)
            put("ok", ok)
            put("revision", revision)
        }.toString()
    }

    fun encodeEventQueueChanged(reason: String, revision: Long): String {
        return JSONObject().apply {
            put("type", TYPE_EVENT_QUEUE_CHANGED)
            put("reason", reason)
            put("revision", revision)
        }.toString()
    }

    fun encodeEventLibraryChanged(revision: Long): String {
        return JSONObject().apply {
            put("type", TYPE_EVENT_LIBRARY_CHANGED)
            put("revision", revision)
        }.toString()
    }

    fun parseMessage(line: String): RemoteMessage? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LINE_LENGTH) return null
        return runCatching {
            val json = JSONObject(trimmed)
            when (json.optString("type")) {
                TYPE_HELLO -> {
                    val device = json.optString("device", "Unknown")
                    val protocol = json.optInt("protocol", 1)
                    RemoteMessage.Hello(device = device, protocol = protocol)
                }
                TYPE_STATE -> {
                    RemoteMessage.PlayerState(
                        title = json.optString("title", ""),
                        artist = json.optString("artist", ""),
                        album = json.optString("album", ""),
                        status = json.optString("status", STATUS_STOPPED),
                        positionMs = json.optLong("position", 0L),
                        durationMs = json.optLong("duration", 0L),
                        volumePercent = json.optInt("volume", 100)
                    )
                }
                TYPE_ARTWORK -> {
                    val data = json.optString("data", "")
                    if (data.isNotEmpty()) RemoteMessage.Artwork(data) else null
                }
                TYPE_COMMAND -> {
                    val cmd = parseCommand(json) ?: return null
                    RemoteMessage.Command(cmd)
                }
                TYPE_LIBRARY_PAGE -> {
                    RemoteMessage.LibraryPage(
                        scope = json.optString("scope", "all"),
                        sort = json.optString("sort", "title"),
                        query = json.optString("query", ""),
                        offset = json.optInt("offset", 0),
                        limit = json.optInt("limit", 50),
                        requestId = json.optLong("id", 0L)
                    )
                }
                TYPE_LIBRARY_PAGE_RESULT -> {
                    val rowsArray = json.optJSONArray("rows") ?: JSONArray()
                    val rows = (0 until rowsArray.length()).map { i ->
                        TrackRow.fromJson(rowsArray.getJSONObject(i))
                    }
                    RemoteMessage.LibraryPageResult(
                        requestId = json.optLong("id", 0L),
                        total = json.optInt("total", 0),
                        hasMore = json.optBoolean("hasMore", false),
                        rows = rows
                    )
                }
                TYPE_LIBRARY_SUMMARY -> {
                    RemoteMessage.LibrarySummaryRequest(requestId = json.optLong("id", 0L))
                }
                TYPE_LIBRARY_SUMMARY_RESULT -> {
                    val genresArray = json.optJSONArray("genres") ?: JSONArray()
                    val genres = (0 until genresArray.length()).map { i ->
                        GenreCount.fromJson(genresArray.getJSONObject(i))
                    }
                    val yearsArray = json.optJSONArray("years") ?: JSONArray()
                    val years = (0 until yearsArray.length()).map { i ->
                        YearCount.fromJson(yearsArray.getJSONObject(i))
                    }
                    RemoteMessage.LibrarySummary(
                        genres = genres,
                        years = years,
                        total = json.optInt("total", 0),
                        requestId = json.optLong("id", 0L)
                    )
                }
                TYPE_LIBRARY_ARTWORK -> {
                    RemoteMessage.LibraryArtworkRequest(
                        trackId = json.optLong("trackId", 0L),
                        requestId = json.optLong("id", 0L)
                    )
                }
                TYPE_LIBRARY_ARTWORK_RESULT -> {
                    RemoteMessage.LibraryArtworkResult(
                        trackId = json.optLong("trackId", 0L),
                        base64 = json.optString("data", ""),
                        width = json.optInt("width", 0),
                        height = json.optInt("height", 0),
                        requestId = json.optLong("id", 0L)
                    )
                }
                TYPE_PLAYLISTS_LIST -> {
                    RemoteMessage.PlaylistsListRequest(requestId = json.optLong("id", 0L))
                }
                TYPE_PLAYLISTS_LIST_RESULT -> {
                    val itemsArray = json.optJSONArray("items") ?: JSONArray()
                    val items = (0 until itemsArray.length()).map { i ->
                        PlaylistRow.fromJson(itemsArray.getJSONObject(i))
                    }
                    val total = json.optInt("total", items.size)
                    RemoteMessage.PlaylistsList(items = items, total = total, requestId = json.optLong("id", 0L))
                }
                TYPE_PLAYLISTS_TRACKS -> {
                    RemoteMessage.PlaylistsTracksRequest(
                        playlistId = json.optLong("playlistId", 0L),
                        offset = json.optInt("offset", 0),
                        limit = json.optInt("limit", 50),
                        requestId = json.optLong("id", 0L)
                    )
                }
                TYPE_PLAYLISTS_TRACKS_RESULT -> {
                    val trackIdsArray = json.optJSONArray("trackIds")
                    val trackIds = if (trackIdsArray != null) {
                        (0 until trackIdsArray.length()).map { trackIdsArray.getLong(it) }
                    } else emptyList()

                    val rowsArray = json.optJSONArray("rows") ?: JSONArray()
                    val rows = (0 until rowsArray.length()).map { i ->
                        TrackRow.fromJson(rowsArray.getJSONObject(i))
                    }
                    RemoteMessage.PlaylistsTracks(
                        playlistId = json.optLong("playlistId", 0L),
                        rows = rows,
                        total = json.optInt("total", 0),
                        hasMore = json.optBoolean("hasMore", false),
                        requestId = json.optLong("id", 0L),
                        trackIds = trackIds
                    )
                }
                TYPE_PLAYLISTS_CREATE -> {
                    RemoteMessage.PlaylistsCreateRequest(
                        name = json.optString("name", ""),
                        requestId = json.optLong("id", 0L)
                    )
                }
                TYPE_PLAYLISTS_RENAME -> {
                    RemoteMessage.PlaylistsRenameRequest(
                        playlistId = json.optLong("playlistId", 0L),
                        name = json.optString("name", ""),
                        requestId = json.optLong("id", 0L)
                    )
                }
                TYPE_PLAYLISTS_DELETE -> {
                    RemoteMessage.PlaylistsDeleteRequest(
                        playlistId = json.optLong("playlistId", 0L),
                        requestId = json.optLong("id", 0L)
                    )
                }
                TYPE_PLAYLISTS_ADD_TRACK -> {
                    RemoteMessage.PlaylistsAddTrackRequest(
                        playlistId = json.optLong("playlistId", 0L),
                        trackId = json.optLong("trackId", 0L),
                        requestId = json.optLong("id", 0L)
                    )
                }
                TYPE_PLAYLISTS_REMOVE_TRACK -> {
                    RemoteMessage.PlaylistsRemoveTrackRequest(
                        playlistId = json.optLong("playlistId", 0L),
                        trackId = json.optLong("trackId", 0L),
                        requestId = json.optLong("id", 0L)
                    )
                }
                TYPE_PLAYLISTS_MUTATE -> {
                    RemoteMessage.PlaylistsMutate(
                        ok = json.optBoolean("ok", false),
                        playlist = json.optJSONObject("playlist")?.let { PlaylistRow.fromJson(it) },
                        requestId = json.optLong("id", 0L)
                    )
                }
                TYPE_QUEUE_STATE -> {
                    RemoteMessage.QueueStateRequest(
                        requestId = json.optLong("id", 0L),
                        offset = json.optInt("offset", 0),
                        limit = json.optInt("limit", 100)
                    )
                }
                TYPE_QUEUE_STATE_RESULT -> {
                    val entriesArray = json.optJSONArray("entries") ?: JSONArray()
                    val entries = (0 until entriesArray.length()).map { i ->
                        QueueEntryRow.fromJson(entriesArray.getJSONObject(i))
                    }
                    val currentEntryId = if (json.has("currentEntryId") && !json.isNull("currentEntryId")) {
                        json.optLong("currentEntryId")
                    } else null
                    RemoteMessage.QueueState(
                        requestId = json.optLong("id", 0L),
                        entries = entries,
                        currentEntryId = currentEntryId,
                        repeatMode = json.optString("repeatMode", "OFF"),
                        shuffleEnabled = json.optBoolean("shuffleEnabled", false),
                        revision = json.optLong("revision", 0L),
                        totalCount = json.optInt("total", entries.size),
                        offset = json.optInt("offset", 0)
                    )
                }
                TYPE_QUEUE_REPLACE -> {
                    val idsArray = json.optJSONArray("trackIds") ?: JSONArray()
                    val trackIds = (0 until idsArray.length()).map { idsArray.getLong(it) }
                    RemoteMessage.QueueReplaceRequest(
                        trackIds = trackIds,
                        startIndex = json.optInt("startIndex", 0),
                        shuffled = json.optBoolean("shuffled", false),
                        requestId = json.optLong("id", 0L)
                    )
                }
                TYPE_QUEUE_PLAY_NEXT -> {
                    val idsArray = json.optJSONArray("trackIds") ?: JSONArray()
                    val trackIds = (0 until idsArray.length()).map { idsArray.getLong(it) }
                    RemoteMessage.QueuePlayNextRequest(
                        trackIds = trackIds,
                        requestId = json.optLong("id", 0L)
                    )
                }
                TYPE_QUEUE_ADD_TO_UP_NEXT -> {
                    val idsArray = json.optJSONArray("trackIds") ?: JSONArray()
                    val trackIds = (0 until idsArray.length()).map { idsArray.getLong(it) }
                    RemoteMessage.QueueAddToUpNextRequest(
                        trackIds = trackIds,
                        requestId = json.optLong("id", 0L)
                    )
                }
                TYPE_QUEUE_REMOVE -> {
                    RemoteMessage.QueueRemoveEntryRequest(
                        entryId = json.optLong("entryId", 0L),
                        requestId = json.optLong("id", 0L)
                    )
                }
                TYPE_QUEUE_MOVE -> {
                    RemoteMessage.QueueMoveEntryRequest(
                        entryId = json.optLong("entryId", 0L),
                        delta = json.optInt("delta", 0),
                        requestId = json.optLong("id", 0L)
                    )
                }
                TYPE_QUEUE_PROMOTE -> {
                    RemoteMessage.QueuePromoteEntryRequest(
                        entryId = json.optLong("entryId", 0L),
                        requestId = json.optLong("id", 0L)
                    )
                }
                TYPE_QUEUE_SHUFFLE_TOGGLE -> {
                    RemoteMessage.QueueShuffleToggleRequest(requestId = json.optLong("id", 0L))
                }
                TYPE_QUEUE_REPEAT_CYCLE -> {
                    RemoteMessage.QueueRepeatCycleRequest(requestId = json.optLong("id", 0L))
                }
                TYPE_QUEUE_CLEAR_UP_NEXT -> {
                    RemoteMessage.QueueClearUpNextRequest(requestId = json.optLong("id", 0L))
                }
                TYPE_QUEUE_CLEAR_REMAINING -> {
                    RemoteMessage.QueueClearRemainingRequest(requestId = json.optLong("id", 0L))
                }
                TYPE_QUEUE_CLEAR -> {
                    RemoteMessage.QueueClearRequest(requestId = json.optLong("id", 0L))
                }
                TYPE_QUEUE_MUTATE -> {
                    RemoteMessage.QueueMutate(
                        ok = json.optBoolean("ok", false),
                        revision = json.optLong("revision", 0L),
                        requestId = json.optLong("id", 0L)
                    )
                }
                TYPE_EVENT_QUEUE_CHANGED -> {
                    RemoteMessage.EventQueueChanged(
                        reason = json.optString("reason", ""),
                        revision = json.optLong("revision", 0L)
                    )
                }
                TYPE_EVENT_LIBRARY_CHANGED -> {
                    RemoteMessage.EventLibraryChanged(
                        revision = json.optLong("revision", 0L)
                    )
                }
                else -> RemoteMessage.Unknown(trimmed)
            }
        }.getOrNull()
    }

    fun parseCommand(json: JSONObject): RemoteCommand? {
        return when (json.optString("command")) {
            CMD_TOGGLE -> RemoteCommand.Toggle
            CMD_PLAY -> RemoteCommand.Play
            CMD_PAUSE -> RemoteCommand.Pause
            CMD_NEXT -> RemoteCommand.Next
            CMD_PREVIOUS -> RemoteCommand.Previous
            CMD_VOLUME_UP -> RemoteCommand.VolumeUp
            CMD_VOLUME_DOWN -> RemoteCommand.VolumeDown
            CMD_SET_VOLUME -> RemoteCommand.SetVolume(json.optInt("percent", 100))
            CMD_REWIND -> RemoteCommand.Rewind(json.optLong("amount", DEFAULT_SEEK_STEP_MS))
            CMD_FORWARD -> RemoteCommand.Forward(json.optLong("amount", DEFAULT_SEEK_STEP_MS))
            CMD_SEEK -> RemoteCommand.Seek(json.optLong("position", 0L))
            else -> null
        }
    }
}

