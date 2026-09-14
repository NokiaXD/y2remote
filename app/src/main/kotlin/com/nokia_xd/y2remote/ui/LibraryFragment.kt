package com.nokia_xd.y2remote.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.paging.LoadState
import com.nokia_xd.y2remote.R
import com.nokia_xd.y2remote.data.ArtistCredit
import com.nokia_xd.y2remote.databinding.FragmentLibraryBinding
import com.nokia_xd.y2remote.protocol.TrackRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewModel by activityViewModels()

    private var trackAdapter: TrackAdapter? = null
    private var tileAdapter: MediaTileAdapter? = null

    private enum class LibraryMode {
        TRACKS,
        ARTISTS,
        ALBUMS,
        YEARS
    }

    private var currentMode = LibraryMode.TRACKS
    private var currentQuery = ""
    private var currentTiles = listOf<MediaTile>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupAdapters()
        setupSortChips()
        setupSearch()
        setupSync()
        switchToMode(currentMode)
        observeViewModel()
    }

    private fun setupSync() {
        binding.btnSyncLibrary.setOnClickListener {
            viewModel.syncLibrary()
        }
        binding.btnEmptySync.setOnClickListener {
            viewModel.syncLibrary()
        }
    }

    private fun setupAdapters() {
        trackAdapter = TrackAdapter(
            artworkCache = viewModel.artworkCache,
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            onTrackClick = { track ->
                viewModel.replaceQueue(listOf(track.id), startIndex = 0, shuffled = false)
            },
            onTrackAction = { track, action ->
                when (action) {
                    TrackAdapter.TrackAction.PLAY_NOW -> viewModel.replaceQueue(listOf(track.id))
                    TrackAdapter.TrackAction.PLAY_NEXT -> viewModel.playNext(listOf(track.id))
                    TrackAdapter.TrackAction.ADD_TO_UP_NEXT -> viewModel.addToUpNext(listOf(track.id))
                    TrackAdapter.TrackAction.ADD_TO_PLAYLIST -> showAddToPlaylistDialog(track)
                    TrackAdapter.TrackAction.REMOVE_FROM_PLAYLIST -> {}
                }
            }
        )

        tileAdapter = MediaTileAdapter(
            artworkCache = viewModel.artworkCache,
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            onTileClick = { tile ->
                handleTileClick(tile)
            }
        )

        binding.rvLibraryTracks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLibraryTracks.adapter = trackAdapter

        binding.rvLibraryTracks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                val isSettling = newState == RecyclerView.SCROLL_STATE_SETTLING
                trackAdapter?.isFastScrolling = isSettling
                tileAdapter?.isFastScrolling = isSettling

                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    if (currentMode == LibraryMode.TRACKS) {
                        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                        val first = lm.findFirstVisibleItemPosition()
                        val last = lm.findLastVisibleItemPosition()
                        if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
                            for (i in first..last) {
                                val holder = recyclerView.findViewHolderForAdapterPosition(i) as? TrackAdapter.TrackViewHolder
                                val item = trackAdapter?.peek(i)
                                if (holder != null && item != null) {
                                    holder.loadArtwork(item)
                                }
                            }
                        }
                    } else {
                        val glm = recyclerView.layoutManager as? GridLayoutManager ?: return
                        val first = glm.findFirstVisibleItemPosition()
                        val last = glm.findLastVisibleItemPosition()
                        if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
                            for (i in first..last) {
                                val holder = recyclerView.findViewHolderForAdapterPosition(i) as? MediaTileAdapter.TileViewHolder
                                val item = tileAdapter?.currentList?.getOrNull(i)
                                if (holder != null && item != null) {
                                    holder.loadArtwork(item)
                                }
                            }
                        }
                    }
                }
            }
        })
    }

    private fun handleTileClick(tile: MediaTile) {
        when (tile.type) {
            TileType.ALBUM -> {
                viewModel.selectTile(tile)
                MediaGroupDetailDialogFragment.newInstance().show(childFragmentManager, "media_group_detail")
            }
            TileType.ARTIST -> {
                viewModel.selectTile(tile)
                ArtistYearOverviewDialogFragment.newInstance().show(childFragmentManager, "artist_year_overview")
            }
            TileType.YEAR -> {
                if (tile.tracks.isEmpty()) {
                    binding.progressBarLibrary.visibility = View.VISIBLE
                    viewModel.loadTracksForScope("year:${tile.title}") { yearTracks ->
                        binding.progressBarLibrary.visibility = View.GONE
                        val populatedTile = tile.copy(
                            tracks = yearTracks,
                            artworkTrackId = yearTracks.firstOrNull { it.hasArtwork }?.id ?: yearTracks.firstOrNull()?.id,
                            subtitle = if (yearTracks.size == 1) "1 song" else "${yearTracks.size} songs"
                        )
                        viewModel.selectTile(populatedTile)
                        ArtistYearOverviewDialogFragment.newInstance().show(childFragmentManager, "artist_year_overview")
                    }
                } else {
                    viewModel.selectTile(tile)
                    ArtistYearOverviewDialogFragment.newInstance().show(childFragmentManager, "artist_year_overview")
                }
            }
        }
    }

    private fun setupSortChips() {
        val targetChipId = when (currentMode) {
            LibraryMode.TRACKS -> when (viewModel.librarySort.value) {
                "added" -> R.id.chipSortAdded
                "recent" -> R.id.chipSortRecent
                else -> R.id.chipSortTitle
            }
            LibraryMode.ARTISTS -> R.id.chipSortArtist
            LibraryMode.ALBUMS -> R.id.chipSortAlbum
            LibraryMode.YEARS -> R.id.chipSortYear
        }
        binding.chipGroupSort.check(targetChipId)

        binding.chipGroupSort.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            when (checkedId) {
                R.id.chipSortArtist -> switchToMode(LibraryMode.ARTISTS)
                R.id.chipSortAlbum -> switchToMode(LibraryMode.ALBUMS)
                R.id.chipSortYear -> switchToMode(LibraryMode.YEARS)
                R.id.chipSortTitle -> {
                    viewModel.setLibrarySort("title")
                    switchToMode(LibraryMode.TRACKS)
                }
                R.id.chipSortAdded -> {
                    viewModel.setLibrarySort("added")
                    switchToMode(LibraryMode.TRACKS)
                }
                R.id.chipSortRecent -> {
                    viewModel.setLibrarySort("recent")
                    switchToMode(LibraryMode.TRACKS)
                }
            }
        }
    }

    private fun switchToMode(mode: LibraryMode) {
        currentMode = mode
        if (mode == LibraryMode.TRACKS) {
            binding.rvLibraryTracks.layoutManager = LinearLayoutManager(requireContext())
            binding.rvLibraryTracks.adapter = trackAdapter
            binding.layoutEmptyLibrary.visibility = View.GONE
            viewModel.setLibraryQuery(currentQuery)
            trackAdapter?.refresh()
        } else {
            binding.rvLibraryTracks.layoutManager = GridLayoutManager(requireContext(), 2)
            binding.rvLibraryTracks.adapter = tileAdapter
            refreshTilesForCurrentMode()
        }
    }

    private fun refreshTilesForCurrentMode() {
        if (currentMode == LibraryMode.TRACKS) return

        if (currentMode == LibraryMode.YEARS) {
            val summary = viewModel.librarySummary.value
            val years = summary?.years.orEmpty()
            if (years.isEmpty()) {
                viewModel.refreshLibrary()
            }
            val artMap = viewModel.yearArtworkMap.value

            val missingArtYears = years.filter { y ->
                val yrStr = y.year?.toString() ?: "Unknown Year"
                (y.artworkTrackId == null || y.artworkTrackId == 0L) && !artMap.containsKey(yrStr)
            }.mapNotNull { it.year?.toString() }

            if (missingArtYears.isNotEmpty()) {
                viewModel.loadArtworkForYears(missingArtYears)
            }

            val tiles = years.map { y ->
                val yrStr = y.year?.toString() ?: "Unknown Year"
                val resolvedArtTrackId = artMap[yrStr] ?: y.artworkTrackId?.takeIf { it > 0L }
                MediaTile(
                    id = "year:$yrStr",
                    title = yrStr,
                    subtitle = if (y.count == 1) "1 song" else "${y.count} songs",
                    artworkTrackId = resolvedArtTrackId,
                    tracks = emptyList(),
                    type = TileType.YEAR
                )
            }
            displayTiles(tiles)
            return
        }

        val allTracks = viewModel.allKnownTracks.value
        if (allTracks.isEmpty()) {
            binding.progressBarLibrary.visibility = View.VISIBLE
            viewModel.ensureFullLibraryLoaded { tracks ->
                binding.progressBarLibrary.visibility = View.GONE
                computeAndDisplayTiles(tracks)
            }
        } else {
            computeAndDisplayTiles(allTracks)
        }
    }

    private fun computeAndDisplayTiles(tracks: List<TrackRow>) {
        val tiles = when (currentMode) {
            LibraryMode.ALBUMS -> {
                // Group tracks strictly by album name (trimmed)
                tracks.filter { it.album.isNotBlank() }
                    .groupBy { it.album.trim() }
                    .map { (albumName, albumTracks) ->
                        val firstWithArt = albumTracks.firstOrNull { it.hasArtwork }?.id ?: albumTracks.firstOrNull()?.id
                        // Find the primary / dominant artist for this album (preventing feat splits)
                        val primaryArtists = albumTracks.map { ArtistCredit.primary(it.artist) }.filter { it.isNotBlank() }
                        val dominantArtist = primaryArtists.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                            ?: albumTracks.firstOrNull()?.artist.orEmpty()

                        val songsCount = if (albumTracks.size == 1) "1 song" else "${albumTracks.size} songs"
                        MediaTile(
                            id = "album:$albumName",
                            title = albumName,
                            subtitle = if (dominantArtist.isNotBlank()) "$dominantArtist • $songsCount" else songsCount,
                            artworkTrackId = firstWithArt,
                            tracks = albumTracks,
                            type = TileType.ALBUM
                        )
                    }
                    .sortedBy { it.title.lowercase() }
            }
            LibraryMode.ARTISTS -> {
                // Split multi-artist credits (feat, ft, commas, semicolons)
                val artistMap = mutableMapOf<String, MutableList<TrackRow>>()
                for (track in tracks) {
                    val names = ArtistCredit.names(track.artist)
                    for (name in names) {
                        artistMap.getOrPut(name) { mutableListOf() }.add(track)
                    }
                }

                artistMap.map { (artistName, artistTracks) ->
                    val firstWithArt = artistTracks.firstOrNull { it.hasArtwork }?.id ?: artistTracks.firstOrNull()?.id
                    val albumCount = artistTracks.map { it.album.trim() }.filter { it.isNotBlank() }.distinct().size
                    val albumStr = if (albumCount == 1) "1 album" else "$albumCount albums"
                    val songStr = if (artistTracks.size == 1) "1 song" else "${artistTracks.size} songs"
                    val subtitle = if (albumCount > 0) "$albumStr • $songStr" else songStr
                    MediaTile(
                        id = "artist:$artistName",
                        title = artistName,
                        subtitle = subtitle,
                        artworkTrackId = firstWithArt,
                        tracks = artistTracks,
                        type = TileType.ARTIST
                    )
                }.sortedBy { it.title.lowercase() }
            }
            else -> emptyList()
        }
        displayTiles(tiles)
    }

    private fun displayTiles(tiles: List<MediaTile>) {
        currentTiles = tiles
        val filtered = if (currentQuery.isBlank()) {
            tiles
        } else {
            tiles.filter { it.title.contains(currentQuery, ignoreCase = true) || it.subtitle.contains(currentQuery, ignoreCase = true) }
        }
        tileAdapter?.submitList(filtered)
        binding.tvEmptyLibrary.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                currentQuery = query.orEmpty().trim()
                if (currentMode == LibraryMode.TRACKS) {
                    viewModel.setLibraryQuery(currentQuery)
                } else {
                    displayTiles(currentTiles)
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                currentQuery = newText.orEmpty().trim()
                if (currentMode == LibraryMode.TRACKS) {
                    viewModel.setLibraryQuery(currentQuery)
                } else {
                    displayTiles(currentTiles)
                }
                return true
            }
        })
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    trackAdapter?.loadStateFlow?.collectLatest { loadStates ->
                        if (currentMode != LibraryMode.TRACKS) return@collectLatest
                        val isLoading = loadStates.refresh is LoadState.Loading
                        val hasItems = (trackAdapter?.itemCount ?: 0) > 0
                        binding.progressBarLibrary.visibility = if (isLoading && !hasItems) View.VISIBLE else View.GONE
                        val isListEmpty = loadStates.refresh is LoadState.NotLoading && !hasItems
                        val summary = viewModel.librarySummary.value
                        val isFeatureDisabled = summary != null && summary.total == -1
                        if (isFeatureDisabled) {
                            binding.tvEmptyLibrary.text = getString(R.string.feature_disabled_library)
                            binding.btnEmptySync.visibility = View.GONE
                            binding.layoutEmptyLibrary.visibility = View.VISIBLE
                        } else if (isListEmpty) {
                            binding.tvEmptyLibrary.text = getString(R.string.empty_library)
                            binding.btnEmptySync.visibility = View.VISIBLE
                            binding.layoutEmptyLibrary.visibility = View.VISIBLE
                        } else {
                            binding.layoutEmptyLibrary.visibility = View.GONE
                        }
                    }
                }

                launch {
                    combine(viewModel.syncProgress, viewModel.lastSyncTimestamp) { sync, lastSync ->
                        Pair(sync, lastSync)
                    }.collect { (sync, lastSync) ->
                        if (sync.isSyncing) {
                            binding.progressBarSync.visibility = View.VISIBLE
                            if (sync.total > 0) {
                                binding.progressBarSync.isIndeterminate = false
                                binding.progressBarSync.max = sync.total
                                binding.progressBarSync.progress = sync.current
                            } else {
                                binding.progressBarSync.isIndeterminate = true
                            }
                            binding.tvSyncStatus.text = sync.statusText.ifEmpty { getString(R.string.sync_in_progress) }
                        } else {
                            binding.progressBarSync.visibility = View.GONE
                            if (!sync.error.isNullOrEmpty()) {
                                binding.tvSyncStatus.text = sync.error
                            } else if (lastSync > 0) {
                                val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(lastSync))
                                binding.tvSyncStatus.text = getString(R.string.sync_status_fmt, dateStr)
                            } else {
                                binding.tvSyncStatus.text = getString(R.string.sync_never)
                            }
                        }
                    }
                }

                launch {
                    viewModel.allKnownTracks.collect { tracks ->
                        if (currentMode == LibraryMode.ARTISTS || currentMode == LibraryMode.ALBUMS) {
                            if (tracks.isNotEmpty()) {
                                computeAndDisplayTiles(tracks)
                            }
                        }
                    }
                }

                launch {
                    viewModel.librarySummary.collect { summary ->
                        if (summary?.total == -1) {
                            binding.tvEmptyLibrary.text = getString(R.string.feature_disabled_library)
                            binding.tvEmptyLibrary.visibility = View.VISIBLE
                            binding.progressBarLibrary.visibility = View.GONE
                        } else if (currentMode == LibraryMode.YEARS) {
                            refreshTilesForCurrentMode()
                        }
                    }
                }

                launch {
                    viewModel.yearArtworkMap.collect {
                        if (currentMode == LibraryMode.YEARS) {
                            refreshTilesForCurrentMode()
                        }
                    }
                }

                launch {
                    viewModel.libraryPageFlow.collectLatest { pagingData ->
                        trackAdapter?.submitData(pagingData)
                    }
                }
            }
        }
    }

    private fun showAddToPlaylistDialog(track: TrackRow) {
        val playlists = viewModel.playlists.value
        if (playlists.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("No Playlists")
                .setMessage("Create a playlist first from the Playlists tab.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val names = playlists.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Add to Playlist")
            .setItems(names) { _, which ->
                val targetPlaylist = playlists[which]
                viewModel.addTrackToPlaylist(targetPlaylist.id, track.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshLibrary()
        if (currentMode != LibraryMode.TRACKS) {
            refreshTilesForCurrentMode()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}