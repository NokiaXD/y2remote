# Plan: Extend Y2 Remote Control (Library, Playlists, Queue)

## Context

- **y2player** is the dedicated music device. Audio output goes via Bluetooth (A2DP/HFP). Remote control enters through a separate RFCOMM socket (`Y2RemoteServer`, UUID `a9c336b4-2dfb-4f9e-a89e-21ef1c60f4e1`).
- **y2remote** is the phone app. Currently it only exposes play/pause/skip/volume/seek. It does not receive the queue or library.

Both projects are located at `D:\y2player` and `D:\y2remote`. The state we need to expose already exists in y2player:
- `LibraryState`/`LibraryIndex` loads the entire library into memory upon scanning.
- `LibraryOrganization.sortTracks` and `tracks(LibraryScope)` already implement the 6 `TrackSortOrder` and All/Genre/Year filters.
- `QueueController` already provides all operations (`replace`, `playNext`, `addToUpNext`, `removeEntry`, `moveEntry`, `promoteToPlayNext`, `clearUpNext`, `clearRemaining`, `clear`, `toggleShuffle`, `cycleRepeat`, `snapshot`).
- `PlaybackService.LocalBinder` wraps each of those calls.
- `LibraryRepository` already provides `createPlaylist`, `deletePlaylist`, `addTrackToPlaylist`, `removeTrackFromPlaylist`, `loadPlaylists`, `loadAllPlaylistTrackIds`, `findTrack`, `toggleFavorite`. Missing: `renamePlaylist`.
- `Y2RemoteServer` currently only exposes `hello`, `state` (current track snapshot), and `artwork` (base64). The `command -> handleRemoteCommand` channel only covers play/pause/skip/volume/seek.

All that is needed is exposing this functionality over the RFCOMM socket.

## Decisions (Agreed with User)

- **Filters**: Complete parity with y2player (6 sort orders + 3 scopes + full-text search).
- **Playlists**: Create, **rename**, delete, add/remove track.
- **Queue**: Complete parity (move, remove, promote, clear_*, shuffle, repeat, play_next batch, add_to_up_next).
- **Paging**: `androidx.paging3` (accepts 2-3 new dependencies).
- **Protocol Version**: Bump to **v2**, without fallback (both projects started at v0).

## Critical Constraint: Bluetooth Bandwidth

Currently, there is a single `BufferedWriter` per socket. Each `flush()` blocks until draining the kernel buffer. Audio exits over A2DP/HFP (a different BT channel), but shares the same physical radio: large bursts compete with audio and cause playback stutter. Rules:

1. **Never send the full library in a single payload**. Max message size ≈ 14 KB (margin well under `MAX_LINE_LENGTH=131072`).
2. **Request/response operations** from phone -> player. Exception: small event pushes (`queue.changed`, `library.changed`) when state changes.
3. **Artwork on demand**, never pre-loaded. Visible rows only, two-level caching on phone (memory + disk).

---

## Phase 1 — Protocol v2 (Shared)

### y2player/app/src/main/kotlin/com/schulzcode/y2player/remote/RemoteProtocol.kt

**Changes**:
- `const val PROTOCOL_VERSION = 2` (was 1).
- New `RemoteMessage` types:
  ```kotlin
  data class LibraryPage(val scope: String, val sort: String, val query: String,
                         val offset: Int, val limit: Int, val requestId: Long) : RemoteMessage()
  data class LibraryPageResult(val requestId: Long, val total: Int, val hasMore: Boolean,
                               val rows: List<TrackRow>) : RemoteMessage()
  data class TrackRow(val id: Long, val title: String, val artist: String,
                      val album: String, val durationMs: Long,
                      val favorite: Boolean, val hasArtwork: Boolean)
  data class LibrarySummary(val genres: List<GenreCount>, val years: List<YearCount>,
                            val total: Int) : RemoteMessage()
  data class GenreCount(val key: String, val label: String, val count: Int)
  data class YearCount(val year: Int?, val count: Int)
  data class LibraryArtwork(val trackId: Long, val base64: String,
                            val width: Int, val height: Int) : RemoteMessage()
  data class PlaylistsList(val items: List<PlaylistRow>) : RemoteMessage()
  data class PlaylistRow(val id: Long, val name: String, val trackCount: Int)
  data class PlaylistsTracks(val playlistId: Long, val rows: List<TrackRow>,
                             val total: Int, val hasMore: Boolean) : RemoteMessage()
  data class PlaylistsMutate(val ok: Boolean, val playlist: PlaylistRow?) : RemoteMessage()
  data class QueueState(val requestId: Long, val entries: List<QueueEntryRow>,
                        val currentEntryId: Long?, val repeatMode: String,
                        val shuffleEnabled: Boolean) : RemoteMessage()
  data class QueueEntryRow(val entryId: Long, val trackId: Long, val origin: String,
                           val track: TrackRow?)
  data class QueueMutate(val ok: Boolean, val requestId: Long) : RemoteMessage()
  data class EventQueueChanged(val reason: String) : RemoteMessage()
  data class EventLibraryChanged(val revision: Long) : RemoteMessage()
  ```
