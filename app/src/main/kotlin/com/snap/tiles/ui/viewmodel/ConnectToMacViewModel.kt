package com.snap.tiles.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.snap.tiles.network.ConnectResult
import com.snap.tiles.network.ConnectToMacRepository
import com.snap.tiles.network.MacDevice
import com.snap.tiles.network.NsdDiscoveryManager
import com.snap.tiles.network.NsdEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class ConnectUiState {
    object Idle : ConnectUiState()
    object Scanning : ConnectUiState()
    data class DevicesFound(val devices: List<MacDevice>) : ConnectUiState()
    object NoDevicesFound : ConnectUiState()
    data class Connecting(val device: MacDevice) : ConnectUiState()
    data class Success(val device: MacDevice, val message: String) : ConnectUiState()
    data class NeedsPairing(val device: MacDevice, val instructions: String) : ConnectUiState()
    data class Error(val message: String, val devices: List<MacDevice> = emptyList()) : ConnectUiState()
}

class ConnectToMacViewModel(app: Application) : AndroidViewModel(app) {

    private val nsd = NsdDiscoveryManager(app)
    private val discovered = mutableMapOf<String, MacDevice>()

    private val _state = MutableStateFlow<ConnectUiState>(ConnectUiState.Idle)
    val state: StateFlow<ConnectUiState> = _state

    fun startScan() {
        discovered.clear()
        _state.value = ConnectUiState.Scanning

        viewModelScope.launch {
            nsd.events.collect { event ->
                when (event) {
                    is NsdEvent.Found -> {
                        discovered[event.device.name] = event.device
                        _state.value = ConnectUiState.DevicesFound(discovered.values.toList())
                    }
                    is NsdEvent.Lost -> {
                        discovered.remove(event.name)
                        _state.value = if (discovered.isEmpty()) ConnectUiState.Scanning
                        else ConnectUiState.DevicesFound(discovered.values.toList())
                    }
                }
            }
        }

        nsd.startDiscovery(viewModelScope)
    }

    fun stopScan() {
        nsd.stopDiscovery()
        if (_state.value is ConnectUiState.Scanning) {
            _state.value = ConnectUiState.NoDevicesFound
        }
    }

    fun connectTo(device: MacDevice) {
        val phoneIp = ConnectToMacRepository.getDeviceLanIp(getApplication())
        if (phoneIp == null) {
            _state.value = ConnectUiState.Error("Cannot read device LAN IP. Are you on Wi-Fi?")
            return
        }

        _state.value = ConnectUiState.Connecting(device)
        viewModelScope.launch {
            when (val result = ConnectToMacRepository.sendConnectRequest(device.host, device.port, phoneIp)) {
                is ConnectResult.Success -> _state.value = ConnectUiState.Success(device, result.message)
                is ConnectResult.NeedsPairing -> _state.value = ConnectUiState.NeedsPairing(device, result.instructions)
                is ConnectResult.Failure -> _state.value = ConnectUiState.Error(
                    result.message,
                    discovered.values.toList()
                )
            }
        }
    }

    fun reset() {
        _state.value = if (discovered.isEmpty()) ConnectUiState.Scanning
        else ConnectUiState.DevicesFound(discovered.values.toList())
    }

    override fun onCleared() {
        super.onCleared()
        nsd.stopDiscovery()
    }
}
