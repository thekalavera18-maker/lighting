package com.kingm.happylighting.ui

import android.bluetooth.BluetoothDevice
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kingm.happylighting.bluetooth.BleGattClient
import com.kingm.happylighting.bluetooth.BleScanner
import com.kingm.happylighting.data.HappyLightingController
import com.kingm.happylighting.data.SettingsRepository
import com.kingm.happylighting.model.AppStyleState
import com.kingm.happylighting.model.LightDevice
import com.kingm.happylighting.model.MainUiState
import com.kingm.happylighting.model.PickerMode
import com.kingm.happylighting.model.ReconnectPhase
import com.kingm.happylighting.model.SavedDreamPreset
import com.kingm.happylighting.model.SavedSwatch
import com.kingm.happylighting.protocol.CustomEffects
import com.kingm.happylighting.protocol.HappyLightingProtocol
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    private val scanner: BleScanner,
    private val gattClient: BleGattClient,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val tag = "HappyLightingUi"
    private val controller = HappyLightingController(gattClient)
    private val _uiState = MutableStateFlow(
        settingsRepository.loadDefaults().copy(
            availableNativeEffects = HappyLightingProtocol.nativeEffects,
            availableCustomEffects = HappyLightingProtocol.customEffects,
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var effectJob: Job? = null
    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var startupReconnectJob: Job? = null

    init {
        controller.setBaseColor(_uiState.value.baseColor)
        controller.setBrightness(_uiState.value.brightness)
    }

    fun setPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(permissionGranted = granted) }
        if (!granted || uiState.value.connected) {
            return
        }
        val savedAddress = uiState.value.savedDeviceAddress
        if (savedAddress != null) {
            startSavedDeviceReconnect(savedAddress)
        } else {
            scanDevices()
        }
    }

    fun scanDevices() {
        if (!_uiState.value.permissionGranted) {
            _uiState.update { it.copy(statusText = "Bluetooth permission is required.") }
            return
        }
        if (scanJob?.isActive == true) {
            return
        }
        startupReconnectJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    scanning = true,
                    autoConnectInProgress = false,
                    reconnectPhase = ReconnectPhase.IDLE,
                    statusText = "Scanning for nearby BLE devices...",
                )
            }
            runCatching {
                scanner.scanNamedDevices(
                    onDeviceFound = ::recordDiscoveredDevice,
                )
            }.onSuccess { devices ->
                val likely = devices.count { it.isLikelyMatch }
                Log.i(tag, "Scan found ${devices.size} devices, likely=$likely")
                _uiState.update {
                    it.copy(
                        devices = devices,
                        scanning = false,
                        reconnectPhase = ReconnectPhase.IDLE,
                        statusText = "Found ${devices.size} named BLE devices, $likely likely HappyLighting-compatible.",
                    )
                }
            }.onFailure { error ->
                Log.e(tag, "Scan failed", error)
                _uiState.update {
                    it.copy(
                        scanning = false,
                        reconnectPhase = ReconnectPhase.IDLE,
                        statusText = error.message ?: "BLE scan failed.",
                    )
                }
            }
        }.also { job ->
            job.invokeOnCompletion { if (scanJob === job) scanJob = null }
        }
    }

    fun connectDevice(address: String) {
        startupReconnectJob?.cancel()
        connectDeviceInternal(address = address, bluetoothDevice = null, launchReconnect = false)
    }

    fun disconnectDevice() {
        startupReconnectJob?.cancel()
        scanJob?.cancel()
        connectJob?.cancel()
        viewModelScope.launch {
            stopEffect(restoreStatic = false)
            runCatching { controller.disconnect() }
            _uiState.update {
                it.copy(
                    connected = false,
                    scanning = false,
                    reconnectPhase = ReconnectPhase.IDLE,
                    deviceName = "Not connected",
                    deviceAddress = null,
                    activeNativeEffect = null,
                    activeCustomEffect = null,
                    autoConnectInProgress = false,
                    statusText = "Disconnected.",
                )
            }
        }
    }

    fun setBaseColor(color: Triple<Int, Int, Int>) {
        stopEffect(restoreStatic = false)
        controller.setBaseColor(color)
        val applied = controller.outputColor
        settingsRepository.saveBaseColor(color)
        settingsRepository.saveAppliedColor(applied)
        val nextStyle = syncedAppStyle(_uiState.value.appStyle, applied)
        _uiState.update {
            it.copy(
                baseColor = color,
                appliedColor = applied,
                appStyle = nextStyle,
                activeNativeEffect = null,
                activeCustomEffect = null,
            )
        }
        settingsRepository.saveAppStyle(nextStyle)
        if (_uiState.value.connected) {
            viewModelScope.launch {
                delay(150L)
                applyCurrentColor("Applied color.")
            }
        }
    }

    fun setBrightness(value: Int) {
        controller.setBrightness(value)
        val applied = controller.outputColor
        settingsRepository.saveBrightness(value)
        settingsRepository.saveAppliedColor(applied)
        val nextStyle = syncedAppStyle(_uiState.value.appStyle, applied)
        _uiState.update { it.copy(brightness = value, appliedColor = applied, appStyle = nextStyle) }
        settingsRepository.saveAppStyle(nextStyle)
        if (_uiState.value.connected && _uiState.value.activeCustomEffect == null && _uiState.value.activeNativeEffect == null) {
            viewModelScope.launch {
                delay(200L)
                applyCurrentColor("Brightness updated.")
            }
        }
    }

    fun setPickerMode(mode: PickerMode) {
        settingsRepository.savePickerMode(mode)
        _uiState.update { it.copy(pickerMode = mode) }
    }

    fun setAppAccentColor(color: Triple<Int, Int, Int>) {
        updateAppStyle { it.copy(accentColor = color) }
    }

    fun setMatchLightColor(enabled: Boolean) {
        updateAppStyle {
            if (enabled) {
                it.copy(matchLightColor = true, accentColor = _uiState.value.appliedColor)
            } else {
                it.copy(matchLightColor = false)
            }
        }
    }

    fun setAppSaturation(value: Int) {
        updateAppStyle { it.copy(saturation = value.coerceIn(0, 200)) }
    }

    fun setAppContrast(value: Int) {
        updateAppStyle { it.copy(contrast = value.coerceIn(60, 160)) }
    }

    fun setAmoledMode(enabled: Boolean) {
        updateAppStyle { it.copy(amoledMode = enabled) }
    }

    fun saveCurrentSwatch() {
        val color = _uiState.value.baseColor
        val brightness = _uiState.value.brightness
        val current = _uiState.value.savedSwatches
        if (current.any { it.color == color && it.brightness == brightness }) {
            _uiState.update { it.copy(statusText = "That color and brightness are already saved.") }
            return
        }
        val updated = (listOf(SavedSwatch(System.currentTimeMillis(), color, brightness)) + current).take(16)
        settingsRepository.saveSwatches(updated)
        _uiState.update { it.copy(savedSwatches = updated, statusText = "Saved color to your library.") }
    }

    fun applySwatch(id: Long) {
        val swatch = _uiState.value.savedSwatches.firstOrNull { it.id == id } ?: return
        stopEffect(restoreStatic = false)
        controller.setBaseColor(swatch.color)
        controller.setBrightness(swatch.brightness)
        val applied = controller.outputColor
        settingsRepository.saveBaseColor(swatch.color)
        settingsRepository.saveBrightness(swatch.brightness)
        settingsRepository.saveAppliedColor(applied)
        val nextStyle = syncedAppStyle(_uiState.value.appStyle, applied)
        settingsRepository.saveAppStyle(nextStyle)
        _uiState.update {
            it.copy(
                baseColor = swatch.color,
                brightness = swatch.brightness,
                appliedColor = applied,
                appStyle = nextStyle,
                activeNativeEffect = null,
                activeCustomEffect = null,
                statusText = "Applied saved color.",
            )
        }
        if (_uiState.value.connected) {
            viewModelScope.launch {
                delay(150L)
                applyCurrentColor("Applied saved color.")
            }
        }
    }

    fun removeSwatch(id: Long) {
        val updated = _uiState.value.savedSwatches.filterNot { it.id == id }
        settingsRepository.saveSwatches(updated)
        _uiState.update { it.copy(savedSwatches = updated, statusText = "Removed saved color.") }
    }

    fun setSpeed(value: Int) {
        settingsRepository.saveSpeed(value)
        _uiState.update { it.copy(speed = value) }
        when {
            _uiState.value.activeNativeEffect != null -> applyNativeEffect(_uiState.value.activeNativeEffect!!)
            _uiState.value.activeCustomEffect != null -> startCustomEffect(_uiState.value.activeCustomEffect!!)
        }
    }

    fun turnOn() = performAction("Light powered on.") { controller.turnOn() }

    fun turnOff() = performAction("Light powered off.") { controller.turnOff() }

    fun applyNativeEffect(effectId: String) {
        stopEffect(restoreStatic = false)
        val speed = _uiState.value.speed
        performAction("Applied ${effectLabel(effectId)}.") {
            controller.setNativeEffect(effectId, speed)
            _uiState.update {
                it.copy(activeNativeEffect = effectId, activeCustomEffect = null)
            }
        }
    }

    fun saveDreamPreset(mode: Int, name: String) {
        val cleaned = name.trim().take(32)
        if (cleaned.isBlank()) {
            _uiState.update { it.copy(statusText = "Give this mode a name first.") }
            return
        }
        val preset = SavedDreamPreset(mode = mode.coerceIn(0, 255), name = cleaned)
        val updated = buildList {
            add(preset)
            addAll(_uiState.value.savedDreamPresets.filterNot { it.mode == preset.mode })
        }.take(32)
        settingsRepository.saveDreamPresets(updated)
        _uiState.update {
            it.copy(
                savedDreamPresets = updated,
                statusText = "Saved ${preset.name} as mode 0x%02X.".format(preset.mode),
            )
        }
    }

    fun removeDreamPreset(mode: Int) {
        val updated = _uiState.value.savedDreamPresets.filterNot { it.mode == mode }
        settingsRepository.saveDreamPresets(updated)
        _uiState.update { it.copy(savedDreamPresets = updated, statusText = "Removed saved mode.") }
    }

    fun applyDreamEffect(mode: Int, label: String) {
        stopEffect(restoreStatic = false)
        performAction("Applied $label.") {
            controller.setDreamEffect(mode)
            _uiState.update {
                it.copy(
                    activeNativeEffect = null,
                    activeCustomEffect = null,
                    statusText = "Applied $label.",
                )
            }
        }
    }

    fun startCustomEffect(effectId: String) {
        effectJob?.cancel()
        _uiState.update { it.copy(activeCustomEffect = effectId, activeNativeEffect = null, statusText = "Running ${effectLabel(effectId)}...") }
        effectJob = viewModelScope.launch {
            while (true) {
                if (!controller.isConnected) {
                    break
                }
                val state = _uiState.value
                val color = CustomEffects.colorFor(
                    effectId = effectId,
                    speed = state.speed,
                    baseColor = state.baseColor,
                    brightness = state.brightness,
                )
                val result = runCatching { controller.sendRawColor(color) }
                if (result.isSuccess) {
                    settingsRepository.saveAppliedColor(color)
                    _uiState.update { current -> current.copy(appliedColor = color) }
                } else {
                    val message = result.exceptionOrNull()?.message ?: "Custom effect failed."
                    _uiState.update { it.copy(statusText = message) }
                    break
                }
                delay(90L)
            }
        }
    }

    fun stopEffect(restoreStatic: Boolean = true) {
        effectJob?.cancel()
        effectJob = null
        val shouldRestore = restoreStatic && controller.isConnected
        _uiState.update { it.copy(activeNativeEffect = null, activeCustomEffect = null) }
        if (shouldRestore) {
            applyCurrentColor("Returned to static color.")
        }
    }

    fun refreshStatus() {
        if (!_uiState.value.connected) {
            return
        }
        viewModelScope.launch {
            runCatching { controller.requestStatus() }
                .onSuccess { status ->
                    if (status?.rgbColor != null) {
                        val applied = status.rgbColor
                        settingsRepository.saveAppliedColor(applied)
                        _uiState.update {
                            it.copy(
                                appliedColor = applied,
                                appStyle = syncedAppStyle(it.appStyle, applied),
                                statusText = buildString {
                                    append("Status synced")
                                    status.brightness?.let { brightness ->
                                        append(" • device brightness ")
                                        append(brightness)
                                    }
                                },
                            )
                        }
                        settingsRepository.saveAppStyle(syncedAppStyle(_uiState.value.appStyle, applied))
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(statusText = error.message ?: "Status request failed.") }
                }
        }
    }

    fun resyncConnection() {
        val state = _uiState.value
        if (state.connected) {
            refreshStatus()
            return
        }
        val savedAddress = state.savedDeviceAddress
        if (savedAddress != null) {
            startSavedDeviceReconnect(savedAddress)
        } else {
            scanDevices()
        }
    }

    private fun startSavedDeviceReconnect(address: String) {
        if (!_uiState.value.permissionGranted || _uiState.value.connected) {
            return
        }
        if (startupReconnectJob?.isActive == true || connectJob?.isActive == true) {
            return
        }
        scanJob?.cancel()
        startupReconnectJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    scanning = true,
                    autoConnectInProgress = true,
                    reconnectPhase = ReconnectPhase.TARGETED_SCAN,
                    statusText = "Looking for your saved Lumora controller...",
                )
            }
            runCatching {
                scanner.findSavedDevice(
                    address = address,
                    timeoutMillis = 2_000L,
                    onDeviceFound = ::recordDiscoveredDevice,
                )
            }.onSuccess { bluetoothDevice ->
                if (bluetoothDevice != null) {
                    connectDeviceInternal(
                        address = bluetoothDevice.address,
                        bluetoothDevice = bluetoothDevice,
                        launchReconnect = true,
                    )
                } else {
                    _uiState.update {
                        it.copy(
                            autoConnectInProgress = false,
                            reconnectPhase = ReconnectPhase.FALLBACK_SCAN,
                            statusText = "Saved controller not found yet. Scanning all nearby devices...",
                        )
                    }
                    scanDevices()
                }
            }.onFailure { error ->
                Log.e(tag, "Saved-device reconnect scan failed", error)
                _uiState.update {
                    it.copy(
                        scanning = false,
                        autoConnectInProgress = false,
                        reconnectPhase = ReconnectPhase.IDLE,
                        statusText = error.message ?: "BLE scan failed.",
                    )
                }
            }
        }.also { job ->
            job.invokeOnCompletion { if (startupReconnectJob === job) startupReconnectJob = null }
        }
    }

    private fun connectDeviceInternal(
        address: String,
        bluetoothDevice: BluetoothDevice?,
        launchReconnect: Boolean,
    ) {
        if (connectJob?.isActive == true) {
            return
        }
        scanJob?.cancel()
        val knownDevice = _uiState.value.devices.firstOrNull { it.address == address }
        Log.i(tag, "Connect requested for ${knownDevice?.name ?: bluetoothDevice?.name ?: "unknown"} [$address]")
        connectJob = viewModelScope.launch {
            stopEffect(restoreStatic = false)
            _uiState.update {
                it.copy(
                    scanning = false,
                    autoConnectInProgress = true,
                    reconnectPhase = ReconnectPhase.CONNECTING,
                    statusText = "Connecting to ${knownDevice?.name ?: bluetoothDevice?.name ?: address} [$address]...",
                )
            }
            runCatching {
                if (bluetoothDevice != null) {
                    controller.connect(bluetoothDevice) {
                        handleUnexpectedDisconnect()
                    }
                } else {
                    controller.connect(address) {
                        handleUnexpectedDisconnect()
                    }
                }
            }.onSuccess {
                val connectedName = knownDevice?.name ?: bluetoothDevice?.name ?: address
                settingsRepository.saveDevice(address, connectedName)
                _uiState.update {
                    it.copy(
                        connected = true,
                        scanning = false,
                        deviceName = connectedName,
                        deviceAddress = address,
                        savedDeviceAddress = address,
                        savedDeviceName = connectedName,
                        autoConnectInProgress = false,
                        reconnectPhase = ReconnectPhase.IDLE,
                        statusText = if (launchReconnect) {
                            "Connected to $connectedName."
                        } else {
                            "Connected to $connectedName."
                        },
                    )
                }
                applyCurrentColor(successText = "Restored saved color.")
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        autoConnectInProgress = false,
                        reconnectPhase = ReconnectPhase.IDLE,
                        statusText = error.message ?: "Connection failed.",
                    )
                }
            }
        }.also { job ->
            job.invokeOnCompletion { if (connectJob === job) connectJob = null }
        }
    }

    private fun handleUnexpectedDisconnect() {
        _uiState.update {
            it.copy(
                connected = false,
                scanning = false,
                reconnectPhase = ReconnectPhase.IDLE,
                deviceName = "Not connected",
                deviceAddress = null,
                activeCustomEffect = null,
                activeNativeEffect = null,
                autoConnectInProgress = false,
                statusText = "Device disconnected unexpectedly.",
            )
        }
    }

    private fun applyCurrentColor(successText: String) {
        val target = controller.outputColor
        settingsRepository.saveAppliedColor(target)
        performAction(successText) {
            controller.applyCurrentColor()
            _uiState.update { it.copy(appliedColor = target, activeNativeEffect = null, activeCustomEffect = null) }
        }
    }

    private fun effectLabel(effectId: String): String =
        HappyLightingProtocol.nativeEffects.firstOrNull { it.id == effectId }?.label
            ?: HappyLightingProtocol.customEffects.firstOrNull { it.id == effectId }?.label
            ?: effectId

    private fun performAction(successText: String, block: suspend () -> Unit) {
        if (!_uiState.value.connected) {
            _uiState.update { it.copy(statusText = "No light is connected.") }
            return
        }
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { _uiState.update { it.copy(statusText = successText) } }
                .onFailure { error ->
                    _uiState.update { it.copy(statusText = error.message ?: "Action failed.") }
                }
        }
    }

    private fun recordDiscoveredDevice(device: LightDevice) {
        _uiState.update { state ->
            val merged = buildList {
                addAll(state.devices.filterNot { it.address == device.address })
                add(device)
            }
            state.copy(devices = HappyLightingProtocol.rankDevices(merged))
        }
    }

    private fun updateAppStyle(transform: (AppStyleState) -> AppStyleState) {
        val updated = transform(_uiState.value.appStyle)
        settingsRepository.saveAppStyle(updated)
        _uiState.update { it.copy(appStyle = updated) }
    }

    private fun syncedAppStyle(style: AppStyleState, appliedColor: Triple<Int, Int, Int>): AppStyleState =
        if (style.matchLightColor) style.copy(accentColor = appliedColor) else style

    override fun onCleared() {
        effectJob?.cancel()
        scanJob?.cancel()
        startupReconnectJob?.cancel()
        connectJob?.cancel()
        viewModelScope.launch {
            runCatching { controller.disconnect() }
        }
        super.onCleared()
    }
}
