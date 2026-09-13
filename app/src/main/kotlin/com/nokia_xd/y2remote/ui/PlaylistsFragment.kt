package com.nokia_xd.y2remote.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.nokia_xd.y2remote.R
import com.nokia_xd.y2remote.databinding.FragmentPlaylistsBinding
import com.nokia_xd.y2remote.protocol.PlaylistRow
import kotlinx.coroutines.launch

class PlaylistsFragment : Fragment() {

    private var _binding: FragmentPlaylistsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewModel by activityViewModels()

    private var playlistAdapter: PlaylistAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlaylistsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupFab()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            onPlaylistClick = { playlist ->
                PlaylistTracksDialogFragment.newInstance(playlist.id, playlist.name)
                    .show(parentFragmentManager, "playlist_tracks")
            },
            onPlaylistAction = { playlist, action ->
                when (action) {
                    PlaylistAdapter.PlaylistAction.RENAME -> showRenamePlaylistDialog(playlist)
                    PlaylistAdapter.PlaylistAction.DELETE -> showDeletePlaylistDialog(playlist)
                }
            }
        )

        binding.rvPlaylists.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPlaylists.adapter = playlistAdapter
    }

    private fun setupFab() {
        binding.fabAddPlaylist.setOnClickListener {
            showCreatePlaylistDialog()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.playlistsLoading.collect { loading ->
                        val hasItems = (playlistAdapter?.itemCount ?: 0) > 0
                        binding.progressBarPlaylists.visibility = if (loading && !hasItems) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.playlistsTotal.collect { total ->
                        if (total == -1) {
                            playlistAdapter?.submitList(emptyList())
                            binding.rvPlaylists.visibility = View.GONE
                            binding.fabAddPlaylist.visibility = View.GONE
                            binding.tvEmptyPlaylists.text = getString(R.string.feature_disabled_playlists)
                            binding.tvEmptyPlaylists.visibility = View.VISIBLE
                            binding.progressBarPlaylists.visibility = View.GONE
                        } else {
                            binding.rvPlaylists.visibility = View.VISIBLE
                            binding.fabAddPlaylist.visibility = View.VISIBLE
                        }
                    }
                }

                launch {
                    viewModel.playlists.collect { playlists ->
                        if (viewModel.playlistsTotal.value != -1) {
                            playlistAdapter?.submitList(playlists)
                            binding.tvEmptyPlaylists.text = getString(R.string.empty_playlists)
                            binding.tvEmptyPlaylists.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun showCreatePlaylistDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Playlist Name"
            setSingleLine()
        }
        val container = FrameLayout(requireContext()).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(input)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.create_playlist)
            .setView(container)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.createPlaylist(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenamePlaylistDialog(playlist: PlaylistRow) {
        val input = EditText(requireContext()).apply {
            setText(playlist.name)
            setSelection(playlist.name.length)
            setSingleLine()
        }
        val container = FrameLayout(requireContext()).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(input)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rename_playlist)
            .setView(container)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != playlist.name) {
                    viewModel.renamePlaylist(playlist.id, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeletePlaylistDialog(playlist: PlaylistRow) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_playlist)
            .setMessage("Are you sure you want to delete '${playlist.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deletePlaylist(playlist.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadPlaylists()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
