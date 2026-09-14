package com.nokia_xd.y2remote.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.nokia_xd.y2remote.R
import com.nokia_xd.y2remote.data.ArtworkCache
import com.nokia_xd.y2remote.databinding.ItemTrackBinding
import com.nokia_xd.y2remote.protocol.TrackRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class TrackAdapter(
    private val artworkCache: ArtworkCache?,
    private val coroutineScope: CoroutineScope,
    private val isPlaylistMode: Boolean = false,
    private val onTrackClick: (TrackRow) -> Unit,
    private val onTrackAction: (TrackRow, TrackAction) -> Unit
) : PagingDataAdapter<TrackRow, TrackAdapter.TrackViewHolder>(DIFF_CALLBACK) {

    enum class TrackAction {
        PLAY_NOW,
        PLAY_NEXT,
        ADD_TO_UP_NEXT,
        ADD_TO_PLAYLIST,
        REMOVE_FROM_PLAYLIST
    }

    var isFastScrolling: Boolean = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val binding = ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TrackViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val item = getItem(position)
        if (item != null) {
            holder.bind(item)
        }
    }

    override fun onViewRecycled(holder: TrackViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelArtworkLoad()
    }

    inner class TrackViewHolder(private val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root) {
        private var currentTrackId: Long? = null
        private var artworkJob: Job? = null

        fun bind(track: TrackRow) {
            val ctx = binding.root.context
            currentTrackId = track.id
            binding.tvTrackTitle.text = track.title.ifEmpty { ctx.getString(R.string.unknown_title) }
            binding.tvTrackSubtitle.text = buildString {
                if (track.artist.isNotEmpty()) append(track.artist)
                if (track.artist.isNotEmpty() && track.album.isNotEmpty()) append(" • ")
                if (track.album.isNotEmpty()) append(track.album)
            }.ifEmpty { ctx.getString(R.string.unknown_artist) }

            binding.tvTrackDuration.text = formatDuration(track.durationMs)
            binding.ivTrackArt.setImageResource(R.drawable.ic_notification)

            binding.root.setOnClickListener {
                onTrackClick(track)
            }

            binding.btnTrackOptions.setOnClickListener { view ->
                val popup = PopupMenu(view.context, view)
                popup.menu.add(0, 1, 0, R.string.track_action_play_next)
                popup.menu.add(0, 2, 1, R.string.track_action_add_to_up_next)
                popup.menu.add(0, 3, 2, R.string.track_action_add_to_playlist)
                if (isPlaylistMode) {
                    popup.menu.add(0, 4, 3, R.string.track_action_remove_from_playlist)
                }
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> onTrackAction(track, TrackAction.PLAY_NEXT)
                        2 -> onTrackAction(track, TrackAction.ADD_TO_UP_NEXT)
                        3 -> onTrackAction(track, TrackAction.ADD_TO_PLAYLIST)
                        4 -> onTrackAction(track, TrackAction.REMOVE_FROM_PLAYLIST)
                    }
                    true
                }
                popup.show()
            }

            loadArtwork(track)
        }

        fun loadArtwork(track: TrackRow) {
            artworkJob?.cancel()
            if (!track.hasArtwork || artworkCache == null || isFastScrolling) {
                return
            }

            val trackId = track.id
            artworkJob = coroutineScope.launch {
                val bitmap = artworkCache.get(trackId)
                if (bitmap != null && currentTrackId == trackId) {
                    withContext(Dispatchers.Main) {
                        binding.ivTrackArt.setImageBitmap(bitmap)
                    }
                }
            }
        }

        fun cancelArtworkLoad() {
            artworkJob?.cancel()
            artworkJob = null
            currentTrackId?.let { artworkCache?.cancel(it) }
        }

        private fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000L
            val min = totalSec / 60L
            val sec = totalSec % 60L
            return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<TrackRow>() {
            override fun areItemsTheSame(oldItem: TrackRow, newItem: TrackRow): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: TrackRow, newItem: TrackRow): Boolean =
                oldItem == newItem
        }
    }
}
