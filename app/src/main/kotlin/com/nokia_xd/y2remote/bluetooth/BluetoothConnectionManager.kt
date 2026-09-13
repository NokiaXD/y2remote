package com.nokia_xd.y2remote.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.nokia_xd.y2remote.protocol.RemoteCommand
import com.nokia_xd.y2remote.protocol.RemoteMessage
import com.nokia_xd.y2remote.protocol.RemoteProtocol
import com.nokia_xd.y2remote.util.RemoteLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@SuppressLint("MissingPermission")
class BluetoothConnectionManager {

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        data class Connecting(val deviceName: String) : ConnectionState()
        data class Connected(val deviceName: String, val address: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    interface Listener {
        fun onConnectionStateChanged(state: ConnectionState) {}
        fun onPlayerStateReceived(state: RemoteMessage.PlayerState) {}
        fun onArtworkReceived(bitmap: android.graphics.Bitmap?) {}
        fun onEventQueueChanged(reason: String, revision: Long) {}
        fun onEventLibraryChanged(revision: Long) {}
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val isConnectingOrConnected = AtomicBoolean(false)
    private var connectThread: ConnectThread? = null
    @Volatile private var connectedWorker: ConnectedWorker? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var targetDevice: BluetoothDevice? = null
    private val reconnectRunnable = Runnable {
        val dev = targetDevice
        if (dev != null && isConnectingOrConnected.get() && connectedWorker == null) {
            RemoteLogger.log("BT", "Auto-reconnecting to ${dev.name ?: dev.address}...")
            val thread = ConnectThread(dev)
            connectThread = thread
            thread.start()
        }
    }

    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<RemoteMessage>>()
    private val nextRequestId = AtomicLong(1L)

    @Volatile var currentState: ConnectionState = ConnectionState.Disconnected
        private set
    @Volatile var lastPlayerState: RemoteMessage.PlayerState? = null
        private set
    @Volatile var lastArtwork: android.graphics.Bitmap? = null
        private set

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onConnectionStateChanged(currentState)
        lastPlayerState?.let { listener.onPlayerStateReceived(it) }
        lastArtwork?.let { listener.onArtworkReceived(it) }
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    private fun updateState(newState: ConnectionState) {
        currentState = newState
        if (newState is ConnectionState.Disconnected || newState is ConnectionState.Error) {
            lastPlayerState = null
            lastArtwork = null
            cancelAllPendingRequests()
        }
        when (newState) {
            is ConnectionState.Connected -> RemoteLogger.log("BT", "CONNECTED to ${newState.deviceName} (${newState.address})")
            is ConnectionState.Connecting -> RemoteLogger.log("BT", "Connecting to ${newState.deviceName}...")
            is ConnectionState.Disconnected -> RemoteLogger.log("BT", "Disconnected.")
            is ConnectionState.Error -> RemoteLogger.log("BT", "ERROR: ${newState.message}")
        }
        listeners.forEach { it.onConnectionStateChanged(newState) }
    }

    @Synchronized
    fun connect(device: BluetoothDevice) {
        disconnect()
        targetDevice = device
        isConnectingOrConnected.set(true)
        val devName = device.name ?: device.address
        RemoteLogger.log("BT", "Initiating connection to $devName (${device.address})")
        updateState(ConnectionState.Connecting(devName))
        val thread = ConnectThread(device)
        connectThread = thread
        thread.start()
    }

    @Synchronized
    fun disconnect() {
        targetDevice = null
        mainHandler.removeCallbacks(reconnectRunnable)
        isConnectingOrConnected.set(false)
        connectThread?.cancel()
        connectThread = null
        connectedWorker?.cancel()
        connectedWorker = null
        cancelAllPendingRequests()
        updateState(ConnectionState.Disconnected)
    }

    fun sendCommand(command: RemoteCommand) {
        val json = RemoteProtocol.encodeCommand(command)
        RemoteLogger.log("TX", "Command -> $json")
        connectedWorker?.send(json)
    }

    private fun cancelAllPendingRequests() {
        pendingRequests.forEach { (_, deferred) -> deferred.cancel() }
        pendingRequests.clear()
    }

    suspend fun sendRequest(messageJson: String, requestId: Long, timeoutMs: Long = 3000L): RemoteMessage? {
        val worker = connectedWorker ?: run {
            RemoteLogger.log("BT", "sendRequest failed: worker is null (not connected)")
            return null
        }
        val deferred = CompletableDeferred<RemoteMessage>()
        pendingRequests[requestId] = deferred
        try {
            RemoteLogger.log("TX", "Request #$requestId -> ${messageJson.take(120)}")
            worker.send(messageJson)
            val response = withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }
            if (response == null) {
                RemoteLogger.log("BT", "Request #$requestId timed out after ${timeoutMs}ms")
            }
            return response
        } finally {
            pendingRequests.remove(requestId)
        }
    }

    // --- Request Helpers ---

    suspend fun requestLibraryPage(scope: String, sort: String, query: String, offset: Int, limit: Int): RemoteMessage.LibraryPageResult? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodeLibraryPage(scope, sort, query, offset, limit, reqId)
        return sendRequest(json, reqId) as? RemoteMessage.LibraryPageResult
    }

