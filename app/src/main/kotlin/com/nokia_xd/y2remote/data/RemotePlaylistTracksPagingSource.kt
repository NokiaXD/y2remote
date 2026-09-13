package com.nokia_xd.y2remote.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.nokia_xd.y2remote.bluetooth.BluetoothConnectionManager
import com.nokia_xd.y2remote.protocol.TrackRow
import java.io.IOException

class RemotePlaylistTracksPagingSource(
    private val connection: BluetoothConnectionManager,
    private val cache: LibraryCache,
    private val playlistId: Long
) : PagingSource<Int, TrackRow>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TrackRow> {
        val offset = params.key ?: 0
        val limit = params.loadSize.coerceIn(1, 50)
        val resp = connection.requestPlaylistsTracks(playlistId, offset, limit)
            ?: return LoadResult.Error(IOException("Failed to receive playlist tracks from Y2"))

        cache.putAll(resp.rows)

        return LoadResult.Page(
            data = resp.rows,
            prevKey = if (offset == 0) null else (offset - limit).coerceAtLeast(0),
            nextKey = if (resp.hasMore) offset + limit else null
        )
    }

    override fun getRefreshKey(state: PagingState<Int, TrackRow>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey?.plus(50)
                ?: state.closestPageToPosition(anchorPosition)?.nextKey?.minus(50)
        }
    }
}