- `TYPE_*` constants for each message.
- `encodeLibraryPage(...)`, `parseLibraryPage(...)`, etc. — one pair per message.
- `parseMessage` filters out any v2 message if the previous handshake was not >= 2.

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/protocol/RemoteProtocol.kt

Exact mirror of the messages above in `com.nokia_xd.y2remote.protocol`.

### Tests

- `y2player/src/test/kotlin/com/schulzcode/y2player/remote/RemoteProtocolTest.kt`: Add `libraryPageRoundTrip`, `libraryArtworkRoundTrip`, `queueStateRoundTrip`, `eventLibraryChangedRoundTrip`.
- `y2remote/src/test/kotlin/com/nokia_xd/y2remote/protocol/RemoteProtocolTest.kt` (create): Round-trip test for every new message.

---

## Phase 2 — y2player Library Handlers

### y2player/app/src/main/kotlin/com/schulzcode/y2player/remote/RemoteRequestHandlers.kt (New)

```kotlin
class RemoteRequestHandlers(
    private val libraryRepository: LibraryRepository,
    private val artworkLoader: AlbumArtworkLoader,
    private val scope: CoroutineScope,
) {
    suspend fun handleLibraryPage(req: LibraryPage): LibraryPageResult
    suspend fun handleLibrarySearch(req: LibraryPage): LibraryPageResult  // same helper
    suspend fun handleLibrarySummary(req: Unit): LibrarySummary
    suspend fun handleLibraryArtwork(req: ArtworkRequest): LibraryArtwork
}
```

- `handleLibraryPage`: Snapshot of `libraryRepository.snapshot()` -> `LibraryOrganization.sortTracks(scope, sort)` (scope parsed to `LibraryScope.All/Genre/Year`) -> `query` filter (case-insensitive on title/artist/album) -> slice `offset..offset+limit`.
- `handleLibrarySummary`: Uses previously computed `LibraryOrganization`.
- `handleLibraryArtwork`: Lookup `findTrack(trackId)`, `artworkLoader.load(path, 128, callback)` with `runBlocking` + `withTimeoutOrNull(1500)`, encode JPEG base64. If timeout/miss -> empty base64.
- Each handler validates `result.toString().length <= 14_000` before returning; if exceeded, reduces `limit` and retries once.

### y2player/app/src/main/kotlin/com/schulzcode/y2player/remote/Y2RemoteServer.kt

**Changes**:
- Add `private val requestHandlers: RemoteRequestHandlers?` (injected).
- In `ConnectedWorker.run()`, identify messages with `type` in `requestTypes: Set<String>` (listed below). If request:
  1. Parse.
  2. `scope.launch { val resp = handlers.handle*(req); send(RemoteProtocol.encode(resp)) }`.
  3. `send` executes under `writeLock` (already present in `send()`).
- `update(snapshot, track, volume)` remains; add `pushEventQueueChanged(reason)` and `pushEventLibraryChanged(rev)`.
- `requestTypes = setOf("library.page", "library.search", "library.summary", "library.artwork", "playlists.list", "playlists.tracks", "playlists.create", "playlists.rename", "playlists.delete", "playlists.add_track", "playlists.remove_track", "queue.state", "queue.replace", "queue.play_next", "queue.add_to_up_next", "queue.remove", "queue.move", "queue.promote", "queue.shuffle.toggle", "queue.repeat.cycle", "queue.clear_up_next", "queue.clear_remaining", "queue.clear")`.

### y2player/app/src/main/kotlin/com/schulzcode/y2player/playback/PlaybackService.kt

