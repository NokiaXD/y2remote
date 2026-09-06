package com.nokia_xd.y2remote.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.nokia_xd.y2remote.protocol.RemoteCommand
import com.nokia_xd.y2remote.protocol.RemoteMessage
import com.nokia_xd.y2remote.protocol.RemoteProtocol
import com.nokia_xd.y2remote.util.RemoteLogger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class BluetoothConnectionManager {

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        data class Connecting(val deviceName: String) : ConnectionState()
        data class Connected(val deviceName: String, val address: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    interface Listener {
        fun onConnectionStateChanged(state: ConnectionState)
        fun onPlayerStateReceived(state: RemoteMessage.PlayerState)
        fun onArtworkReceived(bitmap: android.graphics.Bitmap?)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val isConnectingOrConnected = AtomicBoolean(false)
    private var connectThread: ConnectThread? = null
    @Volatile private var connectedWorker: ConnectedWorker? = null

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
        isConnectingOrConnected.set(false)
        connectThread?.cancel()
        connectThread = null
        connectedWorker?.cancel()
        connectedWorker = null
        updateState(ConnectionState.Disconnected)
    }

    fun sendCommand(command: RemoteCommand) {
        val json = RemoteProtocol.encodeCommand(command)
        RemoteLogger.log("TX", "Command -> $json")
        connectedWorker?.send(json)
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

            // Strategy 1: Insecure RFCOMM with custom UUID (best for Android 4.4 + modern Android)
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

            // Strategy 4: Direct Channel 1 Insecure Reflection (MediaTek / KitKat direct port)
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
                updateState(ConnectionState.Connected(devName, device.address))
                val worker = ConnectedWorker(connectedSocket, device)
                connectedWorker = worker
                worker.start()
            } else {
                runCatching { socket?.close() }
                if (isConnectingOrConnected.get()) {
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
        private val writeLock = Any()
        @Volatile private var writer: BufferedWriter? = null
        @Volatile var isRunning = true

        override fun run() {
            var reader: BufferedReader? = null
            try {
                val out = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
                writer = out
                reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))

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
                runCatching { reader?.close() }
                runCatching { writer?.close() }
                runCatching { socket.close() }
                if (isConnectingOrConnected.get()) {
                    updateState(ConnectionState.Disconnected)
                }
            }
        }

        fun send(message: String) {
            if (!isRunning) return
            try {
                synchronized(writeLock) {
                    val w = writer ?: return
                    w.write(message)
                    w.write("\n")
                    w.flush()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send command: ${e.message}")
                cancel()
            }
        }

        fun cancel() {
            isRunning = false
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "Y2BluetoothClient"
    }
}
