# Plan: extender el control remoto de Y2 (biblioteca, playlists, cola)

## Contexto

- **y2player** es el dispositivo dedicado de música. El audio sale por Bluetooth (A2DP/HFP). El control remoto entra por un socket RFCOMM aparte (`Y2RemoteServer`, UUID `a9c336b4-2dfb-4f9e-a89e-21ef1c60f4e1`).
- **y2remote** es la app del teléfono. Hoy solo expone play/pause/skip/volume/seek. No recibe la cola ni la biblioteca.

Ambos proyectos están en `D:\y2player` y `D:\y2remote`. El estado que necesitamos exponer ya existe en y2player:
- `LibraryState`/`LibraryIndex` carga toda la biblioteca en memoria al escanear.
- `LibraryOrganization.sortTracks` y `tracks(LibraryScope)` ya implementan los 6 `TrackSortOrder` y los filtros All/Genre/Year.
- `QueueController` ya tiene todas las operaciones (`replace`, `playNext`, `addToUpNext`, `removeEntry`, `moveEntry`, `promoteToPlayNext`, `clearUpNext`, `clearRemaining`, `clear`, `toggleShuffle`, `cycleRepeat`, `snapshot`).
- `PlaybackService.LocalBinder` envuelve cada una de esas llamadas.
- `LibraryRepository` ya tiene `createPlaylist`, `deletePlaylist`, `addTrackToPlaylist`, `removeTrackFromPlaylist`, `loadPlaylists`, `loadAllPlaylistTrackIds`, `findTrack`, `toggleFavorite`. Falta `renamePlaylist`.
- `Y2RemoteServer` hoy solo expone `hello`, `state` (snapshot de pista actual) y `artwork` (base64). El canal `command → handleRemoteCommand` solo cubre play/pause/skip/volume/seek.

Solo falta exponer todo eso por el socket RFCOMM.

## Decisiones (acordadas con el usuario)

- **Filtros**: paridad completa con y2player (6 sort orders + 3 scopes + búsqueda full-text).
- **Playlists**: create, **rename**, delete, add/remove track.
- **Cola**: paridad completa (move, remove, promote, clear_*, shuffle, repeat, play_next batch, add_to_up_next).
- **Paginación**: `androidx.paging3` (acepta las 2-3 deps nuevas).
- **Versión de protocolo**: bump a **v2**, sin fallback (ambos proyectos en v0).

## Restricción crítica: ancho de banda Bluetooth

Hoy un único `BufferedWriter` por socket. Cada `flush()` se bloquea hasta drenar el buffer del kernel. Audio sale por A2DP/HFP (canal BT distinto), pero el radio es el mismo: ráfagas grandes compiten con audio y rompen la reproducción. Reglas:

1. **Nunca enviar la biblioteca entera**. Tamaño máximo por mensaje ≈ 14 KB (margen bajo `MAX_LINE_LENGTH=131072`).
2. **Operaciones request/response** del teléfono → player. Excepción: push de eventos pequeños (`queue.changed`, `library.changed`) cuando cambia el estado.
3. **Artwork bajo demanda**, nunca pre-cargado. Solo filas visibles, caché en dos niveles en el teléfono (memoria + disco).

---

## Fase 1 — Protocolo v2 (compartido)

### y2player/app/src/main/kotlin/com/schulzcode/y2player/remote/RemoteProtocol.kt

**Cambios**:
- `const val PROTOCOL_VERSION = 2` (era 1).
- Nuevos `RemoteMessage`:
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
- Constantes `TYPE_*` para cada uno.
- `encodeLibraryPage(...)`, `parseLibraryPage(...)`, etc. — un par por mensaje.
- `parseMessage` filtra cualquier mensaje v2 si el handshake previo no fue >= 2.

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/protocol/RemoteProtocol.kt

Espejo exacto de los mensajes anteriores en `com.nokia_xd.y2remote.protocol`.

### Tests

