package com.nokia_xd.y2remote.data

import android.util.LruCache
import com.nokia_xd.y2remote.protocol.TrackRow

class LibraryCache(maxEntries: Int = 5000) {
    private val memory = LruCache<Long, TrackRow>(maxEntries)

    fun get(trackId: Long): TrackRow? = memory.get(trackId)

    fun put(track: TrackRow) {
        memory.put(track.id, track)
    }

    fun putAll(tracks: List<TrackRow>) {
        tracks.forEach { memory.put(it.id, it) }
    }

    fun clear() {
        memory.evictAll()
    }
}
