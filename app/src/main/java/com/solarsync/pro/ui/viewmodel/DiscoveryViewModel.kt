package com.solarsync.pro.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solarsync.pro.core.network.InverterProtocolDetector
import com.solarsync.pro.protocols.DeyeKnoxProtocolAdapter
import com.solarsync.pro.protocols.FronusSolaXAdapter
import com.solarsync.pro.protocols.InverterAdapter
import com.solarsync.pro.protocols.InverterWorkMode
import com.solarsync.pro.protocols.SolisCloudProtocolAdapter
import com.solarsync.pro.protocols.TelemetrySnapshot
import com.solarsync.pro.protocols.VoltronicInverexAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Orchestrates the whole flow: scan the subnet -> auto-detect brand ->
 * build the right adapter -> poll telemetry every 2s -> expose UI state.
 */
class DiscoveryViewModel : ViewModel() {

    sealed interface DiscoveryState {
        data object Idle : DiscoveryState
        data class Scanning(val progressHosts: Int) : DiscoveryState
        data class DeviceFound(val ip: String, val protocol: InverterProtocolDetector.DetectedProtocol) : DiscoveryState
        data object NotFound : DiscoveryState
    }

    data class UiState(
        val discovery: DiscoveryState = DiscoveryState.Idle,
        val telemetry: TelemetrySnapshot? = null,
        val isPolling: Boolean = false,
        val lastError: String? = null
    )

    private val detector = InverterProtocolDetector()
    private var activeAdapter: InverterAdapter? = null

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Requires a Logger Serial number for Deye/Knox devices (printed on the dongle). */
    fun startDiscovery(subnetPrefix: String, knownLoggerSerial: Long? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(discovery = DiscoveryState.Scanning(0), lastError = null)

            var found = false
            detector.scanSubnet(subnetPrefix) { result ->
                if (!found) {
                    found = true
                    _uiState.value = _uiState.value.copy(
                        discovery = DiscoveryState.DeviceFound(result.ipAddress, result.protocol)
                    )
                    activeAdapter = buildAdapter(result, knownLoggerSerial)
                    startPolling()
                }
            }

            if (!found) {
                _uiState.value = _uiState.value.copy(discovery = DiscoveryState.NotFound)
            }
        }
    }

    private fun buildAdapter(
        result: InverterProtocolDetector.ScanResult,
        knownLoggerSerial: Long?
    ): InverterAdapter = when (result.protocol) {
        InverterProtocolDetector.DetectedProtocol.SOLARMAN_V5 ->
            DeyeKnoxProtocolAdapter(
                ip = result.ipAddress,
                loggerSerial = knownLoggerSerial ?: 0L
            )
        InverterProtocolDetector.DetectedProtocol.PI30_ASCII ->
            VoltronicInverexAdapter(ip = result.ipAddress, port = result.openPort)
        InverterProtocolDetector.DetectedProtocol.MODBUS_TCP_SOLIS ->
            SolisCloudProtocolAdapter(ip = result.ipAddress, port = result.openPort)
        InverterProtocolDetector.DetectedProtocol.SUNSPEC_MODBUS ->
            FronusSolaXAdapter(ip = result.ipAddress, port = result.openPort)
        InverterProtocolDetector.DetectedProtocol.UNKNOWN ->
            throw IllegalStateException("Unrecognized inverter protocol at ${result.ipAddress}")
    }

    /** Polls telemetry every 2 seconds while the screen is active. */
    private fun startPolling() {
        _uiState.value = _uiState.value.copy(isPolling = true)
        viewModelScope.launch {
            while (isActive && _uiState.value.isPolling) {
                val adapter = activeAdapter
                if (adapter != null) {
                    try {
                        val snapshot = adapter.readTelemetry()
                        _uiState.value = _uiState.value.copy(telemetry = snapshot, lastError = null)
                    } catch (e: Exception) {
                        _uiState.value = _uiState.value.copy(lastError = e.message ?: "Telemetry read failed")
                    }
                }
                delay(2000)
            }
        }
    }

    fun stopPolling() {
        _uiState.value = _uiState.value.copy(isPolling = false)
    }

    /** Dispatches a control action (e.g. from the UI's mode selector) to the connected inverter. */
    fun dispatchWorkModeChange(mode: InverterWorkMode) {
        viewModelScope.launch {
            val adapter = activeAdapter ?: return@launch
            try {
                val success = adapter.setWorkMode(mode)
                if (!success) {
                    _uiState.value = _uiState.value.copy(lastError = "Inverter rejected mode change")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(lastError = e.message ?: "Mode change failed")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