- `y2player/src/test/kotlin/com/schulzcode/y2player/remote/RemoteProtocolTest.kt`: añadir `libraryPageRoundTrip`, `libraryArtworkRoundTrip`, `queueStateRoundTrip`, `eventLibraryChangedRoundTrip`.
- `y2remote/src/test/kotlin/com/nokia_xd/y2remote/protocol/RemoteProtocolTest.kt` (crear): round-trip de cada mensaje nuevo.

---

## Fase 2 — y2player handlers de biblioteca

### y2player/app/src/main/kotlin/com/schulzcode/y2player/remote/RemoteRequestHandlers.kt (nuevo)

```kotlin
class RemoteRequestHandlers(
    private val libraryRepository: LibraryRepository,
    private val artworkLoader: AlbumArtworkLoader,
    private val scope: CoroutineScope,
) {
    suspend fun handleLibraryPage(req: LibraryPage): LibraryPageResult
    suspend fun handleLibrarySearch(req: LibraryPage): LibraryPageResult  // mismo helper
    suspend fun handleLibrarySummary(req: Unit): LibrarySummary
    suspend fun handleLibraryArtwork(req: ArtworkRequest): LibraryArtwork
}
```

- `handleLibraryPage`: snapshot de `libraryRepository.snapshot()` → `LibraryOrganization.sortTracks(scope, sort)` (scope parseado a `LibraryScope.All/Genre/Year`) → filtro `query` (case-insensitive sobre title/artist/album) → slice `offset..offset+limit`.
- `handleLibrarySummary`: usa `LibraryOrganization` ya calculado.
- `handleLibraryArtwork`: lookup `findTrack(trackId)`, `artworkLoader.load(path, 128, callback)` con `runBlocking` + `withTimeoutOrNull(1500)`, encode JPEG base64. Si timeout/miss → base64 vacío.
- Cada handler valida `result.toString().length ≤ 14_000` antes de devolver; si excede, reduce `limit` y reintenta una vez.

### y2player/app/src/main/kotlin/com/schulzcode/y2player/remote/Y2RemoteServer.kt

**Cambios**:
- Añadir `private val requestHandlers: RemoteRequestHandlers?` (inyectado).
- En `ConnectedWorker.run()`, distinguir mensajes con `type` en `requestTypes: Set<String>` (los listados abajo). Si es request:
  1. Parsear.
  2. `scope.launch { val resp = handlers.handle*(req); send(RemoteProtocol.encode(resp)) }`.
  3. `send` corre bajo `writeLock` (ya está en `send()`).
- `update(snapshot, track, volume)` se mantiene; añadir `pushEventQueueChanged(reason)` y `pushEventLibraryChanged(rev)`.
- `requestTypes = setOf("library.page", "library.search", "library.summary", "library.artwork", "playlists.list", "playlists.tracks", "playlists.create", "playlists.rename", "playlists.delete", "playlists.add_track", "playlists.remove_track", "queue.state", "queue.replace", "queue.play_next", "queue.add_to_up_next", "queue.remove", "queue.move", "queue.promote", "queue.shuffle.toggle", "queue.repeat.cycle", "queue.clear_up_next", "queue.clear_remaining", "queue.clear")`.

### y2player/app/src/main/kotlin/com/schulzcode/y2player/playback/PlaybackService.kt

- Crear `requestHandlers = RemoteRequestHandlers(libraryRepository, container.artworkLoader, CoroutineScope(SupervisorJob() + playbackHandler.looper))` en el `post { ... }` de inicialización (justo después de crear `libraryRepository`).
- Pasar `requestHandlers` al constructor de `Y2RemoteServer`.
- Push `library.changed` desde `libraryRepository.addListener { listener -> ... }` → cuando `revision` cambia, push event.
- Push `queue.changed` desde un nuevo `private fun afterQueueMutationRemote()` que se llama después de cada mutación exitosa del binder (mirar `afterQueueMutation()` existente en línea ~140) → push event.

### Tests

