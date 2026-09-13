package com.nokia_xd.y2remote.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.paging.LoadState
import com.nokia_xd.y2remote.R
import com.nokia_xd.y2remote.databinding.FragmentLibraryBinding
import com.nokia_xd.y2remote.protocol.TrackRow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewModel by activityViewModels()

    private var trackAdapter: TrackAdapter? = null
    private var scopeAdapter: ArrayAdapter<String>? = null
    private var sortAdapter: ArrayAdapter<String>? = null
    private val scopeKeys = mutableListOf<String>()

    private val sortOptions = listOf(
        "title" to "Title",
        "artist" to "Artist",
        "album" to "Album",
        "year" to "Year",
        "added" to "Date Added",
        "recent" to "Recently Modified"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSpinners()
        setupSearch()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        val adapter = TrackAdapter(
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
        trackAdapter = adapter
        binding.rvLibraryTracks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLibraryTracks.adapter = adapter

        // BT Hygiene: Pause artwork loading while scrolling fast (fling)
        binding.rvLibraryTracks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                adapter.isFastScrolling = newState == RecyclerView.SCROLL_STATE_SETTLING
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val first = layoutManager.findFirstVisibleItemPosition()
                    val last = layoutManager.findLastVisibleItemPosition()
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
    }

    private fun setupSpinners() {
        scopeAdapter = ArrayAdapter(requireContext(), R.layout.item_device_spinner, mutableListOf("All Music"))
        scopeAdapter?.setDropDownViewResource(R.layout.item_device_dropdown)
        binding.spinnerScope.adapter = scopeAdapter
        scopeKeys.clear()
        scopeKeys.add("all")

        binding.spinnerScope.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in scopeKeys.indices) {
                    viewModel.setLibraryScope(scopeKeys[position])
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        sortAdapter = ArrayAdapter(requireContext(), R.layout.item_device_spinner, sortOptions.map { it.second })
        sortAdapter?.setDropDownViewResource(R.layout.item_device_dropdown)
        binding.spinnerSort.adapter = sortAdapter

        binding.spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in sortOptions.indices) {
                    viewModel.setLibrarySort(sortOptions[position].first)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.setLibraryQuery(query.orEmpty())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setLibraryQuery(newText.orEmpty())
                return true
            }
        })
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    trackAdapter?.loadStateFlow?.collectLatest { loadStates ->
                        val isLoading = loadStates.refresh is LoadState.Loading
                        val hasItems = (trackAdapter?.itemCount ?: 0) > 0
                        binding.progressBarLibrary.visibility = if (isLoading && !hasItems) View.VISIBLE else View.GONE
                        val isListEmpty = loadStates.refresh is LoadState.NotLoading && !hasItems
                        val summary = viewModel.librarySummary.value
                        val isFeatureDisabled = summary != null && summary.total == -1
                        if (isFeatureDisabled) {
                            binding.tvEmptyLibrary.text = getString(R.string.feature_disabled_library)
                            binding.tvEmptyLibrary.visibility = View.VISIBLE
                        } else if (isListEmpty) {
                            binding.tvEmptyLibrary.text = getString(R.string.empty_library)
                            binding.tvEmptyLibrary.visibility = View.VISIBLE
                        } else {
                            binding.tvEmptyLibrary.visibility = View.GONE
                        }
                    }
                }

                launch {
                    viewModel.librarySummary.collect { summary ->
                        if (summary != null) {
                            if (summary.total == -1) {
                                binding.tvEmptyLibrary.text = getString(R.string.feature_disabled_library)
                                binding.tvEmptyLibrary.visibility = View.VISIBLE
                                binding.progressBarLibrary.visibility = View.GONE
                                scopeAdapter?.clear()
                                return@collect
                            }

                            scopeKeys.clear()
                            val labels = mutableListOf<String>()

                            scopeKeys.add("all")
                            labels.add("All Music (${summary.total})")

                            summary.genres.forEach { g ->
                                scopeKeys.add("genre:${g.key}")
                                labels.add("Genre: ${g.label} (${g.count})")
                            }

                            summary.years.forEach { y ->
                                val yrStr = y.year?.toString() ?: "Unknown"
                                scopeKeys.add("year:$yrStr")
                                labels.add("Year: $yrStr (${y.count})")
                            }

                            scopeAdapter?.clear()
                            scopeAdapter?.addAll(labels)
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
