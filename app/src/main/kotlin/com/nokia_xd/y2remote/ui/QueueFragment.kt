package com.nokia_xd.y2remote.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nokia_xd.y2remote.R
import com.nokia_xd.y2remote.databinding.FragmentQueueBinding
import kotlinx.coroutines.launch

class QueueFragment : Fragment() {

    private var _binding: FragmentQueueBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewModel by activityViewModels()

    private var queueAdapter: QueueAdapter? = null
    private var itemTouchHelper: ItemTouchHelper? = null
    private var isDragging = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupControls()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        val adapter = QueueAdapter(
            artworkCache = viewModel.artworkCache,
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            onPromoteClick = { entry ->
                viewModel.promoteQueueEntry(entry.entryId)
            },
            onRemoveClick = { entry ->
                viewModel.removeQueueEntry(entry.entryId)
            },
            onStartDrag = { viewHolder ->
                itemTouchHelper?.startDrag(viewHolder)
            }
        )
        queueAdapter = adapter

        binding.rvQueueEntries.layoutManager = LinearLayoutManager(requireContext())
        binding.rvQueueEntries.adapter = adapter

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            private var dragFrom = -1
            private var dragTo = -1

            override fun isLongPressDragEnabled(): Boolean = false

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val pos = viewHolder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return 0
                val item = adapter.getLocalItem(pos)
                if (item == null || item.isCurrent) {
                    return makeMovementFlags(0, 0)
                }
                return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    isDragging = true
                    dragFrom = viewHolder?.bindingAdapterPosition ?: -1
                    dragTo = dragFrom
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false

                val fromItem = adapter.getLocalItem(fromPos)
                val toItem = adapter.getLocalItem(toPos)
                if (fromItem == null || fromItem.isCurrent) return false
                if (toItem == null || toItem.isCurrent) return false
                if (fromItem.entry.origin != toItem.entry.origin) return false

                dragTo = toPos
                adapter.moveItem(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                val from = dragFrom
                val to = dragTo
                isDragging = false
                dragFrom = -1
                dragTo = -1
                if (from != -1 && to != -1 && from != to) {
                    val item = adapter.getLocalItem(to)
                    if (item != null) {
                        val delta = to - from
                        viewModel.moveQueueEntry(item.entry.entryId, delta)
                    }
                } else {
                    val current = viewModel.queueState.value
                    if (current != null) {
                        val uiItems = current.entries.map { entry ->
                            QueueItemUi(entry, entry.entryId == current.currentEntryId)
                        }
                        adapter.setQueueItems(uiItems)
                    }
                }
            }
        }

        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper?.attachToRecyclerView(binding.rvQueueEntries)

        binding.rvQueueEntries.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy <= 0) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val visibleItemCount = layoutManager.childCount
                val totalItemCount = layoutManager.itemCount
                val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount - 4 && totalItemCount > 0) {
                    viewModel.loadMoreQueue()
                }
            }
        })
    }

    private fun setupControls() {
        binding.btnToggleShuffle.setOnClickListener {
            viewModel.toggleShuffle()
        }

        binding.btnCycleRepeat.setOnClickListener {
            viewModel.cycleRepeat()
        }

        binding.btnQueueMenu.setOnClickListener { view ->
            val popup = PopupMenu(view.context, view)
            popup.menu.add(0, 1, 0, R.string.queue_clear_up_next)
            popup.menu.add(0, 2, 1, R.string.queue_clear_remaining)
            popup.menu.add(0, 3, 2, R.string.queue_clear_all)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        viewModel.clearUpNext()
                        true
                    }
                    2 -> {
                        viewModel.clearRemaining()
                        true
                    }
                    3 -> {
                        viewModel.clearQueue()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.queueLoading.collect { loading ->
                        val hasItems = (queueAdapter?.itemCount ?: 0) > 0
                        binding.progressBarQueue.visibility = if (loading && !hasItems) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.queueState.collect { queueState ->
                        if (isDragging) return@collect

                        if (queueState != null) {
                            val isFeatureDisabled = queueState.totalCount == -1
                            if (isFeatureDisabled) {
                                queueAdapter?.setQueueItems(emptyList())
                                binding.rvQueueEntries.visibility = View.GONE
                                binding.tvEmptyQueue.text = getString(R.string.feature_disabled_queue)
                                binding.tvEmptyQueue.visibility = View.VISIBLE
                                binding.progressBarQueue.visibility = View.GONE
                                binding.tvQueueTitle.text = getString(R.string.nav_queue)
                                return@collect
                            }

                            binding.rvQueueEntries.visibility = View.VISIBLE
                            val uiItems = queueState.entries.map { entry ->
                                QueueItemUi(entry, entry.entryId == queueState.currentEntryId)
                            }
                            queueAdapter?.setQueueItems(uiItems)
                            binding.tvEmptyQueue.text = getString(R.string.empty_queue)
                            binding.tvEmptyQueue.visibility = if (queueState.entries.isEmpty()) View.VISIBLE else View.GONE
                            binding.progressBarQueue.visibility = View.GONE
                            binding.tvQueueTitle.text = if (queueState.totalCount > queueState.entries.size) {
                                "${getString(R.string.nav_queue)} (${queueState.totalCount})"
                            } else {
                                getString(R.string.nav_queue)
                            }

                            // Shuffle state styling
                            val accentColor = ContextCompat.getColor(requireContext(), R.color.accent)
                            val defaultColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)
                            binding.btnToggleShuffle.imageTintList = ColorStateList.valueOf(
                                if (queueState.shuffleEnabled) accentColor else defaultColor
                            )

                            // Repeat state styling
                            val isRepeatActive = queueState.repeatMode.lowercase() != "off"
                            binding.btnCycleRepeat.imageTintList = ColorStateList.valueOf(
                                if (isRepeatActive) accentColor else defaultColor
                            )
                        } else {
                            queueAdapter?.setQueueItems(emptyList())
                            binding.tvEmptyQueue.text = getString(R.string.empty_queue)
                            binding.tvEmptyQueue.visibility = View.VISIBLE
                            binding.tvQueueTitle.text = getString(R.string.nav_queue)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadQueue()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