- `y2player/src/test/kotlin/com/schulzcode/y2player/remote/RemoteRequestHandlersTest.kt` (nuevo, JVM, sin Android):
  - `libraryPageReturnsFirstWindow`: 200 tracks, scope=All, sort=Title, query="", offset=0, limit=50 → 50 rows en orden alfabético, `total=200`, `hasMore=true`.
  - `libraryPageAppliesScope`: scope=Year(2020), solo tracks de ese año.
  - `libraryPageAppliesQuery`: query="the" → match contra título/artista/álbum case-insensitive.
  - `librarySummaryGroupsCorrectly`: agrupa por género y año.
  - `libraryArtworkEncodes`: con `AlbumArtworkLoader` mock que devuelve bitmap → base64 JPEG < 6 KB.
  - `libraryArtworkTimesOut`: con loader que cuelga → devuelve `LibraryArtwork(trackId, base64="", ...)`.

---

## Fase 3 — y2player handlers playlists

### y2player/app/src/main/kotlin/com/schulzcode/y2player/library/LibraryRepository.kt

Añadir (después de `deletePlaylist` línea 441):
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

Añadir (después de `deletePlaylist` línea 468):
```kotlin
fun renamePlaylist(playlistId: Long, name: String) {
    writableDatabase.update(
        "playlists",
        ContentValues().apply { put("name", name) },
        "id = ?", arrayOf(playlistId.toString())
    )
}
```

Sin migración de esquema: la tabla `playlists` ya tiene `name TEXT` desde el schema actual (versión 15).

### y2player/app/src/main/kotlin/com/schulzcode/y2player/remote/RemoteRequestHandlers.kt

Añadir:
```kotlin
suspend fun handlePlaylistsList(req: Unit): PlaylistsList
suspend fun handlePlaylistsTracks(req: PlaylistsTracksRequest): PlaylistsTracks
suspend fun handlePlaylistsCreate(req: PlaylistsCreateRequest): PlaylistsMutate
suspend fun handlePlaylistsRename(req: PlaylistsRenameRequest): PlaylistsMutate
suspend fun handlePlaylistsDelete(req: PlaylistsDeleteRequest): PlaylistsMutate
suspend fun handlePlaylistsAddTrack(req: PlaylistsTrackMutation): PlaylistsMutate
suspend fun handlePlaylistsRemoveTrack(req: PlaylistsTrackMutation): PlaylistsMutate
```

Todos delegan a `LibraryRepository` correspondiente y devuelven `PlaylistsMutate(ok=true, playlist=...)`. Mutaciones se ejecutan vía `libraryRepository.*()` (que ya hace `stateExecutor.execute`); los handlers `await` con `runCatching`.

### Tests

- `RemoteRequestHandlersTest.kt`: añadir `playlistsCreateReturnsNew`, `playlistsRenameUpdatesName`, `playlistsDeleteRemoves`, `playlistsAddTrackInsertsAtEnd`, `playlistsTracksReturnsPaginatedWindow`.

---

## Fase 4 — y2player handlers cola + push events

### y2player/app/src/main/kotlin/com/schulzcode/y2player/remote/RemoteRequestHandlers.kt

Añadir (todos enqueue en `playbackHandler` del servicio, similar a `LocalBinder`):

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

Cada uno delega al método equivalente del `LocalBinder` (que ya hace `post {}` al `playbackHandler`):
- `replace` → `playCollection(trackIds, startIndex, shuffled)`. Requiere que el handler tenga un `binderProxy` al servicio.
- `playNext` → `playNext(trackIds)`.
- `addToUpNext` → `addToUpNext(trackIds, shuffled=false)`.
- `remove` → `removeQueueEntry(entryId)`.
- `move` → `moveQueueEntry(entryId, delta)`.
- `promote` → `promoteQueueEntry(entryId)`.
- `shuffleToggle` → `toggleShuffle()`.
- `repeatCycle` → `cycleRepeat()`.
- `clearUpNext` → `clearUpNext()`.
- `clearRemaining` → `clearRemaining()`.
- `clear` → `clearQueue()`.

### y2player/app/src/main/kotlin/com/schulzcode/y2player/remote/Y2RemoteServer.kt

