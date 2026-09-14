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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors
import com.nokia_xd.y2remote.R
import com.nokia_xd.y2remote.bluetooth.BluetoothConnectionManager
import com.nokia_xd.y2remote.databinding.ActivityMainBinding
import com.nokia_xd.y2remote.service.RemoteControlService
import com.nokia_xd.y2remote.util.LastConnection
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: PlayerViewModel by viewModels()

    private var remoteService: RemoteControlService? = null
    private var isBound = false
    private var autoConnectAttempted = false

    private val playerFragment by lazy { PlayerFragment() }
    private val libraryFragment by lazy { LibraryFragment() }
    private val playlistsFragment by lazy { PlaylistsFragment() }
    private val queueFragment by lazy { QueueFragment() }

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
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            binding.bottomNavigation.setPadding(0, 0, 0, systemBars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        if (savedInstanceState == null) {
            showFragment(playerFragment)
        }

        startAndBindService()
        checkAndRequestPermissions()
        setupBottomNavigation()
        observeDevicesForAutoConnect()
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

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_player -> {
                    showFragment(playerFragment)
                    true
                }
                R.id.nav_library -> {
                    showFragment(libraryFragment)
                    true
                }
                R.id.nav_playlists -> {
                    showFragment(playlistsFragment)
                    true
                }
                R.id.nav_queue -> {
                    showFragment(queueFragment)
                    true
                }
                else -> false
            }
        }
    }

    private fun showFragment(fragment: Fragment) {
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (current === fragment) return

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun observeDevicesForAutoConnect() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pairedDevices.collect { devices ->
                    maybeAutoConnect(devices)
                }
            }
        }
    }

    private fun maybeAutoConnect(devices: List<BluetoothDevice>) {
        if (autoConnectAttempted || remoteService == null || devices.isEmpty()) return
        val state = viewModel.connectionState.value
        if (state is BluetoothConnectionManager.ConnectionState.Connected ||
            state is BluetoothConnectionManager.ConnectionState.Connecting
        ) return
        val savedAddress = LastConnection.load(this) ?: return
        val device = devices.firstOrNull { it.address == savedAddress } ?: return
        autoConnectAttempted = true
        viewModel.connectToDevice(device)
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadPairedDevices()
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
