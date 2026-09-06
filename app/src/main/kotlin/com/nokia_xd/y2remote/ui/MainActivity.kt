package com.nokia_xd.y2remote.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nokia_xd.y2remote.R
import com.nokia_xd.y2remote.bluetooth.BluetoothConnectionManager
import com.nokia_xd.y2remote.databinding.ActivityMainBinding
import com.nokia_xd.y2remote.protocol.RemoteCommand
import com.nokia_xd.y2remote.protocol.RemoteProtocol
import com.nokia_xd.y2remote.service.RemoteControlService
import com.nokia_xd.y2remote.util.LastConnection
import kotlinx.coroutines.launch
import java.util.Locale

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: PlayerViewModel by viewModels()

    private var remoteService: RemoteControlService? = null
    private var isBound = false
    private var isUserSeeking = false
    private var isUserAdjustingVolume = false
    private var autoConnectAttempted = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val bluetoothGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: true
        if (bluetoothGranted) {
            viewModel.loadPairedDevices()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required to connect to Y2", Toast.LENGTH_LONG).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RemoteControlService.LocalBinder ?: return
            remoteService = binder.service
            viewModel.bindConnectionManager(binder.connectionManager)
            isBound = true
            maybeAutoConnect(viewModel.pairedDevices.value)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startAndBindService()
        checkAndRequestPermissions()
        setupListeners()
        observeViewModel()
    }

    private fun startAndBindService() {
        val intent = Intent(this, RemoteControlService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            viewModel.loadPairedDevices()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadPairedDevices()
    }

    private fun setupListeners() {
        binding.btnRefresh.setOnClickListener {
            checkAndRequestPermissions()
            viewModel.loadPairedDevices()
            Toast.makeText(this, "Refreshing paired devices...", Toast.LENGTH_SHORT).show()
        }

        binding.btnConnect.setOnClickListener {
            val state = viewModel.connectionState.value
            if (state is BluetoothConnectionManager.ConnectionState.Connected ||
                state is BluetoothConnectionManager.ConnectionState.Connecting
            ) {
                viewModel.disconnect()
            } else {
                val selectedDevice = binding.spinnerDevices.selectedItem as? BluetoothDeviceItem
                if (selectedDevice != null) {
                    LastConnection.save(this, selectedDevice.device.address)
                    viewModel.connectToDevice(selectedDevice.device)
                } else {
                    Toast.makeText(this, "Select a paired Y2 device first", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnPlayPause.setOnClickListener {
            viewModel.sendCommand(RemoteCommand.Toggle)
        }

        binding.btnNext.setOnClickListener {
            viewModel.sendCommand(RemoteCommand.Next)
        }

        binding.btnPrevious.setOnClickListener {
            viewModel.sendCommand(RemoteCommand.Previous)
        }

        binding.btnVolumeUp.setOnClickListener {
            viewModel.volumeUp()
        }

        binding.btnVolumeDown.setOnClickListener {
            viewModel.volumeDown()
        }

        binding.seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    viewModel.setVolume(progress, immediate = false)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserAdjustingVolume = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserAdjustingVolume = false
                val targetProgress = seekBar?.progress ?: return
                viewModel.setVolume(targetProgress, immediate = true)
            }
        })

        binding.seekBarProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentPosition.text = formatDuration(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                val targetMs = seekBar?.progress?.toLong() ?: 0L
                viewModel.seekTo(targetMs)
            }
        })
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.pairedDevices.collect { devices ->
                        val items = devices.map { BluetoothDeviceItem(it) }
                        val adapter = ArrayAdapter(
                            this@MainActivity,
                            R.layout.item_device_spinner,
                            items
                        ).apply {
                            setDropDownViewResource(R.layout.item_device_dropdown)
                        }
                        binding.spinnerDevices.adapter = adapter
                        val savedAddress = LastConnection.load(this@MainActivity)
                        if (savedAddress != null) {
                            val savedIndex = devices.indexOfFirst { it.address == savedAddress }
                            if (savedIndex >= 0) {
                                binding.spinnerDevices.setSelection(savedIndex)
                            }
                        }
                        maybeAutoConnect(devices)
                    }
                }

                launch {
                    viewModel.connectionState.collect { state ->
                        when (state) {
                            is BluetoothConnectionManager.ConnectionState.Connected -> {
                                binding.tvConnectionStatus.text = getString(R.string.status_connected) + ": " + state.deviceName
                                binding.btnConnect.text = getString(R.string.btn_disconnect)
                            }
                            is BluetoothConnectionManager.ConnectionState.Connecting -> {
                                binding.tvConnectionStatus.text = getString(R.string.status_connecting) + " (" + state.deviceName + ")"
                                binding.btnConnect.text = getString(R.string.btn_disconnect)
                            }
                            is BluetoothConnectionManager.ConnectionState.Disconnected -> {
                                binding.tvConnectionStatus.text = getString(R.string.status_disconnected)
                                binding.btnConnect.text = getString(R.string.btn_connect)
                            }
                            is BluetoothConnectionManager.ConnectionState.Error -> {
                                binding.tvConnectionStatus.text = getString(R.string.status_error) + ": " + state.message
                                binding.btnConnect.text = getString(R.string.btn_connect)
                                Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                launch {
                    viewModel.artwork.collect { bitmap ->
                        if (bitmap != null) {
                            binding.ivArtwork.setImageBitmap(bitmap)
                        } else {
                            binding.ivArtwork.setImageResource(R.drawable.ic_notification)
                        }
                    }
                }

                launch {
                    viewModel.volumePercent.collect { vol ->
                        if (!isUserAdjustingVolume) {
                            binding.seekBarVolume.progress = vol
                        }
                    }
                }

                launch {
                    viewModel.playerState.collect { state ->
                        if (state != null) {
                            binding.tvTrackTitle.text = state.title.ifEmpty { getString(R.string.no_track) }
                            binding.tvArtist.text = state.artist.ifEmpty { getString(R.string.unknown_artist) }
                            binding.tvAlbum.text = state.album.ifEmpty { getString(R.string.unknown_album) }
                            binding.tvTotalDuration.text = formatDuration(state.durationMs)
                            binding.seekBarProgress.max = state.durationMs.toInt()

                            val isPlaying = state.status == RemoteProtocol.STATUS_PLAYING
                            binding.btnPlayPause.setBackgroundResource(
                                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                            )
                        } else {
                            binding.tvTrackTitle.text = getString(R.string.no_track)
                            binding.tvArtist.text = getString(R.string.unknown_artist)
                            binding.tvAlbum.text = getString(R.string.unknown_album)
                            binding.tvCurrentPosition.text = getString(R.string.time_zero)
                            binding.tvTotalDuration.text = getString(R.string.time_zero)
                            binding.seekBarProgress.progress = 0
                            binding.btnPlayPause.setBackgroundResource(R.drawable.ic_play)
                        }
                    }
                }

                launch {
                    viewModel.interpolatedPositionMs.collect { posMs ->
                        if (!isUserSeeking) {
                            binding.seekBarProgress.progress = posMs.toInt()
                            binding.tvCurrentPosition.text = formatDuration(posMs)
                        }
                    }
                }
            }
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun maybeAutoConnect(devices: List<BluetoothDevice>) {
        if (autoConnectAttempted || remoteService == null || devices.isEmpty()) return
        val state = viewModel.connectionState.value
        if (state is BluetoothConnectionManager.ConnectionState.Connected ||
            state is BluetoothConnectionManager.ConnectionState.Connecting
        ) return
        val savedAddress = LastConnection.load(this) ?: return
        val device = devices.firstOrNull { it.address == savedAddress } ?: return
        val savedIndex = devices.indexOf(device)
        if (savedIndex >= 0 && binding.spinnerDevices.selectedItemPosition != savedIndex) {
            binding.spinnerDevices.setSelection(savedIndex)
        }
        autoConnectAttempted = true
        viewModel.connectToDevice(device)
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private data class BluetoothDeviceItem(val device: BluetoothDevice) {
        override fun toString(): String = (device.name ?: "Unknown") + " (${device.address})"
    }
}