- Tras cada mutación de cola, `pushEventQueueChanged(reason = "mutation")`.
- `Y2Application.container.libraryRepository.addListener { newState -> pushEventLibraryChanged(newState.revision) }` cuando cambia `revision`.

### PlaybackService.kt

- Exponer un `binderProxy: suspend (suspend LocalBinder.() -> Unit) -> Unit` desde el `LocalBinder` (re-entrante). Ya existe el patrón en `LocalBinder` con `post {}`; solo necesitamos un `CompletableDeferred` por llamada para que el handler `await` antes de responder.

### Tests

- `RemoteRequestHandlersTest.kt`: añadir `queueStateReturnsSnapshot`, `queueReplaceClearsAndRefills`, `queuePlayNextAddsAtTop`, `queueMoveSwapsAdjacentEntries`, `queueShuffleToggleFlipsFlag`.

---

## Fase 5 — y2remote capa de datos

### y2remote/app/build.gradle.kts

Añadir:
```kotlin
implementation("androidx.paging:paging-runtime-ktx:3.3.4")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
```

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/data/ArtworkCache.kt (nuevo)

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

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/data/LibraryCache.kt (nuevo)

Estructura análoga, pero para `TrackSummary` JSON. `LruCache<Long, TrackSummary>` (≈ 5000 entries), disco `cacheDir/library/<id>.json`.

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/data/RemoteLibraryPagingSource.kt (nuevo)

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

Añadir:
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

- En `ConnectedWorker.run()` añadir rama para `type` con respuesta (`library.page`, `playlists.list`, `queue.state`, etc.) → `pending[id]?.completion?.complete(json)`.
- Cancelar todos los `pending` en `disconnect()`.

### Tests

- `y2remote/src/test/kotlin/com/nokia_xd/y2remote/data/ArtworkCacheTest.kt`: hit memoria, hit disco, miss + fetch + write disco, dedup in-flight.
- `y2remote/src/test/kotlin/com/nokia_xd/y2remote/data/LibraryCacheTest.kt`: idem.
- `y2remote/src/test/kotlin/com/nokia_xd/y2remote/data/RemoteLibraryPagingSourceTest.kt`: con fake `BluetoothConnectionManager`, verificar offset/limit, prev/next keys.
- `y2remote/src/test/kotlin/com/nokia_xd/y2remote/bluetooth/RequestProtocolTest.kt`: id correlativo, timeout, dedup.

---

## Fase 6 — y2remote UI biblioteca + playlists

### y2remote/app/src/main/res/layout/activity_main.xml

Reemplazar por `DrawerLayout` o `CoordinatorLayout` + `BottomNavigationView` + `FrameLayout` para fragments.

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/ui/MainActivity.kt

- `BottomNavigationView` con 3 items: `player`, `library`, `queue`.
- Reemplazar el `setupListeners()` y `observeViewModel()` actuales por `FragmentManager` que swappea `PlayerFragment`/`LibraryFragment`/`QueueFragment`.

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/ui/PlayerViewModel.kt

