package com.nokia_xd.y2remote.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.nokia_xd.y2remote.protocol.TrackRow

class LocalLibraryPagingSource(
    private val database: LocalLibraryDatabase,
    private val sort: String,
    private val query: String
) : PagingSource<Int, TrackRow>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TrackRow> {
        val offset = params.key ?: 0
        val limit = params.loadSize.coerceIn(1, 100)

        val rows = database.getPagedTracks(sort, query, offset, limit)
        val total = database.getTrackCount(query)
        val hasMore = (offset + rows.size) < total

        return LoadResult.Page(
            data = rows,
            prevKey = if (offset == 0) null else (offset - limit).coerceAtLeast(0),
            nextKey = if (hasMore && rows.isNotEmpty()) offset + rows.size else null
        )
    }

    override fun getRefreshKey(state: PagingState<Int, TrackRow>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey?.plus(50)
                ?: state.closestPageToPosition(anchorPosition)?.nextKey?.minus(50)
        }
    }
}
