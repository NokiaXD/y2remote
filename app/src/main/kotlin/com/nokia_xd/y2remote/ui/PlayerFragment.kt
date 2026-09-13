package com.nokia_xd.y2remote.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nokia_xd.y2remote.R
import com.nokia_xd.y2remote.bluetooth.BluetoothConnectionManager
import com.nokia_xd.y2remote.databinding.FragmentPlayerBinding
import com.nokia_xd.y2remote.protocol.RemoteCommand
import com.nokia_xd.y2remote.protocol.RemoteMessage
import com.nokia_xd.y2remote.protocol.RemoteProtocol
import com.nokia_xd.y2remote.util.LastConnection
import kotlinx.coroutines.launch
import java.util.Locale

@SuppressLint("MissingPermission")
class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewModel by activityViewModels()

    private var deviceAdapter: ArrayAdapter<String>? = null
    private var devicesList: List<BluetoothDevice> = emptyList()
    private var isUserSeeking = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDeviceSpinner()
        setupListeners()
        observeViewModel()
    }

    private fun setupDeviceSpinner() {
        deviceAdapter = ArrayAdapter(requireContext(), R.layout.item_device_spinner, mutableListOf())
        deviceAdapter?.setDropDownViewResource(R.layout.item_device_dropdown)
        binding.spinnerDevices.adapter = deviceAdapter

        binding.spinnerDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in devicesList.indices) {
                    val device = devicesList[position]
                    LastConnection.save(requireContext(), device.address)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupListeners() {
        binding.btnRefresh.setOnClickListener {
            viewModel.loadPairedDevices()
        }

        binding.btnConnect.setOnClickListener {
            val state = viewModel.connectionState.value
            if (state is BluetoothConnectionManager.ConnectionState.Connected ||
                state is BluetoothConnectionManager.ConnectionState.Connecting
            ) {
                viewModel.disconnect()
            } else {
                val selectedPos = binding.spinnerDevices.selectedItemPosition
                if (selectedPos in devicesList.indices) {
                    val device = devicesList[selectedPos]
                    viewModel.connectToDevice(device)
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

        binding.btnVolumeDown.setOnClickListener {
            viewModel.volumeDown()
        }

        binding.btnVolumeUp.setOnClickListener {
            viewModel.volumeUp()
        }

        binding.seekBarProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentPosition.text = formatTime(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                seekBar?.let { viewModel.seekTo(it.progress.toLong()) }
            }
        })

        binding.seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    viewModel.setVolume(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { state ->
                        updateConnectionUi(state)
                    }
                }
                launch {
                    viewModel.pairedDevices.collect { devices ->
                        devicesList = devices
                        deviceAdapter?.clear()
                        deviceAdapter?.addAll(devices.map { it.name ?: it.address })
                        val lastAddress = LastConnection.load(requireContext())
                        val index = devices.indexOfFirst { it.address == lastAddress }
                        if (index >= 0) {
                            binding.spinnerDevices.setSelection(index)
                        }
                    }
                }
                launch {
                    viewModel.playerState.collect { state ->
                        updatePlayerUi(state)
                    }
                }
                launch {
                    viewModel.interpolatedPositionMs.collect { pos ->
                        if (!isUserSeeking) {
                            binding.seekBarProgress.progress = pos.toInt()
                            binding.tvCurrentPosition.text = formatTime(pos)
                        }
                    }
                }
                launch {
                    viewModel.artwork.collect { bmp ->
                        if (bmp != null) {
                            binding.ivArtwork.setImageBitmap(bmp)
                        } else {
                            binding.ivArtwork.setImageResource(R.drawable.ic_notification)
                        }
                    }
                }
                launch {
                    viewModel.volumePercent.collect { vol ->
                        binding.seekBarVolume.progress = vol
                    }
                }
            }
        }
    }

    private fun updateConnectionUi(state: BluetoothConnectionManager.ConnectionState) {
        when (state) {
            is BluetoothConnectionManager.ConnectionState.Connected -> {
                binding.tvConnectionStatus.text = getString(R.string.status_connected) + ": ${state.deviceName}"
                binding.tvConnectionStatus.setTextColor(resources.getColor(R.color.accent, null))
                binding.btnConnect.text = getString(R.string.btn_disconnect)
                binding.btnConnect.isEnabled = true
            }
            is BluetoothConnectionManager.ConnectionState.Connecting -> {
                binding.tvConnectionStatus.text = getString(R.string.status_connecting)
                binding.tvConnectionStatus.setTextColor(resources.getColor(R.color.text_secondary, null))
                binding.btnConnect.text = getString(R.string.btn_disconnect)
                binding.btnConnect.isEnabled = true
            }
            is BluetoothConnectionManager.ConnectionState.Disconnected -> {
                binding.tvConnectionStatus.text = getString(R.string.status_disconnected)
                binding.tvConnectionStatus.setTextColor(resources.getColor(R.color.text_primary, null))
                binding.btnConnect.text = getString(R.string.btn_connect)
                binding.btnConnect.isEnabled = devicesList.isNotEmpty()
            }
            is BluetoothConnectionManager.ConnectionState.Error -> {
                binding.tvConnectionStatus.text = getString(R.string.status_error) + ": ${state.message}"
                binding.tvConnectionStatus.setTextColor(0xFFFF4444.toInt())
                binding.btnConnect.text = getString(R.string.btn_connect)
                binding.btnConnect.isEnabled = devicesList.isNotEmpty()
            }
        }
    }

    private fun updatePlayerUi(state: RemoteMessage.PlayerState?) {
        if (state == null) {
            binding.tvTrackTitle.text = getString(R.string.no_track)
            binding.tvArtist.text = getString(R.string.unknown_artist)
            binding.tvAlbum.text = getString(R.string.unknown_album)
            binding.seekBarProgress.max = 0
            binding.seekBarProgress.progress = 0
            binding.tvCurrentPosition.text = getString(R.string.time_zero)
            binding.tvTotalDuration.text = getString(R.string.time_zero)
            binding.btnPlayPause.setBackgroundResource(R.drawable.ic_play)
            return
        }

        binding.tvTrackTitle.text = state.title.ifEmpty { getString(R.string.no_track) }
        binding.tvArtist.text = state.artist.ifEmpty { getString(R.string.unknown_artist) }
        binding.tvAlbum.text = state.album.ifEmpty { getString(R.string.unknown_album) }
        binding.seekBarProgress.max = state.durationMs.toInt()
        binding.tvTotalDuration.text = formatTime(state.durationMs)

        if (state.status == RemoteProtocol.STATUS_PLAYING) {
            binding.btnPlayPause.setBackgroundResource(R.drawable.ic_pause)
        } else {
            binding.btnPlayPause.setBackgroundResource(R.drawable.ic_play)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000L
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
