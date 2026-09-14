package com.nokia_xd.y2remote.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.nokia_xd.y2remote.R
import com.nokia_xd.y2remote.data.ArtworkCache
import com.nokia_xd.y2remote.databinding.ItemQueueEntryBinding
import com.nokia_xd.y2remote.protocol.QueueEntryRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.nokia_xd.y2remote.protocol.TrackRow

data class QueueItemUi(
    val entry: QueueEntryRow,
    val isCurrent: Boolean
)

class QueueAdapter(
    private val artworkCache: ArtworkCache?,
    private val coroutineScope: CoroutineScope,
    private val trackLookup: ((Long) -> TrackRow?)? = null,
    private val onPromoteClick: (QueueEntryRow) -> Unit,
    private val onRemoveClick: (QueueEntryRow) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private val items = mutableListOf<QueueItemUi>()

    fun setQueueItems(newItems: List<QueueItemUi>) {
        if (items == newItems) return

        val oldSize = items.size
        val isAppend = newItems.size > oldSize &&
            oldSize > 0 &&
            items.indices.all { items[it].entry.entryId == newItems[it].entry.entryId }

        if (isAppend) {
            val added = newItems.subList(oldSize, newItems.size)
            items.addAll(added)
            notifyItemRangeInserted(oldSize, added.size)
            return
        }

        val sameOrderAndIds = items.size == newItems.size &&
            items.indices.all { items[it].entry.entryId == newItems[it].entry.entryId }

        items.clear()
        items.addAll(newItems)

        if (sameOrderAndIds) {
            notifyItemRangeChanged(0, items.size)
        } else {
            notifyDataSetChanged()
        }
    }

    fun moveItem(fromPos: Int, toPos: Int) {
        if (fromPos in items.indices && toPos in items.indices) {
            val item = items.removeAt(fromPos)
            items.add(toPos, item)
            notifyItemMoved(fromPos, toPos)
        }
    }

    fun getLocalItem(position: Int): QueueItemUi? = items.getOrNull(position)

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val binding = ItemQueueEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return QueueViewHolder(binding)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val item = items.getOrNull(position) ?: return
        holder.bind(item, position)
    }

    override fun onViewRecycled(holder: QueueViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelArtworkLoad()
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class QueueViewHolder(private val binding: ItemQueueEntryBinding) : RecyclerView.ViewHolder(binding.root) {
        private var currentTrackId: Long? = null
        private var artworkJob: Job? = null

        fun bind(item: QueueItemUi, position: Int) {
            val entry = item.entry
            val trackId = entry.trackId
            val track = entry.track ?: trackLookup?.invoke(trackId)
            currentTrackId = trackId

            val isCurrent = item.isCurrent

            if (isCurrent) {
                binding.ivPlayingIndicator.visibility = View.VISIBLE
                binding.tvQueueIndex.visibility = View.GONE
                binding.tvTrackTitle.setTextColor(ContextCompat.getColor(binding.root.context, R.color.accent))
                binding.btnPromote.visibility = View.GONE
                binding.ivDragHandle.visibility = View.INVISIBLE
                binding.ivDragHandle.setOnTouchListener(null)
            } else {
                binding.ivPlayingIndicator.visibility = View.GONE
                binding.tvQueueIndex.visibility = View.VISIBLE
                binding.tvQueueIndex.text = "${position + 1}"
                binding.tvTrackTitle.setTextColor(ContextCompat.getColor(binding.root.context, R.color.text_primary))
                binding.btnPromote.visibility = View.VISIBLE
                binding.ivDragHandle.visibility = View.VISIBLE
                binding.ivDragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        onStartDrag(this)
                    }
                    false
                }
            }

            binding.tvTrackTitle.text = track?.title?.ifEmpty { "Track #$trackId" } ?: "Track #$trackId"
            binding.tvTrackSubtitle.text = buildString {
                if (track != null) {
                    if (track.artist.isNotEmpty()) append(track.artist)
                    if (track.artist.isNotEmpty() && track.album.isNotEmpty()) append(" • ")
                    if (track.album.isNotEmpty()) append(track.album)
                }
            }.ifEmpty { binding.root.context.getString(R.string.unknown_artist) }

            if (entry.origin == "up_next" || entry.origin == "user") {
                binding.tvOriginBadge.visibility = View.VISIBLE
                binding.tvOriginBadge.text = binding.root.context.getString(R.string.queue_up_next)
            } else {
                binding.tvOriginBadge.visibility = View.GONE
            }

            binding.btnPromote.setOnClickListener {
                onPromoteClick(entry)
            }

            binding.btnRemove.setOnClickListener {
                onRemoveClick(entry)
            }

            binding.ivTrackArt.setImageResource(R.drawable.ic_notification)
            loadArtwork(trackId, track?.hasArtwork ?: true)
        }

        private fun loadArtwork(trackId: Long, hasArtwork: Boolean) {
            artworkJob?.cancel()
            if (!hasArtwork || artworkCache == null) return

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
    }
}
