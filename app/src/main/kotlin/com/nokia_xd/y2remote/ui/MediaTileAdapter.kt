package com.nokia_xd.y2remote.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nokia_xd.y2remote.R
import com.nokia_xd.y2remote.data.ArtworkCache
import com.nokia_xd.y2remote.databinding.ItemMediaTileBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaTileAdapter(
    private val artworkCache: ArtworkCache?,
    private val coroutineScope: CoroutineScope,
    private val onTileClick: (MediaTile) -> Unit
) : ListAdapter<MediaTile, MediaTileAdapter.TileViewHolder>(DIFF_CALLBACK) {

    var isFastScrolling: Boolean = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileViewHolder {
        val binding = ItemMediaTileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: TileViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelArtworkLoad()
    }

    inner class TileViewHolder(private val binding: ItemMediaTileBinding) : RecyclerView.ViewHolder(binding.root) {
        private var currentTrackId: Long? = null
        private var artworkJob: Job? = null

        fun bind(tile: MediaTile) {
            binding.tvTileTitle.text = tile.title.ifEmpty { binding.root.context.getString(R.string.unknown_label) }
            binding.tvTileSubtitle.text = tile.subtitle
            binding.ivTileArt.setImageResource(R.drawable.ic_music_library)

            binding.root.setOnClickListener {
                onTileClick(tile)
            }

            loadArtwork(tile)
        }

        fun loadArtwork(tile: MediaTile) {
            artworkJob?.cancel()
            val trackId = tile.artworkTrackId
            if (trackId == null || artworkCache == null) {
                return
            }

            if (isFastScrolling && !artworkCache.hasDiskArtwork(trackId)) {
                return
            }

            currentTrackId = trackId
            artworkJob = coroutineScope.launch {
                val bitmap = artworkCache.get(trackId)
                if (bitmap != null && currentTrackId == trackId) {
                    withContext(Dispatchers.Main) {
                        binding.ivTileArt.setImageBitmap(bitmap)
                    }
                }
            }
        }

        fun cancelArtworkLoad() {
            artworkJob?.cancel()
            artworkJob = null
            currentTrackId?.let { artworkCache?.cancel(it) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<MediaTile>() {
            override fun areItemsTheSame(oldItem: MediaTile, newItem: MediaTile): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: MediaTile, newItem: MediaTile): Boolean =
                oldItem == newItem
        }
    }
}
