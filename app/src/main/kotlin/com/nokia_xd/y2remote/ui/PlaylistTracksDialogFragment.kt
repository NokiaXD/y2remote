package com.nokia_xd.y2remote.ui

import android.app.Dialog
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

    private var trackAdapter: TrackAdapter? = null
    private var playlistId: Long = 0L
    private var playlistName: String = ""

    companion object {
        private const val ARG_PLAYLIST_ID = "arg_playlist_id"
        private const val ARG_PLAYLIST_NAME = "arg_playlist_name"

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
        _binding = DialogPlaylistTracksBinding.inflate(inflater, container, false)
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
            binding.rvPlaylistTracks.setPadding(
                binding.rvPlaylistTracks.paddingLeft,
                binding.rvPlaylistTracks.paddingTop,
                binding.rvPlaylistTracks.paddingRight,
                maxOf(basePad, bars.bottom)
            )
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.root)

        binding.tvPlaylistTitle.text = playlistName
        binding.tvPlaylistSubtitle.text = getString(R.string.playlist_subtitle)

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
                val allIds = trackAdapter?.snapshot()?.items?.map { it.id }.orEmpty()
                val idx = allIds.indexOf(track.id).coerceAtLeast(0)
                if (allIds.isNotEmpty()) {
                    viewModel.replaceQueue(allIds, startIndex = idx, shuffled = false)
                } else {
                    viewModel.replaceQueue(listOf(track.id), startIndex = 0, shuffled = false)
                }
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

        adapter.addLoadStateListener { loadState ->
            val isListEmpty = loadState.refresh is androidx.paging.LoadState.NotLoading && adapter.itemCount == 0
            if (loadState.refresh is androidx.paging.LoadState.Error) {
                binding.tvEmptyTracks.text = getString(R.string.playlist_load_error)
                binding.tvEmptyTracks.visibility = View.VISIBLE
            } else if (isListEmpty) {
                binding.tvEmptyTracks.text = getString(R.string.empty_library)
                binding.tvEmptyTracks.visibility = View.VISIBLE
            } else {
                binding.tvEmptyTracks.visibility = View.GONE
            }
        }

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
                .setTitle(R.string.dialog_no_other_playlists)
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
