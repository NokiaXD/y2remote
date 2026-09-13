package com.nokia_xd.y2remote.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nokia_xd.y2remote.R
import com.nokia_xd.y2remote.databinding.DialogPlaylistTracksBinding
import com.nokia_xd.y2remote.protocol.TrackRow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlaylistTracksDialogFragment : DialogFragment() {

    private var _binding: DialogPlaylistTracksBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewModel by activityViewModels()

    private var playlistId: Long = 0L
    private var playlistName: String = ""
    private var trackAdapter: TrackAdapter? = null

    companion object {
        private const val ARG_PLAYLIST_ID = "playlist_id"
        private const val ARG_PLAYLIST_NAME = "playlist_name"

        fun newInstance(playlistId: Long, playlistName: String): PlaylistTracksDialogFragment {
            return PlaylistTracksDialogFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_PLAYLIST_ID, playlistId)
                    putString(ARG_PLAYLIST_NAME, playlistName)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlistId = arguments?.getLong(ARG_PLAYLIST_ID) ?: 0L
        playlistName = arguments?.getString(ARG_PLAYLIST_NAME).orEmpty()
        setStyle(STYLE_NORMAL, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogPlaylistTracksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvPlaylistTitle.text = playlistName
        binding.tvPlaylistSubtitle.text = "Playlist"

        binding.btnBack.setOnClickListener {
            dismiss()
        }

        setupRecyclerView()
        observeTracks()
    }

    private fun setupRecyclerView() {
        val adapter = TrackAdapter(
            artworkCache = viewModel.artworkCache,
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            isPlaylistMode = true,
            onTrackClick = { track ->
                viewModel.replaceQueue(listOf(track.id), startIndex = 0, shuffled = false)
            },
            onTrackAction = { track, action ->
                when (action) {
                    TrackAdapter.TrackAction.PLAY_NOW -> viewModel.replaceQueue(listOf(track.id))
                    TrackAdapter.TrackAction.PLAY_NEXT -> viewModel.playNext(listOf(track.id))
                    TrackAdapter.TrackAction.ADD_TO_UP_NEXT -> viewModel.addToUpNext(listOf(track.id))
                    TrackAdapter.TrackAction.ADD_TO_PLAYLIST -> showAddToPlaylistDialog(track)
                    TrackAdapter.TrackAction.REMOVE_FROM_PLAYLIST -> {
                        viewModel.removeTrackFromPlaylist(playlistId, track.id)
                        trackAdapter?.refresh()
                    }
                }
            }
        )
        trackAdapter = adapter
        binding.rvPlaylistTracks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPlaylistTracks.adapter = adapter

        binding.rvPlaylistTracks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                adapter.isFastScrolling = newState == RecyclerView.SCROLL_STATE_SETTLING
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val first = layoutManager.findFirstVisibleItemPosition()
                    val last = layoutManager.findLastVisibleItemPosition()
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
            // If items exist in adapter snapshot, play them
            val trackIds = trackAdapter?.snapshot()?.items?.map { it.id }.orEmpty()
            if (trackIds.isNotEmpty()) {
                viewModel.replaceQueue(trackIds, startIndex = 0, shuffled = false)
            }
        }
    }

    private fun observeTracks() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.playlistTracksPagingData(playlistId).collectLatest { pagingData ->
                    trackAdapter?.submitData(pagingData)
                }
            }
        }
    }

    private fun showAddToPlaylistDialog(track: TrackRow) {
        val playlists = viewModel.playlists.value.filter { it.id != playlistId }
        if (playlists.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("No Other Playlists")
                .setMessage("No other playlists available to add this track.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val names = playlists.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Add to Playlist")
            .setItems(names) { _, which ->
                val target = playlists[which]
                viewModel.addTrackToPlaylist(target.id, track.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
