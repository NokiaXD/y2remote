package com.nokia_xd.y2remote.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nokia_xd.y2remote.bluetooth.BluetoothConnectionManager
import com.nokia_xd.y2remote.protocol.RemoteCommand
import com.nokia_xd.y2remote.protocol.RemoteMessage
import com.nokia_xd.y2remote.protocol.RemoteProtocol
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class PlayerViewModel : ViewModel(), BluetoothConnectionManager.Listener {

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

    private val _artwork = MutableStateFlow<android.graphics.Bitmap?>(null)
    val artwork: StateFlow<android.graphics.Bitmap?> = _artwork.asStateFlow()

    private val _volumePercent = MutableStateFlow(100)
    val volumePercent: StateFlow<Int> = _volumePercent.asStateFlow()

    private var connectionManager: BluetoothConnectionManager? = null
    private var interpolationJob: Job? = null
    private var lastStateReceivedAtUptime = 0L
    private var basePositionMs = 0L

    fun bindConnectionManager(manager: BluetoothConnectionManager) {
        connectionManager?.removeListener(this)
        connectionManager = manager
        manager.addListener(this)
        _connectionState.value = manager.currentState
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

    fun setVolume(percent: Int) {
        _volumePercent.value = percent
        sendCommand(RemoteCommand.SetVolume(percent))
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

    override fun onConnectionStateChanged(state: BluetoothConnectionManager.ConnectionState) {
        _connectionState.value = state
        if (state is BluetoothConnectionManager.ConnectionState.Disconnected ||
            state is BluetoothConnectionManager.ConnectionState.Error
        ) {
            stopInterpolation()
            _playerState.value = null
            _interpolatedPositionMs.value = 0L
            _artwork.value = null
        }
    }

    override fun onPlayerStateReceived(state: RemoteMessage.PlayerState) {
        _playerState.value = state
        _volumePercent.value = state.volumePercent
        basePositionMs = state.positionMs
        lastStateReceivedAtUptime = SystemClock.uptimeMillis()
        _interpolatedPositionMs.value = state.positionMs

        if (state.status == RemoteProtocol.STATUS_PLAYING) {
            startInterpolation(state.durationMs)
        } else {
            stopInterpolation()
        }
    }

    override fun onArtworkReceived(bitmap: android.graphics.Bitmap?) {
        _artwork.value = bitmap
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
