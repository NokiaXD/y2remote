package com.nokia_xd.y2remote.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nokia_xd.y2remote.R
import com.nokia_xd.y2remote.databinding.DialogArtistYearOverviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArtistYearOverviewDialogFragment : DialogFragment() {

    private var _binding: DialogArtistYearOverviewBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewModel by activityViewModels()

    private var tileAdapter: MediaTileAdapter? = null

    companion object {
        fun newInstance(): ArtistYearOverviewDialogFragment = ArtistYearOverviewDialogFragment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_Y2Remote_FullScreenDialog)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogArtistYearOverviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val topInset = maxOf(
                bars.top,
                insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()).top
            )
            v.setPadding(bars.left, topInset, bars.right, 0)
            val basePad = (24 * resources.displayMetrics.density).toInt()
            binding.rvSubTiles.setPadding(
                binding.rvSubTiles.paddingLeft,
                binding.rvSubTiles.paddingTop,
                binding.rvSubTiles.paddingRight,
                maxOf(basePad, bars.bottom)
            )
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.root)

        binding.btnBack.setOnClickListener {
            dismiss()
        }

        val tile = viewModel.selectedTile.value
        if (tile == null) {
            dismiss()
            return
        }

        val isArtist = tile.type == TileType.ARTIST
        binding.tvCategoryType.text = if (isArtist) "Artist" else "Year"
        binding.tvHeaderTitle.text = tile.title
        binding.tvHeaderSubtitle.text = tile.subtitle
        binding.tvSectionLabel.text = if (isArtist) "DISCOGRAPHY / ALBUMS" else "ALBUMS FROM THIS YEAR"

        // Load header artwork
        tile.artworkTrackId?.let { trackId ->
            viewLifecycleOwner.lifecycleScope.launch {
                val bmp = viewModel.artworkCache?.get(trackId)
                if (bmp != null) {
                    withContext(Dispatchers.Main) {
                        binding.ivHeaderArt.setImageBitmap(bmp)
                    }
                }
            }
        }

        // Header Play All
        binding.btnPlayAll.setOnClickListener {
            val trackIds = tile.tracks.map { it.id }
            if (trackIds.isNotEmpty()) {
                viewModel.replaceQueue(trackIds, startIndex = 0, shuffled = false)
            }
        }

        setupSubTilesGrid(tile)
    }

    private fun setupSubTilesGrid(parentTile: MediaTile) {
        val subTiles = mutableListOf<MediaTile>()

        // 1. First Tile: "All Songs"
        val totalSongs = parentTile.tracks.size
        val allSongsTile = MediaTile(
            id = "${parentTile.id}:all_tracks",
            title = "All Songs",
            subtitle = if (totalSongs == 1) "1 song" else "$totalSongs songs",
            artworkTrackId = parentTile.artworkTrackId,
            tracks = parentTile.tracks,
            type = TileType.ALBUM
        )
        subTiles.add(allSongsTile)

        // 2. Album Tiles
        val albumsGrouped = parentTile.tracks
            .filter { it.album.isNotBlank() }
            .groupBy { it.album.trim() }
            .map { (albumName, albumTracks) ->
                val artTrackId = albumTracks.firstOrNull { it.hasArtwork }?.id ?: albumTracks.firstOrNull()?.id
                val songCount = albumTracks.size
                MediaTile(
                    id = "album:$albumName",
                    title = albumName,
                    subtitle = if (songCount == 1) "1 song" else "$songCount songs",
                    artworkTrackId = artTrackId,
                    tracks = albumTracks,
                    type = TileType.ALBUM
                )
            }
            .sortedBy { it.title.lowercase() }

        subTiles.addAll(albumsGrouped)

        // Loose / single tracks if any
        val singles = parentTile.tracks.filter { it.album.isBlank() }
        if (singles.isNotEmpty()) {
            val artTrackId = singles.firstOrNull { it.hasArtwork }?.id ?: singles.firstOrNull()?.id
            val singleCount = singles.size
            subTiles.add(
                MediaTile(
                    id = "${parentTile.id}:singles",
                    title = "Singles / Other",
                    subtitle = if (singleCount == 1) "1 song" else "$singleCount songs",
                    artworkTrackId = artTrackId,
                    tracks = singles,
                    type = TileType.ALBUM
                )
            )
        }

        val albumCount = albumsGrouped.size
        val albumStr = if (albumCount == 1) "1 album" else "$albumCount albums"
        val songStr = if (parentTile.tracks.size == 1) "1 song" else "${parentTile.tracks.size} songs"
        binding.tvHeaderStats.text = "$albumStr • $songStr"

        tileAdapter = MediaTileAdapter(
            artworkCache = viewModel.artworkCache,
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            onTileClick = { clickedSubTile ->
                viewModel.selectTile(clickedSubTile)
                MediaGroupDetailDialogFragment.newInstance().show(childFragmentManager, "media_group_detail")
            }
        )

        binding.rvSubTiles.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvSubTiles.adapter = tileAdapter
        tileAdapter?.submitList(subTiles)

        binding.rvSubTiles.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                tileAdapter?.isFastScrolling = newState == RecyclerView.SCROLL_STATE_SETTLING
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
