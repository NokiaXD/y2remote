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
import com.nokia_xd.y2remote.data.LocalLibraryDatabase
import com.nokia_xd.y2remote.data.LocalLibraryPagingSource
import com.nokia_xd.y2remote.data.RemoteLibraryPagingSource
import com.nokia_xd.y2remote.data.RemotePlaylistTracksPagingSource
import com.nokia_xd.y2remote.protocol.PlaylistRow
import com.nokia_xd.y2remote.protocol.RemoteCommand
import com.nokia_xd.y2remote.protocol.RemoteMessage
import com.nokia_xd.y2remote.protocol.RemoteProtocol
import com.nokia_xd.y2remote.protocol.TrackRow
import com.nokia_xd.y2remote.util.SyncNotificationHelper
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext

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
    private val _librarySort = MutableStateFlow("title")
    val librarySort: StateFlow<String> = _librarySort.asStateFlow()

    private val _libraryQuery = MutableStateFlow("")
    val libraryQuery: StateFlow<String> = _libraryQuery.asStateFlow()

    private val _libraryRefreshTrigger = MutableStateFlow(0L)

    private val _librarySummary = MutableStateFlow<RemoteMessage.LibrarySummary?>(null)
    val librarySummary: StateFlow<RemoteMessage.LibrarySummary?> = _librarySummary.asStateFlow()

    private val _selectedTile = MutableStateFlow<MediaTile?>(null)
    val selectedTile: StateFlow<MediaTile?> = _selectedTile.asStateFlow()

    fun selectTile(tile: MediaTile) {
        _selectedTile.value = tile
    }

    val localDatabase = LocalLibraryDatabase(application)
    val libraryCache = LibraryCache()
    val syncNotificationHelper = SyncNotificationHelper(application)

    data class SyncProgress(
        val isSyncing: Boolean = false,
        val current: Int = 0,
        val total: Int = 0,
        val statusText: String = "",
        val error: String? = null
    )

    private val _syncProgress = MutableStateFlow(SyncProgress())
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    private val _lastSyncTimestamp = MutableStateFlow(localDatabase.getLastSyncTimestamp())
    val lastSyncTimestamp: StateFlow<Long> = _lastSyncTimestamp.asStateFlow()

    private val _allKnownTracks = MutableStateFlow<List<TrackRow>>(emptyList())
    val allKnownTracks: StateFlow<List<TrackRow>> = _allKnownTracks.asStateFlow()

    init {
        val initialTracks = localDatabase.getAllTracks()
        _allKnownTracks.value = initialTracks
        libraryCache.putAll(initialTracks)
    }

    private var fullLibraryJob: Job? = null
    private val _isFullLibraryLoading = MutableStateFlow(false)
    val isFullLibraryLoading: StateFlow<Boolean> = _isFullLibraryLoading.asStateFlow()

    fun ensureFullLibraryLoaded(onLoaded: ((List<TrackRow>) -> Unit)? = null) {
        val dbTracks = localDatabase.getAllTracks()
        if (dbTracks.isNotEmpty()) {
            _allKnownTracks.value = dbTracks
            onLoaded?.invoke(dbTracks)
            return
        }

        val cached = libraryCache.getAll()
        if (cached.isNotEmpty()) {
            _allKnownTracks.value = cached
            onLoaded?.invoke(cached)
            return
        }

        if (_connectionState.value is BluetoothConnectionManager.ConnectionState.Connected) {
            syncLibrary()
        } else {
            _allKnownTracks.value = emptyList()
            onLoaded?.invoke(emptyList())
        }
    }

    fun syncLibrary() {
        val mgr = connectionManager
        if (mgr == null || _connectionState.value !is BluetoothConnectionManager.ConnectionState.Connected) {
            _syncProgress.value = SyncProgress(
                isSyncing = false,
                error = "Connect to player to sync"
            )
            return
        }

        if (_syncProgress.value.isSyncing) return

        viewModelScope.launch(Dispatchers.IO) {
            _syncProgress.value = SyncProgress(isSyncing = true, statusText = "Starting synchronization...")
            syncNotificationHelper.showProgress(
                title = "Syncing library",
                message = "Starting synchronization...",
                current = 0,
                max = 0,
                indeterminate = true
            )
            try {
                val allTracks = mutableListOf<TrackRow>()
                var offset = 0
                val limit = 100
                var hasMore = true
                var totalCount = 0

                while (hasMore) {
                    val page = mgr.requestLibraryPage("all", "title", "", offset, limit)
                    if (page == null) {
                        val errMsg = "Error receiving data from player"
                        _syncProgress.value = SyncProgress(
                            isSyncing = false,
                            error = errMsg
                        )
                        syncNotificationHelper.showError(errMsg)
                        return@launch
                    }
                    if (totalCount == 0 && page.total > 0) {
                        totalCount = page.total
                    }
                    allTracks.addAll(page.rows)
                    offset += page.rows.size
                    hasMore = page.hasMore && page.rows.isNotEmpty()

                    val currentTotal = if (totalCount > 0) totalCount else allTracks.size
                    val statusMsg = "Syncing songs (${allTracks.size}/$currentTotal)..."
                    _syncProgress.value = SyncProgress(
                        isSyncing = true,
                        current = allTracks.size,
                        total = currentTotal,
                        statusText = statusMsg
                    )
                    syncNotificationHelper.showProgress(
                        title = "Syncing library",
                        message = statusMsg,
                        current = allTracks.size,
                        max = currentTotal,
                        indeterminate = false
                    )
                }

                syncNotificationHelper.showProgress(
                    title = "Syncing library",
                    message = "Saving songs to local cache...",
                    current = allTracks.size,
                    max = allTracks.size,
                    indeterminate = true
                )

                localDatabase.saveTracks(allTracks, replaceAll = true)
                libraryCache.clear()
                libraryCache.putAll(allTracks)

                // 2. Download missing artwork and replicate across album tracks
                val artCache = artworkCache
                if (artCache != null) {
                    val tracksWithArt = allTracks.filter { it.hasArtwork }
                    val albumGroups = tracksWithArt.filter { it.album.isNotBlank() }
                        .groupBy { it.album.trim().lowercase() }

                    val albumReps = albumGroups.mapValues { (_, tracks) -> tracks.first().id }
                    val singleTrackIds = tracksWithArt.filter { it.album.isBlank() }.map { it.id }

                    val distinctArtIds = (albumReps.values + singleTrackIds).distinct()
                    val toFetch = distinctArtIds.filter { !artCache.hasDiskArtwork(it) }

                    var fetchedCount = 0
                    val totalToFetch = toFetch.size

                    if (totalToFetch > 0) {
                        for (trackId in toFetch) {
                            fetchedCount++
                            val artMsg = "Downloading artwork ($fetchedCount/$totalToFetch)..."
                            _syncProgress.value = SyncProgress(
                                isSyncing = true,
                                current = fetchedCount,
                                total = totalToFetch,
                                statusText = artMsg
                            )
                            syncNotificationHelper.showProgress(
                                title = "Downloading artwork",
                                message = artMsg,
                                current = fetchedCount,
                                max = totalToFetch,
                                indeterminate = false
                            )
                            artCache.downloadAndCache(trackId)
                        }
                    }

                    // For all other tracks in each album, copy the cover on disk so every track and tile has it
                    if (albumGroups.isNotEmpty()) {
                        syncNotificationHelper.showProgress(
                            title = "Downloading artwork",
                            message = "Organizing artwork in cache...",
                            current = totalToFetch,
                            max = if (totalToFetch > 0) totalToFetch else 1,
                            indeterminate = true
                        )
                    }
                    for ((_, tracks) in albumGroups) {
                        val repId = tracks.first().id
                        for (other in tracks.drop(1)) {
                            artCache.copyDiskArtwork(repId, other.id)
                        }
                    }
                }

                val now = System.currentTimeMillis()
                _lastSyncTimestamp.value = now

                withContext(Dispatchers.Main) {
                    _allKnownTracks.value = allTracks
                    _libraryRefreshTrigger.value = SystemClock.uptimeMillis()
                    val count = allTracks.size
                    val songsText = if (count == 1) "1 song" else "$count songs"
                    _syncProgress.value = SyncProgress(
                        isSyncing = false,
                        current = allTracks.size,
                        total = allTracks.size,
                        statusText = "Sync completed ($songsText)"
                    )
                }

                syncNotificationHelper.showCompleted(allTracks.size)

                loadPlaylists()
                refreshLibrarySummary()
            } catch (e: Exception) {
                val errMsg = e.message ?: "Sync error"
                _syncProgress.value = SyncProgress(
                    isSyncing = false,
                    error = errMsg
                )
                syncNotificationHelper.showError(errMsg)
            }
        }
    }

    fun loadTracksForScope(scope: String, onLoaded: (List<TrackRow>) -> Unit) {
        val mgr = connectionManager ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val res = mgr.requestLibraryPage(scope, "title", "", 0, 200)
            val rows = res?.rows.orEmpty()
            libraryCache.putAll(rows)
            withContext(Dispatchers.Main) {
                onLoaded(rows)
            }
        }
    }

    private val _yearArtworkMap = MutableStateFlow<Map<String, Long>>(emptyMap())
    val yearArtworkMap: StateFlow<Map<String, Long>> = _yearArtworkMap.asStateFlow()

    private var yearArtworkJob: Job? = null

    fun loadArtworkForYears(years: List<String>) {
        val mgr = connectionManager ?: return
        if (years.isEmpty()) return
        yearArtworkJob?.cancel()
        yearArtworkJob = viewModelScope.launch(Dispatchers.IO) {
            val current = _yearArtworkMap.value.toMutableMap()
            val pending = years.filterNot { current.containsKey(it) }
            if (pending.isEmpty()) return@launch

            for (yr in pending) {
                if (!isActive) break
                val page = mgr.requestLibraryPage("year:$yr", "title", "", 0, 5)
                val trackWithArt = page?.rows?.firstOrNull { it.hasArtwork } ?: page?.rows?.firstOrNull()
                if (trackWithArt != null) {
                    current[yr] = trackWithArt.id
                    libraryCache.put(trackWithArt)
                    withContext(Dispatchers.Main) {
                        _yearArtworkMap.value = current.toMap()
                    }
                }
            }
        }
    }

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
    var artworkCache: ArtworkCache? = null
        private set

    private var interpolationJob: Job? = null
    private var volumeJob: Job? = null
    private var lastVolumeSentTime = 0L
    private val VOLUME_THROTTLE_MS = 100L
    private var lastStateReceivedAtUptime = 0L
    private var basePositionMs = 0L

    private data class LibraryFlowParams(
        val sort: String,
        val query: String,
        val isConnected: Boolean,
        val trigger: Long
    )

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val libraryPageFlow: Flow<PagingData<TrackRow>> = combine(
        _librarySort,
        _libraryQuery.debounce(250L),
        _libraryRefreshTrigger
    ) { sort, query, trigger ->
        LibraryFlowParams(sort, query, isConnected = true, trigger)
    }.distinctUntilChanged()
        .flatMapLatest { params ->
            Pager(
                config = PagingConfig(pageSize = 50, enablePlaceholders = false),
                pagingSourceFactory = {
                    val count = localDatabase.getTrackCount(params.query)
                    if (count > 0) {
                        LocalLibraryPagingSource(localDatabase, params.sort, params.query)
                    } else {
                        val mgr = connectionManager
                        if (mgr != null && _connectionState.value is BluetoothConnectionManager.ConnectionState.Connected) {
                            RemoteLibraryPagingSource(mgr, libraryCache, "all", params.sort, params.query)
                        } else {
                            LocalLibraryPagingSource(localDatabase, params.sort, params.query)
                        }
                    }
                }
            ).flow
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

    fun setLibrarySort(sort: String) {
        _librarySort.value = sort
        _libraryRefreshTrigger.value = SystemClock.uptimeMillis()
    }

    fun setLibraryQuery(query: String) {
        _libraryQuery.value = query
        _libraryRefreshTrigger.value = SystemClock.uptimeMillis()
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
                val summaryArt = summary.years.mapNotNull { y ->
                    val yrStr = y.year?.toString() ?: return@mapNotNull null
                    val artId = y.artworkTrackId?.takeIf { it > 0L } ?: return@mapNotNull null
                    yrStr to artId
                }.toMap()
                if (summaryArt.isNotEmpty()) {
                    _yearArtworkMap.value = _yearArtworkMap.value + summaryArt
                }
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
            config = PagingConfig(pageSize = 100, enablePlaceholders = false),
            pagingSourceFactory = {
                RemotePlaylistTracksPagingSource(mgr, localDatabase, playlistId)
            }
        ).flow.cachedIn(viewModelScope)
    }

    // --- Queue Controls ---
    private var queueLoadJob: Job? = null
    private var queueLoadMoreJob: Job? = null
    private val QUEUE_PAGE_SIZE = 100

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