    suspend fun requestLibrarySummary(): RemoteMessage.LibrarySummary? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodeLibrarySummaryRequest(reqId)
        return sendRequest(json, reqId) as? RemoteMessage.LibrarySummary
    }

    suspend fun requestLibraryArtwork(trackId: Long): RemoteMessage.LibraryArtworkResult? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodeLibraryArtworkRequest(trackId, reqId)
        return sendRequest(json, reqId, timeoutMs = 4000L) as? RemoteMessage.LibraryArtworkResult
    }

    suspend fun requestPlaylistsList(): RemoteMessage.PlaylistsList? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodePlaylistsListRequest(reqId)
        return sendRequest(json, reqId) as? RemoteMessage.PlaylistsList
    }

    suspend fun requestPlaylistsTracks(playlistId: Long, offset: Int, limit: Int): RemoteMessage.PlaylistsTracks? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodePlaylistsTracksRequest(playlistId, offset, limit, reqId)
        return sendRequest(json, reqId) as? RemoteMessage.PlaylistsTracks
    }

    suspend fun requestPlaylistsCreate(name: String): RemoteMessage.PlaylistsMutate? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodePlaylistsCreate(name, reqId)
        return sendRequest(json, reqId) as? RemoteMessage.PlaylistsMutate
    }

    suspend fun requestPlaylistsRename(playlistId: Long, name: String): RemoteMessage.PlaylistsMutate? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodePlaylistsRename(playlistId, name, reqId)
        return sendRequest(json, reqId) as? RemoteMessage.PlaylistsMutate
    }

    suspend fun requestPlaylistsDelete(playlistId: Long): RemoteMessage.PlaylistsMutate? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodePlaylistsDelete(playlistId, reqId)
        return sendRequest(json, reqId) as? RemoteMessage.PlaylistsMutate
    }

    suspend fun requestPlaylistsAddTrack(playlistId: Long, trackId: Long): RemoteMessage.PlaylistsMutate? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodePlaylistsAddTrack(playlistId, trackId, reqId)
        return sendRequest(json, reqId) as? RemoteMessage.PlaylistsMutate
    }

    suspend fun requestPlaylistsRemoveTrack(playlistId: Long, trackId: Long): RemoteMessage.PlaylistsMutate? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodePlaylistsRemoveTrack(playlistId, trackId, reqId)
        return sendRequest(json, reqId) as? RemoteMessage.PlaylistsMutate
    }

    suspend fun requestQueueState(offset: Int = 0, limit: Int = 20): RemoteMessage.QueueState? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodeQueueStateRequest(reqId, offset, limit)
        return sendRequest(json, reqId) as? RemoteMessage.QueueState
    }

    suspend fun requestQueueReplace(trackIds: List<Long>, startIndex: Int = 0, shuffled: Boolean = false): RemoteMessage.QueueMutate? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodeQueueReplace(trackIds, startIndex, shuffled, reqId)
        return sendRequest(json, reqId) as? RemoteMessage.QueueMutate
    }

    suspend fun requestQueuePlayNext(trackIds: List<Long>): RemoteMessage.QueueMutate? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodeQueuePlayNext(trackIds, reqId)
        return sendRequest(json, reqId) as? RemoteMessage.QueueMutate
    }

    suspend fun requestQueueAddToUpNext(trackIds: List<Long>): RemoteMessage.QueueMutate? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodeQueueAddToUpNext(trackIds, reqId)
        return sendRequest(json, reqId) as? RemoteMessage.QueueMutate
    }

    suspend fun requestQueueRemove(entryId: Long): RemoteMessage.QueueMutate? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodeQueueRemoveEntry(entryId, reqId)
        return sendRequest(json, reqId) as? RemoteMessage.QueueMutate
    }

    suspend fun requestQueueMove(entryId: Long, delta: Int): RemoteMessage.QueueMutate? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodeQueueMoveEntry(entryId, delta, reqId)
        return sendRequest(json, reqId) as? RemoteMessage.QueueMutate
    }

    suspend fun requestQueuePromote(entryId: Long): RemoteMessage.QueueMutate? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodeQueuePromoteEntry(entryId, reqId)
        return sendRequest(json, reqId) as? RemoteMessage.QueueMutate
    }

    suspend fun requestQueueShuffleToggle(): RemoteMessage.QueueMutate? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodeQueueShuffleToggle(reqId)
        return sendRequest(json, reqId) as? RemoteMessage.QueueMutate
    }

    suspend fun requestQueueRepeatCycle(): RemoteMessage.QueueMutate? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodeQueueRepeatCycle(reqId)
        return sendRequest(json, reqId) as? RemoteMessage.QueueMutate
    }

    suspend fun requestQueueClearUpNext(): RemoteMessage.QueueMutate? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodeQueueClearUpNext(reqId)
        return sendRequest(json, reqId) as? RemoteMessage.QueueMutate
    }

    suspend fun requestQueueClearRemaining(): RemoteMessage.QueueMutate? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodeQueueClearRemaining(reqId)
        return sendRequest(json, reqId) as? RemoteMessage.QueueMutate
    }

    suspend fun requestQueueClear(): RemoteMessage.QueueMutate? {
        val reqId = nextRequestId.getAndIncrement()
        val json = RemoteProtocol.encodeQueueClear(reqId)
        return sendRequest(json, reqId) as? RemoteMessage.QueueMutate
    }

    private inner class ConnectThread(private val device: BluetoothDevice) : Thread("y2-client-connect") {
        private var socket: BluetoothSocket? = null

        private fun tryConnectSocket(label: String, factory: () -> BluetoothSocket): BluetoothSocket? {
            if (!isConnectingOrConnected.get()) return null
            var s: BluetoothSocket? = null
            return try {
                RemoteLogger.log("BT", "Trying $label...")
                s = factory()
                s.connect()
                RemoteLogger.log("BT", "$label SUCCESS!")
                s
            } catch (e: Exception) {
                RemoteLogger.log("BT", "$label failed: ${e.message}")
                runCatching { s?.close() }
                try { sleep(100) } catch (ignored: InterruptedException) {}
                null
            }
        }

        override fun run() {
            var connectedSocket: BluetoothSocket? = null

            // Crucial: Cancel Bluetooth discovery on the phone before attempting to connect
            runCatching {
                val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                if (adapter?.isDiscovering == true) {
                    adapter.cancelDiscovery()
                    RemoteLogger.log("BT", "Discovery cancelled.")
                }
            }

            // Strategy 1: Insecure RFCOMM with custom UUID
            if (connectedSocket == null) {
                connectedSocket = tryConnectSocket("Strategy 1 (Insecure RFCOMM + Custom UUID)") {
                    device.createInsecureRfcommSocketToServiceRecord(RemoteProtocol.UUID_Y2_REMOTE)
                }
            }

            // Strategy 2: Secure RFCOMM with custom UUID
            if (connectedSocket == null) {
                connectedSocket = tryConnectSocket("Strategy 2 (Secure RFCOMM + Custom UUID)") {
                    device.createRfcommSocketToServiceRecord(RemoteProtocol.UUID_Y2_REMOTE)
                }
            }

            // Strategy 3: Insecure RFCOMM with Standard SPP UUID
            if (connectedSocket == null) {
                connectedSocket = tryConnectSocket("Strategy 3 (Insecure RFCOMM + Standard SPP)") {
                    device.createInsecureRfcommSocketToServiceRecord(RemoteProtocol.UUID_STANDARD_SPP)
                }
            }

            // Strategy 4: Direct Channel 1 Insecure Reflection
            if (connectedSocket == null) {
                connectedSocket = tryConnectSocket("Strategy 4 (Reflection Insecure Channel 1)") {
                    val method = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                    method.invoke(device, 1) as BluetoothSocket
                }
            }

            // Strategy 5: Direct Channel 1 Secure Reflection
            if (connectedSocket == null) {
                connectedSocket = tryConnectSocket("Strategy 5 (Reflection Secure Channel 1)") {
                    val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    method.invoke(device, 1) as BluetoothSocket
                }
            }

            if (connectedSocket != null && isConnectingOrConnected.get()) {
                socket = connectedSocket
                val devName = device.name ?: device.address
                val worker = ConnectedWorker(connectedSocket, device)
                connectedWorker = worker
                worker.start()
                updateState(ConnectionState.Connected(devName, device.address))
            } else {
                runCatching { socket?.close() }
                if (isConnectingOrConnected.get() && targetDevice != null) {
                    updateState(ConnectionState.Error("Retrying connection in 3s..."))
                    mainHandler.removeCallbacks(reconnectRunnable)
                    mainHandler.postDelayed(reconnectRunnable, 3000L)
                } else if (isConnectingOrConnected.get()) {
                    isConnectingOrConnected.set(false)
                    updateState(ConnectionState.Error("All connection strategies failed. Ensure Y2Player is running on Y2."))
                }
            }
        }

        fun cancel() {
            runCatching { socket?.close() }
        }
    }

    private inner class ConnectedWorker(
        private val socket: BluetoothSocket,
        private val device: BluetoothDevice
    ) : Thread("y2-client-worker") {
        private val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
        private val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        private val outboundQueue = LinkedBlockingQueue<String>(64)
        @Volatile var isRunning = true

        private val writerThread = Thread({
            try {
                while (isRunning && isConnectingOrConnected.get()) {
                    val msg = outboundQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    try {
                        writer.write(msg)
                        writer.write("\n")
                        writer.flush()
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to send message: ${e.message}")
                        cancel()
                        break
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                runCatching { writer.close() }
            }
        }, "y2-client-sender").apply { isDaemon = true }

        override fun run() {
            writerThread.start()
            try {
                // Send Hello
                val hello = RemoteProtocol.encodeHello()
                send(hello)
                RemoteLogger.log("TX", "Handshake sent: $hello")

                while (isRunning && isConnectingOrConnected.get()) {
                    val line = reader.readLine() ?: break
                    val message = RemoteProtocol.parseMessage(line) ?: continue
                    when (message) {
                        is RemoteMessage.PlayerState -> {
                            lastPlayerState = message
                            RemoteLogger.log("RX", "Track: '${message.title}' by '${message.artist}' [${message.status}]")
                            listeners.forEach { it.onPlayerStateReceived(message) }
                        }
                        is RemoteMessage.Artwork -> {
                            RemoteLogger.log("RX", "Artwork received (${message.base64.length} chars)")
                            val bitmap = runCatching {
                                val bytes = android.util.Base64.decode(message.base64, android.util.Base64.DEFAULT)
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }.getOrNull()
                            lastArtwork = bitmap
                            listeners.forEach { it.onArtworkReceived(bitmap) }
                        }
                        is RemoteMessage.Hello -> {
                            RemoteLogger.log("RX", "Handshake from Y2: ${message.device} (v${message.protocol})")
                        }
                        is RemoteMessage.EventQueueChanged -> {
                            RemoteLogger.log("RX", "Event: Queue Changed (reason: ${message.reason}, rev: ${message.revision})")
                            listeners.forEach { it.onEventQueueChanged(message.reason, message.revision) }
                        }
                        is RemoteMessage.EventLibraryChanged -> {
                            RemoteLogger.log("RX", "Event: Library Changed (rev: ${message.revision})")
                            listeners.forEach { it.onEventLibraryChanged(message.revision) }
                        }

                        // Response Messages completed via pendingRequests
                        is RemoteMessage.LibraryPageResult -> {
                            RemoteLogger.log("RX", "Response #${message.requestId} LibraryPageResult (rows: ${message.rows.size}, total: ${message.total})")
                            pendingRequests[message.requestId]?.complete(message)
                        }
                        is RemoteMessage.LibrarySummary -> {
                            RemoteLogger.log("RX", "Response #${message.requestId} LibrarySummary (genres: ${message.genres.size}, total: ${message.total})")
                            pendingRequests[message.requestId]?.complete(message)
                        }
                        is RemoteMessage.LibraryArtworkResult -> {
                            RemoteLogger.log("RX", "Response #${message.requestId} LibraryArtworkResult")
                            pendingRequests[message.requestId]?.complete(message)
                        }
                        is RemoteMessage.PlaylistsList -> {
                            RemoteLogger.log("RX", "Response #${message.requestId} PlaylistsList (count: ${message.items.size})")
                            pendingRequests[message.requestId]?.complete(message)
                        }
                        is RemoteMessage.PlaylistsTracks -> {
                            RemoteLogger.log("RX", "Response #${message.requestId} PlaylistsTracks (rows: ${message.rows.size}, total: ${message.total})")
                            pendingRequests[message.requestId]?.complete(message)
                        }
                        is RemoteMessage.PlaylistsMutate -> {
                            RemoteLogger.log("RX", "Response #${message.requestId} PlaylistsMutate (ok: ${message.ok})")
                            pendingRequests[message.requestId]?.complete(message)
                        }
                        is RemoteMessage.QueueState -> {
                            RemoteLogger.log("RX", "Response #${message.requestId} QueueState (entries: ${message.entries.size})")
                            pendingRequests[message.requestId]?.complete(message)
                        }
                        is RemoteMessage.QueueMutate -> {
                            RemoteLogger.log("RX", "Response #${message.requestId} QueueMutate (ok: ${message.ok})")
                            pendingRequests[message.requestId]?.complete(message)
                        }

                        else -> {
                            RemoteLogger.log("RX", "Message: $line")
                        }
                    }
                }
            } catch (e: IOException) {
                if (isRunning && isConnectingOrConnected.get()) {
                    RemoteLogger.log("BT", "Worker connection error: ${e.message}")
                }
            } finally {
                isRunning = false
                writerThread.interrupt()
                outboundQueue.clear()
                runCatching { reader?.close() }
                runCatching { writer?.close() }
                runCatching { socket.close() }
                if (isConnectingOrConnected.get()) {
                    updateState(ConnectionState.Disconnected)
                    if (targetDevice != null) {
                        RemoteLogger.log("BT", "Disconnected unexpectedly; auto-reconnecting in 2s...")
                        mainHandler.removeCallbacks(reconnectRunnable)
                        mainHandler.postDelayed(reconnectRunnable, 2000L)
                    }
                }
            }
        }

        fun send(message: String) {
            if (!isRunning) return
            outboundQueue.offer(message)
        }

        fun cancel() {
            isRunning = false
            writerThread.interrupt()
            outboundQueue.clear()
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "Y2BluetoothClient"
    }
}