- Instantiate `requestHandlers = RemoteRequestHandlers(libraryRepository, container.artworkLoader, CoroutineScope(SupervisorJob() + playbackHandler.looper))` in the initialization `post { ... }` (right after initializing `libraryRepository`).
- Pass `requestHandlers` to `Y2RemoteServer` constructor.
- Push `library.changed` from `libraryRepository.addListener { listener -> ... }` -> when `revision` changes, push event.
- Push `queue.changed` from a new `private fun afterQueueMutationRemote()` called after each successful binder mutation (see existing `afterQueueMutation()` around line ~140) -> push event.

### Tests

- `y2player/src/test/kotlin/com/schulzcode/y2player/remote/RemoteRequestHandlersTest.kt` (new, JVM, no Android dependencies):
  - `libraryPageReturnsFirstWindow`: 200 tracks, scope=All, sort=Title, query="", offset=0, limit=50 -> 50 rows in alphabetical order, `total=200`, `hasMore=true`.
  - `libraryPageAppliesScope`: scope=Year(2020), only tracks from that year.
  - `libraryPageAppliesQuery`: query="the" -> case-insensitive match against title/artist/album.
  - `librarySummaryGroupsCorrectly`: groups by genre and year.
  - `libraryArtworkEncodes`: with mock `AlbumArtworkLoader` returning bitmap -> base64 JPEG < 6 KB.
  - `libraryArtworkTimesOut`: with loader that hangs -> returns `LibraryArtwork(trackId, base64="", ...)`.

---

## Phase 3 — y2player Playlist Handlers

### y2player/app/src/main/kotlin/com/schulzcode/y2player/library/LibraryRepository.kt

Add (after `deletePlaylist` line 441):
```kotlin
fun renamePlaylist(playlistId: Long, name: String) = stateExecutor.execute {
    val trimmed = name.trim().take(MAX_PLAYLIST_NAME)
    if (trimmed.isEmpty()) return@execute
    database.renamePlaylist(playlistId, trimmed)
    revision += 1
    publish(current.copy(
        revision = revision,
        playlists = current.playlists.map { if (it.id == playlistId) it.copy(name = trimmed) else it }
    ))
}
companion object { private const val MAX_PLAYLIST_NAME = 80 }
```

### y2player/app/src/main/kotlin/com/schulzcode/y2player/library/LibraryDatabase.kt

Add (after `deletePlaylist` line 468):
```kotlin
fun renamePlaylist(playlistId: Long, name: String) {
    writableDatabase.update(
        "playlists",
        ContentValues().apply { put("name", name) },
        "id = ?", arrayOf(playlistId.toString())
    )
}
```

No schema migration needed: the `playlists` table already has `name TEXT` since the current schema (version 15).

### y2player/app/src/main/kotlin/com/schulzcode/y2player/remote/RemoteRequestHandlers.kt

Add:
```kotlin
suspend fun handlePlaylistsList(req: Unit): PlaylistsList
suspend fun handlePlaylistsTracks(req: PlaylistsTracksRequest): PlaylistsTracks
suspend fun handlePlaylistsCreate(req: PlaylistsCreateRequest): PlaylistsMutate
suspend fun handlePlaylistsRename(req: PlaylistsRenameRequest): PlaylistsMutate
suspend fun handlePlaylistsDelete(req: PlaylistsDeleteRequest): PlaylistsMutate
suspend fun handlePlaylistsAddTrack(req: PlaylistsTrackMutation): PlaylistsMutate
suspend fun handlePlaylistsRemoveTrack(req: PlaylistsTrackMutation): PlaylistsMutate
```

All delegate to the corresponding `LibraryRepository` and return `PlaylistsMutate(ok=true, playlist=...)`. Mutations execute via `libraryRepository.*()` (which already runs on `stateExecutor.execute`); handlers `await` with `runCatching`.

### Tests

- `RemoteRequestHandlersTest.kt`: Add `playlistsCreateReturnsNew`, `playlistsRenameUpdatesName`, `playlistsDeleteRemoves`, `playlistsAddTrackInsertsAtEnd`, `playlistsTracksReturnsPaginatedWindow`.

---

## Phase 4 — y2player Queue Handlers + Push Events

### y2player/app/src/main/kotlin/com/schulzcode/y2player/remote/RemoteRequestHandlers.kt

