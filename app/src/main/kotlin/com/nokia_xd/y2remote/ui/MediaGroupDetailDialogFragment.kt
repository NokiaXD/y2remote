package com.nokia_xd.y2remote.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.nokia_xd.y2remote.R
import com.nokia_xd.y2remote.databinding.DialogMediaGroupDetailBinding
import com.nokia_xd.y2remote.protocol.TrackRow
import kotlinx.coroutines.launch

class MediaGroupDetailDialogFragment : DialogFragment() {

    private var _binding: DialogMediaGroupDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewModel by activityViewModels()

    private var currentTile: MediaTile? = null
    private var displayedTracks: List<TrackRow> = emptyList()
    private var trackAdapter: TrackAdapter? = null

    companion object {
        fun newInstance(): MediaGroupDetailDialogFragment = MediaGroupDetailDialogFragment()
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
        _binding = DialogMediaGroupDetailBinding.inflate(inflater, container, false)
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
            binding.rvGroupTracks.setPadding(
                binding.rvGroupTracks.paddingLeft,
                binding.rvGroupTracks.paddingTop,
                binding.rvGroupTracks.paddingRight,
                maxOf(basePad, bars.bottom)
            )
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.root)

        binding.btnBack.setOnClickListener {
            dismiss()
        }

        val tile = viewModel.selectedTile.value
        currentTile = tile
        if (tile == null) {
            dismiss()
            return
        }

        binding.tvGroupTitle.text = tile.title
        binding.tvGroupSubtitle.text = tile.subtitle
        displayedTracks = tile.tracks

        setupRecyclerView()
        setupSubFilters(tile)
        updateTracks(displayedTracks)
    }

    private fun setupSubFilters(tile: MediaTile) {
        if (tile.type != TileType.ARTIST) {
            binding.scrollSubFilters.visibility = View.GONE
            return
        }

        val singleOrOther = getString(R.string.single_or_other)
        val albums = tile.tracks.map { it.album.ifEmpty { singleOrOther } }.distinct()
        if (albums.size <= 1) {
            binding.scrollSubFilters.visibility = View.GONE
            return
        }

        binding.scrollSubFilters.visibility = View.VISIBLE
        binding.chipGroupSubFilters.removeAllViews()

        // "All Albums" chip
        val allChip = Chip(requireContext()).apply {
            text = getString(R.string.filter_all_albums_fmt, tile.tracks.size)
            isCheckable = true
            isChecked = true
            setOnClickListener {
                displayedTracks = tile.tracks
                updateTracks(displayedTracks)
            }
        }
        binding.chipGroupSubFilters.addView(allChip)

        albums.forEach { albumName ->
            val albumTracks = tile.tracks.filter { (it.album.ifEmpty { singleOrOther }) == albumName }
            val chip = Chip(requireContext()).apply {
                text = getString(R.string.filter_album_count_fmt, albumName, albumTracks.size)
                isCheckable = true
                setOnClickListener {
                    displayedTracks = albumTracks
                    updateTracks(displayedTracks)
                }
            }
            binding.chipGroupSubFilters.addView(chip)
        }
    }

    private fun setupRecyclerView() {
        val adapter = TrackAdapter(
            artworkCache = viewModel.artworkCache,
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            isPlaylistMode = false,
            onTrackClick = { track ->
                val trackIds = displayedTracks.map { it.id }
                val startIndex = trackIds.indexOf(track.id).coerceAtLeast(0)
                viewModel.replaceQueue(trackIds, startIndex = startIndex, shuffled = false)
            },
            onTrackAction = { track, action ->
                when (action) {
                    TrackAdapter.TrackAction.PLAY_NOW -> viewModel.replaceQueue(listOf(track.id))
                    TrackAdapter.TrackAction.PLAY_NEXT -> viewModel.playNext(listOf(track.id))
                    TrackAdapter.TrackAction.ADD_TO_UP_NEXT -> viewModel.addToUpNext(listOf(track.id))
                    TrackAdapter.TrackAction.ADD_TO_PLAYLIST -> showAddToPlaylistDialog(track)
                    TrackAdapter.TrackAction.REMOVE_FROM_PLAYLIST -> Unit
                }
            }
        )
        trackAdapter = adapter
        binding.rvGroupTracks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvGroupTracks.adapter = adapter

        binding.rvGroupTracks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                adapter.isFastScrolling = newState == RecyclerView.SCROLL_STATE_SETTLING
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val first = lm.findFirstVisibleItemPosition()
                    val last = lm.findLastVisibleItemPosition()
                    if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return
                    for (i in first..last) {
                        val holder = recyclerView.findViewHolderForAdapterPosition(i) as? TrackAdapter.TrackViewHolder
                        val item = adapter.peek(i)
                        if (holder != null && item != null) {
                            holder.loadArtwork(item)
                        }
                    }
                }
            }
        })

        binding.btnPlayAll.setOnClickListener {
            val trackIds = displayedTracks.map { it.id }
            if (trackIds.isNotEmpty()) {
                viewModel.replaceQueue(trackIds, startIndex = 0, shuffled = false)
            }
        }
    }

    private fun updateTracks(tracks: List<TrackRow>) {
        binding.tvEmptyTracks.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            trackAdapter?.submitData(androidx.paging.PagingData.from(tracks))
        }
    }

    private fun showAddToPlaylistDialog(track: TrackRow) {
        val playlists = viewModel.playlists.value
        if (playlists.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dialog_no_playlists)
                .setMessage(R.string.dialog_no_playlists_message)
                .setPositiveButton(R.string.common_ok, null)
                .show()
            return
        }

        val names = playlists.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_add_to_playlist)
            .setItems(names) { _, which ->
                val target = playlists[which]
                viewModel.addTrackToPlaylist(target.id, track.id)
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
