package com.nokia_xd.y2remote.ui

import com.nokia_xd.y2remote.protocol.TrackRow

enum class TileType {
    ALBUM,
    ARTIST,
    YEAR
}

data class MediaTile(
    val id: String,
    val title: String,
    val subtitle: String,
    val artworkTrackId: Long?,
    val tracks: List<TrackRow>,
    val type: TileType
)
