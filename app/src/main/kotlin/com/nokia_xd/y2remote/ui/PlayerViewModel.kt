package com.nokia_xd.y2remote.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.nokia_xd.y2remote.bluetooth.BluetoothConnectionManager
import com.nokia_xd.y2remote.data.ArtworkCache
import com.nokia_xd.y2remote.data.LibraryCache
import com.nokia_xd.y2remote.data.RemoteLibraryPagingSource
import com.nokia_xd.y2remote.data.RemotePlaylistTracksPagingSource
import com.nokia_xd.y2remote.protocol.PlaylistRow
import com.nokia_xd.y2remote.protocol.RemoteCommand
import com.nokia_xd.y2remote.protocol.RemoteMessage
import com.nokia_xd.y2remote.protocol.RemoteProtocol
import com.nokia_xd.y2remote.protocol.TrackRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class PlayerViewModel(application: Application) : AndroidViewModel(application), BluetoothConnectionManager.Listener {

    private val _connectionState = MutableStateFlow<BluetoothConnectionManager.ConnectionState>(
        BluetoothConnectionManager.ConnectionState.Disconnected
    )
    val connectionState: StateFlow<BluetoothConnectionManager.ConnectionState> = _connectionState.asStateFlow()

    private val _playerState = MutableStateFlow<RemoteMessage.PlayerState?>(null)
    val playerState: StateFlow<RemoteMessage.PlayerState?> = _playerState.asStateFlow()

    private val _interpolatedPositionMs = MutableStateFlow(0L)
    val interpolatedPositionMs: StateFlow<Long> = _interpolatedPositionMs.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDevice>> = _pairedDevices.asStateFlow()

    private val _artwork = MutableStateFlow<Bitmap?>(null)
    val artwork: StateFlow<Bitmap?> = _artwork.asStateFlow()

    private val _volumePercent = MutableStateFlow(100)
    val volumePercent: StateFlow<Int> = _volumePercent.asStateFlow()

    // --- V2 Library States ---
    private val _libraryScope = MutableStateFlow("all")
    val libraryScope: StateFlow<String> = _libraryScope.asStateFlow()

    private val _librarySort = MutableStateFlow("title")
    val librarySort: StateFlow<String> = _librarySort.asStateFlow()

    private val _libraryQuery = MutableStateFlow("")
    val libraryQuery: StateFlow<String> = _libraryQuery.asStateFlow()

    private val _libraryRefreshTrigger = MutableStateFlow(0L)

    private val _librarySummary = MutableStateFlow<RemoteMessage.LibrarySummary?>(null)
    val librarySummary: StateFlow<RemoteMessage.LibrarySummary?> = _librarySummary.asStateFlow()

    // --- V2 Playlists & Queue States ---
    private val _playlists = MutableStateFlow<List<PlaylistRow>>(emptyList())
    val playlists: StateFlow<List<PlaylistRow>> = _playlists.asStateFlow()

    private val _playlistsTotal = MutableStateFlow(0)
    val playlistsTotal: StateFlow<Int> = _playlistsTotal.asStateFlow()

    private val _playlistsLoading = MutableStateFlow(false)
    val playlistsLoading: StateFlow<Boolean> = _playlistsLoading.asStateFlow()

    private val _queueState = MutableStateFlow<RemoteMessage.QueueState?>(null)
    val queueState: StateFlow<RemoteMessage.QueueState?> = _queueState.asStateFlow()

    private val _queueLoading = MutableStateFlow(false)
    val queueLoading: StateFlow<Boolean> = _queueLoading.asStateFlow()

    private var connectionManager: BluetoothConnectionManager? = null
    val libraryCache = LibraryCache()
    var artworkCache: ArtworkCache? = null
        private set

    private var interpolationJob: Job? = null
    private var volumeJob: Job? = null
    private var lastVolumeSentTime = 0L
    private val VOLUME_THROTTLE_MS = 100L
    private var lastStateReceivedAtUptime = 0L
    private var basePositionMs = 0L

    private data class LibraryFlowParams(
        val scope: String,
        val sort: String,
        val query: String,
        val isConnected: Boolean,
        val trigger: Long
    )

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val libraryPageFlow: Flow<PagingData<TrackRow>> = combine(
        _libraryScope,
        _librarySort,
        _libraryQuery.debounce(250L),
        _connectionState,
        _libraryRefreshTrigger
    ) { scope, sort, query, connState, trigger ->
        val connected = connState is BluetoothConnectionManager.ConnectionState.Connected
        LibraryFlowParams(scope, sort, query, connected, trigger)
    }.distinctUntilChanged()
        .flatMapLatest { params ->
            val mgr = connectionManager
            if (!params.isConnected || mgr == null) {
                flowOf(PagingData.empty())
            } else {
                Pager(
                    config = PagingConfig(pageSize = 50, enablePlaceholders = false),
                    pagingSourceFactory = {
                        RemoteLibraryPagingSource(mgr, libraryCache, params.scope, params.sort, params.query)
                    }
                ).flow
            }
        }.cachedIn(viewModelScope)

    fun bindConnectionManager(manager: BluetoothConnectionManager) {
        connectionManager?.removeListener(this)
        connectionManager = manager
        artworkCache = ArtworkCache(getApplication(), manager)
        manager.addListener(this)
        _connectionState.value = manager.currentState
        if (manager.currentState is BluetoothConnectionManager.ConnectionState.Connected) {
            refreshAllRemoteData()
        }
    }

    fun loadPairedDevices() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val bonded = adapter.bondedDevices?.toList() ?: emptyList()
        _pairedDevices.value = bonded
    }

    fun connectToDevice(device: BluetoothDevice) {
        connectionManager?.connect(device)
    }

    fun disconnect() {
        connectionManager?.disconnect()
    }

    fun sendCommand(command: RemoteCommand) {
        connectionManager?.sendCommand(command)
    }

    fun setVolume(percent: Int, immediate: Boolean = false) {
        _volumePercent.value = percent
        val now = SystemClock.uptimeMillis()
        if (immediate) {
            volumeJob?.cancel()
            volumeJob = null
            lastVolumeSentTime = now
            sendCommand(RemoteCommand.SetVolume(percent))
        } else {
            if (now - lastVolumeSentTime >= VOLUME_THROTTLE_MS) {
                volumeJob?.cancel()
                volumeJob = null
                lastVolumeSentTime = now
                sendCommand(RemoteCommand.SetVolume(percent))
            } else if (volumeJob == null || !volumeJob!!.isActive) {
                volumeJob = viewModelScope.launch {
                    val waitTime = VOLUME_THROTTLE_MS - (SystemClock.uptimeMillis() - lastVolumeSentTime)
                    if (waitTime > 0) {
                        delay(waitTime)
                    }
                    lastVolumeSentTime = SystemClock.uptimeMillis()
                    sendCommand(RemoteCommand.SetVolume(_volumePercent.value))
                }
            }
        }
    }

    fun volumeUp() {
        sendCommand(RemoteCommand.VolumeUp)
    }

    fun volumeDown() {
        sendCommand(RemoteCommand.VolumeDown)
    }

    fun seekTo(positionMs: Long) {
        _interpolatedPositionMs.value = positionMs
        basePositionMs = positionMs
        lastStateReceivedAtUptime = SystemClock.uptimeMillis()
        sendCommand(RemoteCommand.Seek(positionMs))
    }

    // --- Library Filter Controls ---

    fun setLibraryScope(scope: String) {
        _libraryScope.value = scope
    }

    fun setLibrarySort(sort: String) {
        _librarySort.value = sort
    }

    fun setLibraryQuery(query: String) {
        _libraryQuery.value = query
    }

    fun refreshLibrary() {
        _libraryRefreshTrigger.value = SystemClock.uptimeMillis()
        refreshLibrarySummary()
    }

    fun refreshLibrarySummary() {
        viewModelScope.launch {
            val summary = connectionManager?.requestLibrarySummary()
            if (summary != null) {
                _librarySummary.value = summary
            }
        }
    }

    // --- Playlist Controls ---

    fun loadPlaylists() {
        viewModelScope.launch {
            _playlistsLoading.value = true
            val list = connectionManager?.requestPlaylistsList()
            _playlistsLoading.value = false
            if (list != null) {
                _playlistsTotal.value = list.total
                _playlists.value = list.items
            }
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val res = connectionManager?.requestPlaylistsCreate(name)
            if (res?.ok == true) {
                loadPlaylists()
            }
        }
    }

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch {
            val res = connectionManager?.requestPlaylistsRename(id, name)
            if (res?.ok == true) {
                loadPlaylists()
            }
        }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            val res = connectionManager?.requestPlaylistsDelete(id)
            if (res?.ok == true) {
                loadPlaylists()
            }
        }
    }

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            val res = connectionManager?.requestPlaylistsAddTrack(playlistId, trackId)
            if (res?.ok == true) {
                loadPlaylists()
            }
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            val res = connectionManager?.requestPlaylistsRemoveTrack(playlistId, trackId)
            if (res?.ok == true) {
                loadPlaylists()
            }
        }
    }

    fun playlistTracksPagingData(playlistId: Long): Flow<PagingData<TrackRow>> {
        val mgr = connectionManager
        if (mgr == null) return flowOf(PagingData.empty())
        return Pager(
            config = PagingConfig(pageSize = 50, enablePlaceholders = false),
            pagingSourceFactory = {
                RemotePlaylistTracksPagingSource(mgr, libraryCache, playlistId)
            }
        ).flow.cachedIn(viewModelScope)
    }

    // --- Queue Controls ---
    private var queueLoadJob: Job? = null
    private var queueLoadMoreJob: Job? = null
    private val QUEUE_PAGE_SIZE = 20

    fun loadQueue(preserveLoadedWindow: Boolean = true) {
        queueLoadJob?.cancel()
        queueLoadMoreJob?.cancel()
        val currentLoadedCount = _queueState.value?.entries?.size ?: QUEUE_PAGE_SIZE
        val requestLimit = if (preserveLoadedWindow) maxOf(QUEUE_PAGE_SIZE, currentLoadedCount) else QUEUE_PAGE_SIZE
        _queueLoading.value = true
        queueLoadJob = viewModelScope.launch {
            val state = connectionManager?.requestQueueState(offset = 0, limit = requestLimit)
            _queueLoading.value = false
            if (state != null) {
                _queueState.value = state
            }
        }
    }

    fun loadMoreQueue() {
        val current = _queueState.value ?: return
        if (current.entries.size >= current.totalCount) return
        if (queueLoadMoreJob?.isActive == true || queueLoadJob?.isActive == true) return

        queueLoadMoreJob = viewModelScope.launch {
            val nextOffset = current.entries.size
            val state = connectionManager?.requestQueueState(offset = nextOffset, limit = QUEUE_PAGE_SIZE)
            if (state != null) {
                val latest = _queueState.value
                if (latest != null && latest.revision == state.revision) {
                    val combined = (latest.entries + state.entries).distinctBy { it.entryId }
                    _queueState.value = state.copy(
                        entries = combined,
                        totalCount = state.totalCount,
                        offset = 0
                    )
                } else {
                    _queueState.value = state
                }
            }
        }
    }

    fun replaceQueue(trackIds: List<Long>, startIndex: Int = 0, shuffled: Boolean = false) {
        viewModelScope.launch {
            val res = connectionManager?.requestQueueReplace(trackIds, startIndex, shuffled)
            if (res?.ok == true) {
                loadQueue()
            }
        }
    }

    fun playNext(trackIds: List<Long>) {
        viewModelScope.launch {
            val res = connectionManager?.requestQueuePlayNext(trackIds)
            if (res?.ok == true) {
                loadQueue()
            }
        }
    }

    fun addToUpNext(trackIds: List<Long>) {
        viewModelScope.launch {
            val res = connectionManager?.requestQueueAddToUpNext(trackIds)
            if (res?.ok == true) {
                loadQueue()
            }
        }
    }

    fun removeQueueEntry(entryId: Long) {
        viewModelScope.launch {
            val res = connectionManager?.requestQueueRemove(entryId)
            if (res?.ok == true) {
                loadQueue()
            }
        }
    }

    fun moveQueueEntry(entryId: Long, delta: Int) {
        viewModelScope.launch {
            val res = connectionManager?.requestQueueMove(entryId, delta)
            if (res?.ok == true) {
                loadQueue()
            }
        }
    }

    fun promoteQueueEntry(entryId: Long) {
        viewModelScope.launch {
            val res = connectionManager?.requestQueuePromote(entryId)
            if (res?.ok == true) {
                loadQueue()
            }
        }
    }

    fun toggleShuffle() {
        viewModelScope.launch {
            val res = connectionManager?.requestQueueShuffleToggle()
            if (res?.ok == true) {
                loadQueue()
            }
        }
    }

    fun cycleRepeat() {
        viewModelScope.launch {
            val res = connectionManager?.requestQueueRepeatCycle()
            if (res?.ok == true) {
                loadQueue()
            }
        }
    }

    fun clearUpNext() {
        viewModelScope.launch {
            val res = connectionManager?.requestQueueClearUpNext()
            if (res?.ok == true) {
                loadQueue()
            }
        }
    }

    fun clearRemaining() {
        viewModelScope.launch {
            val res = connectionManager?.requestQueueClearRemaining()
            if (res?.ok == true) {
                loadQueue()
            }
        }
    }

    fun clearQueue() {
        viewModelScope.launch {
            val res = connectionManager?.requestQueueClear()
            if (res?.ok == true) {
                loadQueue()
            }
        }
    }

    suspend fun getArtwork(trackId: Long): Bitmap? {
        return artworkCache?.get(trackId)
    }

    private fun refreshAllRemoteData() {
        refreshLibrary()
        loadPlaylists()
        loadQueue()
    }

    override fun onConnectionStateChanged(state: BluetoothConnectionManager.ConnectionState) {
        _connectionState.value = state
        if (state is BluetoothConnectionManager.ConnectionState.Connected) {
            refreshAllRemoteData()
        } else if (state is BluetoothConnectionManager.ConnectionState.Disconnected ||
            state is BluetoothConnectionManager.ConnectionState.Error
        ) {
            stopInterpolation()
            volumeJob?.cancel()
            volumeJob = null
            _playerState.value = null
            _interpolatedPositionMs.value = 0L
            _artwork.value = null
            _playlists.value = emptyList()
            _queueState.value = null
            _librarySummary.value = null
        }
    }

    private var lastObservedTrackTitle: String? = null

    override fun onPlayerStateReceived(state: RemoteMessage.PlayerState) {
        val trackChanged = (lastObservedTrackTitle != state.title)
        lastObservedTrackTitle = state.title
        _playerState.value = state
        if (volumeJob == null && (SystemClock.uptimeMillis() - lastVolumeSentTime >= 600L)) {
            _volumePercent.value = state.volumePercent
        }
        basePositionMs = state.positionMs
        lastStateReceivedAtUptime = SystemClock.uptimeMillis()
        _interpolatedPositionMs.value = state.positionMs

        if (trackChanged || _queueState.value == null) {
            loadQueue()
        }

        if (state.status == RemoteProtocol.STATUS_PLAYING) {
            startInterpolation(state.durationMs)
        } else {
            stopInterpolation()
        }
    }

    override fun onArtworkReceived(bitmap: Bitmap?) {
        _artwork.value = bitmap
    }

    override fun onEventQueueChanged(reason: String, revision: Long) {
        loadQueue()
    }

    override fun onEventLibraryChanged(revision: Long) {
        refreshLibrarySummary()
        loadPlaylists()
    }

    private fun startInterpolation(durationMs: Long) {
        interpolationJob?.cancel()
        interpolationJob = viewModelScope.launch {
            while (isActive) {
                val elapsed = SystemClock.uptimeMillis() - lastStateReceivedAtUptime
                val current = (basePositionMs + elapsed).coerceAtMost(durationMs.coerceAtLeast(0L))
                _interpolatedPositionMs.value = current
                delay(200L)
            }
        }
    }

    private fun stopInterpolation() {
        interpolationJob?.cancel()
        interpolationJob = null
    }

    override fun onCleared() {
        connectionManager?.removeListener(this)
        stopInterpolation()
        super.onCleared()
    }
}