Add (all enqueue to the service's `playbackHandler`, similar to `LocalBinder`):

```kotlin
suspend fun handleQueueState(req: QueueStateRequest): QueueState
suspend fun handleQueueReplace(req: QueueReplaceRequest): QueueMutate
suspend fun handleQueuePlayNext(req: QueueIdsRequest): QueueMutate
suspend fun handleQueueAddToUpNext(req: QueueIdsRequest): QueueMutate
suspend fun handleQueueRemove(req: QueueEntryRequest): QueueMutate
suspend fun handleQueueMove(req: QueueMoveRequest): QueueMutate
suspend fun handleQueuePromote(req: QueueEntryRequest): QueueMutate
suspend fun handleQueueShuffleToggle(req: Unit): QueueMutate
suspend fun handleQueueRepeatCycle(req: Unit): QueueMutate
suspend fun handleQueueClearUpNext(req: Unit): QueueMutate
suspend fun handleQueueClearRemaining(req: Unit): QueueMutate
suspend fun handleQueueClear(req: Unit): QueueMutate
```

Each delegates to the equivalent method in `LocalBinder` (which already posts to `playbackHandler`):
- `replace` -> `playCollection(trackIds, startIndex, shuffled)`. Requires handler to have a `binderProxy` to the service.
- `playNext` -> `playNext(trackIds)`.
- `addToUpNext` -> `addToUpNext(trackIds, shuffled=false)`.
- `remove` -> `removeQueueEntry(entryId)`.
- `move` -> `moveQueueEntry(entryId, delta)`.
- `promote` -> `promoteQueueEntry(entryId)`.
- `shuffleToggle` -> `toggleShuffle()`.
- `repeatCycle` -> `cycleRepeat()`.
- `clearUpNext` -> `clearUpNext()`.
- `clearRemaining` -> `clearRemaining()`.
- `clear` -> `clearQueue()`.

### y2player/app/src/main/kotlin/com/schulzcode/y2player/remote/Y2RemoteServer.kt

- After each queue mutation, `pushEventQueueChanged(reason = "mutation")`.
- `Y2Application.container.libraryRepository.addListener { newState -> pushEventLibraryChanged(newState.revision) }` when `revision` changes.

### PlaybackService.kt

- Expose a `binderProxy: suspend (suspend LocalBinder.() -> Unit) -> Unit` from `LocalBinder` (re-entrant). The pattern already exists in `LocalBinder` with `post {}`; we only need a `CompletableDeferred` per invocation so the handler can `await` before responding.

### Tests

- `RemoteRequestHandlersTest.kt`: Add `queueStateReturnsSnapshot`, `queueReplaceClearsAndRefills`, `queuePlayNextAddsAtTop`, `queueMoveSwapsAdjacentEntries`, `queueShuffleToggleFlipsFlag`.

---

## Phase 5 — y2remote Data Layer

### y2remote/app/build.gradle.kts

Add:
```kotlin
implementation("androidx.paging:paging-runtime-ktx:3.3.4")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
```

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/data/ArtworkCache.kt (New)

```kotlin
class ArtworkCache(context: Context, memoryBytes: Int = 8 * 1024 * 1024) {
    private val memory = object : LruCache<Long, Bitmap>(memoryBytes) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }
    private val diskDir = File(context.cacheDir, "artwork").apply { mkdirs() }
    private val inFlight = ConcurrentHashMap<Long, Deferred<Bitmap?>>()

    suspend fun get(trackId: Long, fetcher: suspend () -> Bitmap?): Bitmap? {
        memory.get(trackId)?.let { return it }
        diskDir.resolve("$trackId.jpg").takeIf { it.exists() }?.let { file ->
            BitmapFactory.decodeFile(file.absolutePath)?.let { bmp ->
                memory.put(trackId, bmp); return bmp
            }
        }
        val deferred = inFlight.getOrPut(trackId) { CoroutineScope(Dispatchers.IO).async { fetcher() } }
        val bmp = deferred.await()
        inFlight.remove(trackId)
        bmp?.let {
            memory.put(trackId, it)
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    FileOutputStream(diskDir.resolve("$trackId.jpg")).use { out ->
                        it.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    }
                }
            }
        }
        return bmp
    }

    fun cancel(trackId: Long) { inFlight.remove(trackId)?.cancel() }
    fun evictAll() { memory.evictAll(); diskDir.listFiles()?.forEach { it.delete() } }
}
```

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/data/LibraryCache.kt (New)

Analogous structure, but for `TrackSummary` JSON. `LruCache<Long, TrackSummary>` (≈ 5000 entries), disk `cacheDir/library/<id>.json`.

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/data/RemoteLibraryPagingSource.kt (New)

```kotlin
class RemoteLibraryPagingSource(
    private val connection: BluetoothConnectionManager,
    private val cache: LibraryCache,
    private val scope: String,
    private val sort: String,
    private val query: String,
) : PagingSource<Int, TrackSummary>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TrackSummary> {
        val offset = params.key ?: 0
        val limit = params.loadSize.coerceAtMost(50)
        val resp = connection.sendLibraryPage(scope, sort, query, offset, limit)
            ?: return LoadResult.Error(IOException("no response"))
        cache.putAll(resp.rows)
        return LoadResult.Page(
            data = resp.rows.map { it.toSummary() },
            prevKey = if (offset == 0) null else (offset - limit).coerceAtLeast(0),
            nextKey = if (resp.hasMore) offset + limit else null,
        )
    }
}
```

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/bluetooth/BluetoothConnectionManager.kt

Add:
```kotlin
data class PendingRequest(val type: String, val requestId: Long,
                          val completion: CompletableDeferred<JSONObject?>)

private val pending = ConcurrentHashMap<Long, PendingRequest>()
private val nextRequestId = AtomicLong(1)

fun sendRequest(type: String, payload: JSONObject, timeoutMs: Long = 2000L): JSONObject? {
    val id = nextRequestId.getAndIncrement()
    val deferred = CompletableDeferred<JSONObject?>()
    pending[id] = PendingRequest(type, id, deferred)
    val json = JSONObject().apply {
        put("type", type)
        put("id", id)
        // merge payload keys
    }
    connectedWorker?.send(json.toString())
    return runBlocking {
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            null
        }
    }
}
```

- In `ConnectedWorker.run()`, add branches for response types (`library.page`, `playlists.list`, `queue.state`, etc.) -> `pending[id]?.completion?.complete(json)`.
- Cancel all `pending` requests in `disconnect()`.

### Tests

- `y2remote/src/test/kotlin/com/nokia_xd/y2remote/data/ArtworkCacheTest.kt`: Memory hit, disk hit, miss + fetch + write disk, in-flight dedup.
- `y2remote/src/test/kotlin/com/nokia_xd/y2remote/data/LibraryCacheTest.kt`: Same for library cache.
- `y2remote/src/test/kotlin/com/nokia_xd/y2remote/data/RemoteLibraryPagingSourceTest.kt`: With fake `BluetoothConnectionManager`, verify offset/limit, prev/next keys.
- `y2remote/src/test/kotlin/com/nokia_xd/y2remote/bluetooth/RequestProtocolTest.kt`: Correlative id, timeout, dedup.

---

## Phase 6 — y2remote Library + Playlists UI

### y2remote/app/src/main/res/layout/activity_main.xml

Replace with `DrawerLayout` or `CoordinatorLayout` + `BottomNavigationView` + `FrameLayout` for fragments.

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/ui/MainActivity.kt

- `BottomNavigationView` with 3 items: `player`, `library`, `queue`.
- Replace current `setupListeners()` and `observeViewModel()` with `FragmentManager` swapping `PlayerFragment`/`LibraryFragment`/`QueueFragment`.

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/ui/PlayerViewModel.kt

Add (to existing class):
```kotlin
val libraryPageFlow: Flow<PagingData<TrackSummary>>
val playlists: StateFlow<List<PlaylistRow>>
val queueState: StateFlow<QueueState?>

fun setLibraryScope(scope: String)
fun setLibrarySort(sort: String)
fun setLibraryQuery(query: String)

suspend fun fetchArtwork(trackId: Long): Bitmap?  // uses ArtworkCache + sendRequest

fun createPlaylist(name: String? = null)
fun renamePlaylist(id: Long, name: String)
fun deletePlaylist(id: Long)
fun addTrackToPlaylist(playlistId: Long, trackId: Long)
fun removeTrackFromPlaylist(playlistId: Long, trackId: Long)
fun loadPlaylistTracks(playlistId: Long): Flow<PagingData<TrackSummary>>

fun playTrack(trackId: Long, fromPlaylistId: Long? = null)
fun replaceQueue(trackIds: List<Long>, startIndex: Int = 0)
fun playNext(trackIds: List<Long>)
fun addToUpNext(trackIds: List<Long>)
fun removeQueueEntry(entryId: Long)
fun moveQueueEntry(entryId: Long, delta: Int)
fun promoteQueueEntry(entryId: Long)
fun toggleShuffle()
fun cycleRepeat()
fun clearUpNext()
fun clearRemaining()
fun clearQueue()
```

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/ui/LibraryFragment.kt (New)

- `RecyclerView` with `PagingDataAdapter<TrackSummary, TrackViewHolder>`.
- `SearchView` with 300 ms debounce via `Flow.debounce` in ViewModel.
- Material `Spinner` with `TrackSortOrder.values()` (6 entries: Title/Artist/Album/Year/Added/Recent).
- `Spinner` with dynamic scopes: `[All, <genres from library.summary>, <years from library.summary>]`.
- `RecyclerView.OnScrollListener`:
  - `onScrolled`: for each `ViewHolder` whose top >= 0 and bottom <= recyclerView.height -> `viewModel.fetchArtwork(trackId)`. For views leaving screen -> `artworkCache.cancel(trackId)`.
- `RecyclerView.addOnChildAttachStateChangeListener` to cancel fetch on detach.

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/ui/PlaylistsFragment.kt (New)

- List of playlists (simple non-paginated `RecyclerView`).
- FAB (`FloatingActionButton`) -> dialog with `EditText` -> `viewModel.createPlaylist(name)`.
- Long-press -> bottom sheet with Rename/Delete.
- Rename -> dialog -> `viewModel.renamePlaylist(id, name)`.
- Delete -> confirmation -> `viewModel.deletePlaylist(id)`.
- Click on playlist -> `PlaylistTracksFragment` (reuses `LibraryFragment` with forced scope).

### Tests

- `PlayerViewModelTest.kt`: With fake `BluetoothConnectionManager`, verify that `setLibraryScope` restarts `Pager` and emits new `PagingData`.

---

## Phase 7 — y2remote Queue UI

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/ui/QueueFragment.kt (New)

- `RecyclerView` with flat list of `QueueEntryRow`.
- `ItemTouchHelper(SimpleCallback(UP or DOWN, 0))`:
  - `onMove`: `viewModel.moveQueueEntry(entryId, +1 or -1)`.
  - `onSwiped` disabled.
- Long-press item -> bottom sheet: Promote (`promoteQueueEntry`), Remove (`removeQueueEntry`).
- Overflow menu (top app bar): Shuffle toggle, Repeat cycle, Clear Up Next, Clear Remaining, Clear Queue.
- Auto refresh: listen for `EventQueueChanged` -> re-request `queue.state`.

---

## Phase 8 — Bluetooth Hygiene (Fine Tuning)

Cross-cutting rules:

- `RemoteRequestHandlers.handleLibraryPage`: cap `limit = 50`; if playing (`PlaybackStatus.PLAYING`), cap `limit = 25`.
- `ArtworkCache`: `inFlight` prevents duplicate requests; `cancel(trackId)` upon ViewHolder detach.
- `PlayerViewModel`: Each `MutableStateFlow<String>` for query/sort/scope uses `debounce(200)` before invalidating `Pager`.
- Rate-limit in `sendCommand` for toggle shuffle/repeat: ignore pulses < 50 ms apart.

No new code in `y2player`; in `y2remote`, implemented as part of previous phases.

---

## Phase 9 — End-to-End Smoke Test (Manual + Scripts)

### Scripts in `tools/` (New)

- `tools/bt_throughput_check.py`: uses `pybluez` to open an RFCOMM socket to UUID `a9c336b4-2dfb-4f9e-a89e-21ef1c60f4e1`, dump messages, validate max size.

### Manual Checklist

1. Pair debug `y2player` + debug `y2remote`.
2. Connect `y2remote` -> verify Hello v2 handshake.
3. Open Library tab, scroll 1000 tracks -> verify (via logcat) that no message exceeds 14 KB, and `library.page` is requested <= 5 times per second.
4. Search "the" -> verify 300 ms debounce.
5. Create/rename/delete playlist from phone -> confirm on player.
6. Queue tab -> drag to reorder, promote, remove, shuffle toggle -> confirm on player that queue changes and audio does not stutter.

---

## Execution Order

1. **Phase 1** — Protocol v2 in both projects + tests.
2. **Phase 2** — Library handlers in y2player + tests.
3. **Phase 3** — Playlist handlers in y2player + tests.
4. **Phase 4** — Queue handlers + push events in y2player + tests.
5. **Phase 5** — Caches + paging source + request/response in y2remote + tests.
6. **Phase 6** — Library + playlists UI in y2remote.
7. **Phase 7** — Queue UI in y2remote.
8. **Phase 8** — Cross-cutting Bluetooth hygiene.
9. **Phase 9** — E2E smoke tests.

Each phase is completed with passing tests before proceeding to the next.