Añadir (a la clase existente):
```kotlin
val libraryPageFlow: Flow<PagingData<TrackSummary>>
val playlists: StateFlow<List<PlaylistRow>>
val queueState: StateFlow<QueueState?>

fun setLibraryScope(scope: String)
fun setLibrarySort(sort: String)
fun setLibraryQuery(query: String)

suspend fun fetchArtwork(trackId: Long): Bitmap?  // usa ArtworkCache + sendRequest

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

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/ui/LibraryFragment.kt (nuevo)

- `RecyclerView` con `PagingDataAdapter<TrackSummary, TrackViewHolder>`.
- `SearchView` con debounce 300 ms vía `Flow.debounce` en el ViewModel.
- `Spinner` (Material) con `TrackSortOrder.values()` (6 entradas: Title/Artist/Album/Year/Added/Recent).
- `Spinner` con scopes dinámicos: `[All, <genres from library.summary>, <years from library.summary>]`.
- `RecyclerView.OnScrollListener`:
  - `onScrolled`: para cada `ViewHolder` cuyo top >= 0 y bottom <= recyclerView.height → `viewModel.fetchArtwork(trackId)`. Para los que salen de pantalla → `artworkCache.cancel(trackId)`.
- `RecyclerView.addOnChildAttachStateChangeListener` para cancelar fetch al detach.

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/ui/PlaylistsFragment.kt (nuevo)

- Lista de playlists (`RecyclerView` simple, no paginada).
- FAB (`FloatingActionButton`) → diálogo con `EditText` → `viewModel.createPlaylist(name)`.
- Long-press → bottom sheet con Rename/Delete.
- Rename → diálogo → `viewModel.renamePlaylist(id, name)`.
- Delete → confirmación → `viewModel.deletePlaylist(id)`.
- Click en playlist → `PlaylistTracksFragment` (reusa `LibraryFragment` con scope forzado).

### Tests

- `PlayerViewModelTest.kt`: con fake `BluetoothConnectionManager`, verificar que `setLibraryScope` reinicia el `Pager` y emite nueva `PagingData`.

---

## Fase 7 — y2remote UI cola

### y2remote/app/src/main/kotlin/com/nokia_xd/y2remote/ui/QueueFragment.kt (nuevo)

- `RecyclerView` con lista plana de `QueueEntryRow`.
- `ItemTouchHelper(SimpleCallback(UP or DOWN, 0))`:
  - `onMove`: `viewModel.moveQueueEntry(entryId, +1 o -1)`.
  - `onSwiped` deshabilitado.
- Long-press item → bottom sheet: Promote (`promoteQueueEntry`), Remove (`removeQueueEntry`).
- Overflow menu (top app bar): Shuffle toggle, Repeat cycle, Clear Up Next, Clear Remaining, Clear Queue.
- Refresco automático: escuchar `EventQueueChanged` → re-pedir `queue.state`.

---

## Fase 8 — Higiene BT (ajustes finos)

Aplicado transversalmente:

- `RemoteRequestHandlers.handleLibraryPage`: cap `limit = 50`; si playing (`PlaybackStatus.PLAYING`), cap `limit = 25`.
- `ArtworkCache`: `inFlight` evita duplicados; `cancel(trackId)` al detach del ViewHolder.
- `PlayerViewModel`: cada `MutableStateFlow<String>` para query/sort/scope usa `debounce(200)` antes de invalidar el `Pager`.
- Rate-limit en `sendCommand` para toggle shuffle/repeat: ignorar pulsos < 50 ms.

Sin código nuevo en `y2player`; en `y2remote`, aplicado en los puntos anteriores.

---

## Fase 9 — Smoke test E2E (manual + scripts)

### Scripts en `tools/` (nuevos)

- `tools/bt_throughput_check.py`: usa `pybluez` para abrir un socket RFCOMM al UUID `a9c336b4-2dfb-4f9e-a89e-21ef1c60f4e1`, dump de mensajes, validar tamaño máx.

### Manual checklist

1. Emparejar `y2player` debug + `y2remote` debug.
2. Conectar `y2remote` → verificar Hello v2.
3. Abrir tab Library, scroll 1000 tracks → medir (logcat) que ningún msg >14 KB, que `library.page` se pide ≤5 veces por segundo.
4. Buscar "the" → ver debounce 300 ms.
5. Crear/rename/delete playlist desde el teléfono → confirmar en el player.
6. Tab Queue → drag para reordenar, promote, remove, shuffle toggle → confirmar en el player que la cola cambia y el audio no se entrecorta.

---

## Orden de ejecución

1. **Fase 1** — protocolo v2 en ambos proyectos + tests.
2. **Fase 2** — handlers biblioteca y2player + tests.
3. **Fase 3** — handlers playlists y2player + tests.
4. **Fase 4** — handlers cola + push events y2player + tests.
5. **Fase 5** — caches + paging source + request/response y2remote + tests.
6. **Fase 6** — UI library + playlists y2remote.
7. **Fase 7** — UI queue y2remote.
8. **Fase 8** — higiene BT transversal.
9. **Fase 9** — smoke test E2E.

Cada fase se cierra con tests pasando antes de pasar a la siguiente.
