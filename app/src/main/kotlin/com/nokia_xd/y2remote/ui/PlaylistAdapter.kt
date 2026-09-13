package com.nokia_xd.y2remote.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nokia_xd.y2remote.R
import com.nokia_xd.y2remote.databinding.ItemPlaylistBinding
import com.nokia_xd.y2remote.protocol.PlaylistRow

class PlaylistAdapter(
    private val onPlaylistClick: (PlaylistRow) -> Unit,
    private val onPlaylistAction: (PlaylistRow, PlaylistAction) -> Unit
) : ListAdapter<PlaylistRow, PlaylistAdapter.PlaylistViewHolder>(DiffCallback) {

    enum class PlaylistAction {
        RENAME,
        DELETE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val binding = ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PlaylistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PlaylistViewHolder(private val binding: ItemPlaylistBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: PlaylistRow) {
            binding.tvPlaylistName.text = playlist.name
            binding.tvPlaylistTrackCount.text = "${playlist.trackCount} ${if (playlist.trackCount == 1) "track" else "tracks"}"

            binding.root.setOnClickListener {
                onPlaylistClick(playlist)
            }

            binding.btnPlaylistOptions.setOnClickListener { view ->
                val popup = PopupMenu(view.context, view)
                popup.menu.add(0, 1, 0, R.string.rename_playlist)
                popup.menu.add(0, 2, 1, R.string.delete_playlist)
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> {
                            onPlaylistAction(playlist, PlaylistAction.RENAME)
                            true
                        }
                        2 -> {
                            onPlaylistAction(playlist, PlaylistAction.DELETE)
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<PlaylistRow>() {
            override fun areItemsTheSame(oldItem: PlaylistRow, newItem: PlaylistRow): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: PlaylistRow, newItem: PlaylistRow): Boolean {
                return oldItem == newItem
            }
        }
    }
}
