package com.kingm.happylighting

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color.HSVToColor
import android.graphics.Color.colorToHSV
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingm.happylighting.bluetooth.BleGattClient
import com.kingm.happylighting.bluetooth.BleScanner
import com.kingm.happylighting.data.SettingsRepository
import com.kingm.happylighting.model.AppStyleState
import com.kingm.happylighting.model.LightDevice
import com.kingm.happylighting.model.MainUiState
import com.kingm.happylighting.model.PickerMode
import com.kingm.happylighting.model.SavedSwatch
import com.kingm.happylighting.ui.MainViewModel
import com.kingm.happylighting.ui.theme.HappyLightingTheme
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val viewModel: MainViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(
                        scanner = BleScanner(context),
                        gattClient = BleGattClient(context),
                        settingsRepository = SettingsRepository(context),
                    ) as T
                },
            )
            val uiState by viewModel.uiState.collectAsState()
            HappyLightingTheme(appStyle = uiState.appStyle) {
                val permissions = requiredPermissions()
                val launcher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) { granted ->
                    viewModel.setPermissionGranted(granted.values.all { it })
                }

                LaunchedEffect(Unit) {
                    val granted = permissions.all {
                        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                    }
                    viewModel.setPermissionGranted(granted)
                    if (!granted) {
                        launcher.launch(permissions.toTypedArray())
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    LumoraScreen(
                        uiState = uiState,
                        onRequestPermissions = { launcher.launch(permissions.toTypedArray()) },
                        onScan = { viewModel.scanDevices() },
                        onConnect = viewModel::connectDevice,
                        onDisconnect = viewModel::disconnectDevice,
                        onTurnOn = viewModel::turnOn,
                        onTurnOff = viewModel::turnOff,
                        onRefreshStatus = viewModel::resyncConnection,
                        onColorChange = viewModel::setBaseColor,
                        onBrightnessChange = viewModel::setBrightness,
                        onPickerModeChange = viewModel::setPickerMode,
                        onSaveSwatch = viewModel::saveCurrentSwatch,
                        onApplySwatch = viewModel::applySwatch,
                        onRemoveSwatch = viewModel::removeSwatch,
                        onDreamEffect = viewModel::applyDreamEffect,
                        onAppAccentColorChange = viewModel::setAppAccentColor,
                        onAppSaturationChange = viewModel::setAppSaturation,
                        onAppContrastChange = viewModel::setAppContrast,
                        onMatchLightColorChange = viewModel::setMatchLightColor,
                        onAmoledModeChange = viewModel::setAmoledMode,
                    )
                }
            }
        }
    }
}

private fun requiredPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@Composable
private fun LumoraScreen(
    uiState: MainUiState,
    onRequestPermissions: () -> Unit,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit,
    onRefreshStatus: () -> Unit,
    onColorChange: (Triple<Int, Int, Int>) -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onPickerModeChange: (PickerMode) -> Unit,
    onSaveSwatch: () -> Unit,
    onApplySwatch: (Long) -> Unit,
    onRemoveSwatch: (Long) -> Unit,
    onDreamEffect: (Int, String) -> Unit,
    onAppAccentColorChange: (Triple<Int, Int, Int>) -> Unit,
    onAppSaturationChange: (Int) -> Unit,
    onAppContrastChange: (Int) -> Unit,
    onMatchLightColorChange: (Boolean) -> Unit,
    onAmoledModeChange: (Boolean) -> Unit,
) {
    var selectedAddress by rememberSaveable { mutableStateOf(uiState.savedDeviceAddress ?: uiState.deviceAddress) }
    var devicesExpanded by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var wheelInteracting by remember { mutableStateOf(false) }
    val preferredAddress = remember(uiState.devices) {
        (uiState.devices.firstOrNull { it.isLikelyMatch } ?: uiState.devices.firstOrNull())?.address
    }
    val wheelScrollBlocker = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (wheelInteracting) available else Offset.Zero
        }
    }

    LaunchedEffect(uiState.savedDeviceAddress, uiState.deviceAddress, uiState.devices) {
        val selectedStillExists = selectedAddress != null && uiState.devices.any { it.address == selectedAddress }
        if (!selectedStillExists) {
            selectedAddress = uiState.savedDeviceAddress
                ?.takeIf { saved -> uiState.devices.any { it.address == saved } }
                ?: uiState.deviceAddress
                ?: preferredAddress
        }
    }

    if (!uiState.permissionGranted) {
        PermissionGate(onRequestPermissions)
        return
    }

    BackHandler(enabled = settingsOpen) {
        settingsOpen = false
    }

    AnimatedContent(
        targetState = settingsOpen,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(animationSpec = tween(260)) { it / 7 } + fadeIn(animationSpec = tween(220))).togetherWith(
                    slideOutHorizontally(animationSpec = tween(220)) { -it / 10 } + fadeOut(animationSpec = tween(180)),
                )
            } else {
                (slideInHorizontally(animationSpec = tween(240)) { -it / 10 } + fadeIn(animationSpec = tween(220))).togetherWith(
                    slideOutHorizontally(animationSpec = tween(240)) { it / 7 } + fadeOut(animationSpec = tween(180)),
                )
            }
        },
        label = "lumora-screen",
    ) { showSettings ->
        if (showSettings) {
            SettingsScreen(
                uiState = uiState,
                selectedAddress = selectedAddress,
                fallbackAddress = preferredAddress,
                devicesExpanded = devicesExpanded,
                onClose = { settingsOpen = false },
                onExpandToggle = { devicesExpanded = !devicesExpanded },
                onSelectDevice = { selectedAddress = it },
                onScan = onScan,
                onConnect = { (selectedAddress ?: preferredAddress)?.let(onConnect) },
                onDisconnect = onDisconnect,
                onRefreshStatus = onRefreshStatus,
                onAppAccentColorChange = onAppAccentColorChange,
                onAppSaturationChange = onAppSaturationChange,
                onAppContrastChange = onAppContrastChange,
                onMatchLightColorChange = onMatchLightColorChange,
                onAmoledModeChange = onAmoledModeChange,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .nestedScroll(wheelScrollBlocker)
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp,
                        bottom = 16.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                userScrollEnabled = !wheelInteracting,
            ) {
                item {
                    LumoraHeader(
                        uiState = uiState,
                        onRefreshStatus = onRefreshStatus,
                    )
                }
                item {
                    ColorSection(
                        uiState = uiState,
                        onColorChange = onColorChange,
                        onBrightnessChange = onBrightnessChange,
                        onTurnOn = onTurnOn,
                        onTurnOff = onTurnOff,
                        onPickerModeChange = onPickerModeChange,
                        onSaveSwatch = onSaveSwatch,
                        onApplySwatch = onApplySwatch,
                        onRemoveSwatch = onRemoveSwatch,
                        onWheelInteractionChange = { wheelInteracting = it },
                    )
                }
                item {
                    DreamEffectsSection(
                        uiState = uiState,
                        onDreamEffect = onDreamEffect,
                    )
                }
                item {
                    FooterStatus(uiState.statusText)
                }
                item {
                    SettingsEntryButton(
                        onClick = { settingsOpen = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionGate(onRequestPermissions: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.padding(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Lumora needs Bluetooth and location access to find your controller.",
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Button(onClick = onRequestPermissions) {
                    Text("Grant Access")
                }
            }
        }
    }
}

@Composable
private fun LumoraHeader(
    uiState: MainUiState,
    onRefreshStatus: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Lumora", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                        Button(
                            onClick = onRefreshStatus,
                            enabled = uiState.connected || (uiState.savedDeviceAddress != null && !uiState.autoConnectInProgress),
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text("Resync")
                        }
                    }
                    Text(
                        text = when {
                            uiState.connected -> uiState.deviceName
                            uiState.scanning -> "Searching for your controller"
                            else -> uiState.savedDeviceName ?: "Ready to connect"
                        },
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                StatusPill(
                    text = when {
                        uiState.autoConnectInProgress -> "Auto-Connecting"
                        uiState.connected -> "Connected"
                        uiState.scanning -> "Scanning"
                        else -> "Idle"
                    },
                    color = when {
                        uiState.autoConnectInProgress -> Color(0xFFFFC766)
                        uiState.connected -> Color(0xFF4DE3A0)
                        uiState.scanning -> Color(0xFFFF8E5E)
                        else -> Color(0xFF506173)
                    },
                )
            }
            AnimatedStatusBar(uiState)
        }
    }
}

@Composable
private fun AnimatedStatusBar(uiState: MainUiState) {
    val level by animateFloatAsState(
        targetValue = when {
            uiState.connected -> 1f
            uiState.autoConnectInProgress -> 0.72f
            uiState.scanning -> 0.45f
            else -> 0.15f
        },
        animationSpec = spring(stiffness = 220f, dampingRatio = 0.85f),
        label = "header-level",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(level)
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFFFF8E5E), Color(0xFF57E0C3), Color(0xFF6B8CFF)),
                    ),
                ),
        )
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DevicesDropdownSection(
    expanded: Boolean,
    uiState: MainUiState,
    selectedAddress: String?,
    fallbackAddress: String?,
    onExpandToggle: () -> Unit,
    onSelect: (String) -> Unit,
    onScan: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefreshStatus: () -> Unit,
) {
    SurfaceSection(title = "DEVICES") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
                .combinedClickable(onClick = onExpandToggle)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Devices", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(
                    text = when {
                        uiState.connected -> uiState.deviceName
                        selectedAddress != null -> uiState.devices.firstOrNull { it.address == selectedAddress }?.name ?: "Saved controller selected"
                        else -> "Manage scanning and connection"
                    },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            StatusPill(
                text = if (expanded) "Hide" else "Open",
                color = if (expanded) Color(0xFFFF8E5E) else Color(0xFF6B8CFF),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(160)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (uiState.devices.isEmpty()) {
                    Text("No scanned devices yet.", color = Color(0xFF7F92A6))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        uiState.devices.forEach { device ->
                            DeviceRow(
                                device = device,
                                selected = device.address == selectedAddress,
                                onClick = { onSelect(device.address) },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onScan, modifier = Modifier.weight(1f)) { Text("Scan") }
                    Button(
                        onClick = onConnect,
                        enabled = (selectedAddress != null || fallbackAddress != null) && !uiState.connected && !uiState.autoConnectInProgress,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (uiState.autoConnectInProgress) "Connecting..." else "Connect") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onDisconnect, enabled = uiState.connected, modifier = Modifier.weight(1f)) {
                        Text("Disconnect")
                    }
                    OutlinedButton(onClick = onRefreshStatus, enabled = uiState.connected, modifier = Modifier.weight(1f)) {
                        Text("Sync")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    uiState: MainUiState,
    selectedAddress: String?,
    fallbackAddress: String?,
    devicesExpanded: Boolean,
    onClose: () -> Unit,
    onExpandToggle: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onScan: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefreshStatus: () -> Unit,
    onAppAccentColorChange: (Triple<Int, Int, Int>) -> Unit,
    onAppSaturationChange: (Int) -> Unit,
    onAppContrastChange: (Int) -> Unit,
    onMatchLightColorChange: (Boolean) -> Unit,
    onAmoledModeChange: (Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp,
                bottom = 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsHeader(onClose = onClose)
        }
        item {
            AppStyleSection(
                appStyle = uiState.appStyle,
                onAccentColorChange = onAppAccentColorChange,
                onSaturationChange = onAppSaturationChange,
                onContrastChange = onAppContrastChange,
                onMatchLightColorChange = onMatchLightColorChange,
                onAmoledModeChange = onAmoledModeChange,
            )
        }
        item {
            DevicesDropdownSection(
                expanded = devicesExpanded,
                uiState = uiState,
                selectedAddress = selectedAddress,
                fallbackAddress = fallbackAddress,
                onExpandToggle = onExpandToggle,
                onSelect = onSelectDevice,
                onScan = onScan,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onRefreshStatus = onRefreshStatus,
            )
        }
    }
}

@Composable
private fun SettingsHeader(onClose: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Settings", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Manage app style and device connection.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(onClick = onClose) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun SettingsEntryButton(
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
    ) {
        val scale by animateFloatAsState(
            targetValue = 1f,
            animationSpec = spring(stiffness = 280f, dampingRatio = 0.82f),
            label = "settings-entry-scale",
        )
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            Text("Settings")
        }
    }
}

@Composable
private fun SurfaceSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                content()
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRow(
    device: LightDevice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (device.isLikelyMatch) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(device.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(device.address, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = when {
                selected -> "Selected"
                device.isLikelyMatch -> "Likely"
                else -> "BLE"
            },
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ColorSection(
    uiState: MainUiState,
    onColorChange: (Triple<Int, Int, Int>) -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit,
    onPickerModeChange: (PickerMode) -> Unit,
    onSaveSwatch: () -> Unit,
    onApplySwatch: (Long) -> Unit,
    onRemoveSwatch: (Long) -> Unit,
    onWheelInteractionChange: (Boolean) -> Unit,
) {
    val animatedPreview by androidx.compose.animation.animateColorAsState(
        targetValue = uiState.appliedColor.toColor(),
        animationSpec = spring(stiffness = 260f, dampingRatio = 0.85f),
        label = "preview-color",
    )
    val previewTextColor = if (animatedPreview.luminance() > 0.55f) Color.Black else Color(0xFFDDE3EC)

    SurfaceSection(title = "COLOR") {
        PickerToggle(uiState.pickerMode, onPickerModeChange)
        Crossfade(targetState = uiState.pickerMode, label = "picker-mode") { mode ->
            when (mode) {
                PickerMode.WHEEL -> ColorWheelPicker(
                    color = uiState.baseColor,
                    onColorChange = onColorChange,
                    onInteractionChange = onWheelInteractionChange,
                )
                PickerMode.RGB -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ColorSlider("Red", uiState.baseColor.first, Color(0xFFFF6B6B)) {
                        onColorChange(Triple(it, uiState.baseColor.second, uiState.baseColor.third))
                    }
                    ColorSlider("Green", uiState.baseColor.second, Color(0xFF56D364)) {
                        onColorChange(Triple(uiState.baseColor.first, it, uiState.baseColor.third))
                    }
                    ColorSlider("Blue", uiState.baseColor.third, Color(0xFF5DA9FF)) {
                        onColorChange(Triple(uiState.baseColor.first, uiState.baseColor.second, it))
                    }
                }
            }
        }
        ColorPreview(color = animatedPreview, textColor = previewTextColor)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Saved Colors", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onSaveSwatch) { Text("Save") }
        }
        AnimatedVisibility(
            visible = uiState.savedSwatches.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            SwatchLibrary(
                swatches = uiState.savedSwatches,
                current = uiState.baseColor,
                onApply = onApplySwatch,
                onRemove = onRemoveSwatch,
            )
        }
        Text("BRIGHTNESS", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        SliderRow(value = uiState.brightness, range = 1..100, onValueChange = onBrightnessChange)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onTurnOn, enabled = uiState.connected, modifier = Modifier.weight(1f)) { Text("Power On") }
            Button(onClick = onTurnOff, enabled = uiState.connected, modifier = Modifier.weight(1f)) { Text("Power Off") }
        }
    }
}

private data class DreamPreset(
    val mode: Int,
    val label: String,
)

private val verifiedDreamPresets = listOf(
    DreamPreset(0x01, "Rainbow Chase"),
    DreamPreset(0x03, "Blue/Green/Yellow Fade"),
    DreamPreset(0x06, "Red Chase"),
    DreamPreset(0x07, "Red Flicker"),
    DreamPreset(0x08, "Red Flicker II"),
    DreamPreset(0x09, "Pink/Purple/Blue Chase"),
    DreamPreset(0x0A, "Green Chase"),
    DreamPreset(0x0B, "Blue Flicker Chase"),
    DreamPreset(0x0C, "Multicolor Chase"),
    DreamPreset(0x0D, "Red Chase Reverse"),
    DreamPreset(0x0E, "Pink/White/Green Chase"),
    DreamPreset(0x0F, "Blue Chase"),
    DreamPreset(0x10, "Pink Chase"),
    DreamPreset(0x11, "Yellow/Blue/White Chase"),
    DreamPreset(0x12, "Green/White/Pink Bounce"),
    DreamPreset(0x13, "Red/Cyan/Blue Chase"),
    DreamPreset(0x14, "Cyan/Green/Yellow/Pink"),
    DreamPreset(0x15, "Multicolor Chase II"),
    DreamPreset(0x16, "Green/White Chase"),
    DreamPreset(0x17, "Green Comet"),
    DreamPreset(0x18, "White/Pink/Green Chase"),
    DreamPreset(0x19, "Blue/White Reverse"),
    DreamPreset(0x1A, "White/Green Flicker"),
    DreamPreset(0x1B, "White Pulse + Color Chase"),
    DreamPreset(0x1C, "Pink/Blue Bounce"),
    DreamPreset(0x34, "Pink/White Flicker"),
    DreamPreset(0x3A, "Dynamic Multicolor"),
)

@Composable
private fun DreamEffectsSection(
    uiState: MainUiState,
    onDreamEffect: (Int, String) -> Unit,
) {
    SurfaceSection(title = "UNDERGLOW FX") {
        Text(
            "Verified DREAM controller modes from our BLE testing.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            style = MaterialTheme.typography.bodySmall,
        )
        verifiedDreamPresets.chunked(2).forEach { rowPresets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowPresets.forEach { preset ->
                    Button(
                        onClick = { onDreamEffect(preset.mode, preset.label) },
                        enabled = uiState.connected,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(preset.label)
                    }
                }
                if (rowPresets.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PickerToggle(
    pickerMode: PickerMode,
    onPickerModeChange: (PickerMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PickerToggleButton(
            text = "Wheel",
            selected = pickerMode == PickerMode.WHEEL,
            onClick = { onPickerModeChange(PickerMode.WHEEL) },
        )
        PickerToggleButton(
            text = "RGB",
            selected = pickerMode == PickerMode.RGB,
            onClick = { onPickerModeChange(PickerMode.RGB) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickerToggleButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background by androidx.compose.animation.animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "picker-toggle-bg",
    )
    val foreground by androidx.compose.animation.animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
        label = "picker-toggle-fg",
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = foreground, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ColorWheelPicker(
    color: Triple<Int, Int, Int>,
    onColorChange: (Triple<Int, Int, Int>) -> Unit,
    onInteractionChange: (Boolean) -> Unit,
) {
    val hsv = remember(color) { color.toHsv() }
    val selectorColor = Color.hsv(hsv[0], hsv[1], 1f)
    val ringOutlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(264.dp)
                .background(Color.Transparent),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(248.dp)
                    .blur(28.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(selectorColor.copy(alpha = 0.18f), Color.Transparent),
                        ),
                    ),
            )
            Canvas(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(248.dp)
                    .pointerInput(Unit) {
                        detectWheelGestures(
                            onInteractionChange = onInteractionChange,
                            onColorSelected = { selected -> onColorChange(selected) },
                        )
                    },
            ) {
                val radius = size.minDimension / 2f - 12.dp.toPx()
                val center = center
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Red,
                            Color.Yellow,
                            Color.Green,
                            Color.Cyan,
                            Color.Blue,
                            Color.Magenta,
                            Color.Red,
                        ),
                        center = center,
                    ),
                    radius = radius,
                    center = center,
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
                drawCircle(
                    color = ringOutlineColor,
                    radius = radius + 2.dp.toPx(),
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )

                val angle = Math.toRadians(hsv[0].toDouble())
                val selectorRadius = hsv[1] * radius
                val selectorCenter = Offset(
                    x = center.x + cos(angle).toFloat() * selectorRadius,
                    y = center.y + sin(angle).toFloat() * selectorRadius,
                )
                drawCircle(color = selectorColor.copy(alpha = 0.6f), radius = 14.dp.toPx(), center = selectorCenter)
                drawCircle(color = Color.Black.copy(alpha = 0.38f), radius = 11.dp.toPx(), center = selectorCenter)
                drawCircle(color = Color.White, radius = 9.dp.toPx(), center = selectorCenter)
                drawCircle(color = selectorColor, radius = 7.dp.toPx(), center = selectorCenter)
            }
        }
    }
}

@Composable
private fun AppStyleSection(
    appStyle: AppStyleState,
    onAccentColorChange: (Triple<Int, Int, Int>) -> Unit,
    onSaturationChange: (Int) -> Unit,
    onContrastChange: (Int) -> Unit,
    onMatchLightColorChange: (Boolean) -> Unit,
    onAmoledModeChange: (Boolean) -> Unit,
) {
    val accentPreview = appStyle.accentColor.toColor()
    SurfaceSection(title = "APP STYLE") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("AMOLED Mode", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(
                    "Use deeper blacks for the app surfaces.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = appStyle.amoledMode,
                onCheckedChange = onAmoledModeChange,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Match Light Color", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(
                    "Use the current light output as the app accent.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = appStyle.matchLightColor,
                onCheckedChange = onMatchLightColorChange,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accentPreview),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Accent #%02X%02X%02X".format(
                    appStyle.accentColor.first,
                    appStyle.accentColor.second,
                    appStyle.accentColor.third,
                ),
                color = if (accentPreview.luminance() > 0.55f) Color.Black else Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        }
        ColorSlider("Accent Red", appStyle.accentColor.first, Color(0xFFFF6B6B), enabled = !appStyle.matchLightColor) {
            onAccentColorChange(Triple(it, appStyle.accentColor.second, appStyle.accentColor.third))
        }
        ColorSlider("Accent Green", appStyle.accentColor.second, Color(0xFF56D364), enabled = !appStyle.matchLightColor) {
            onAccentColorChange(Triple(appStyle.accentColor.first, it, appStyle.accentColor.third))
        }
        ColorSlider("Accent Blue", appStyle.accentColor.third, Color(0xFF5DA9FF), enabled = !appStyle.matchLightColor) {
            onAccentColorChange(Triple(appStyle.accentColor.first, appStyle.accentColor.second, it))
        }
        Text("SATURATION", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        SliderRow(value = appStyle.saturation, range = 0..200, onValueChange = onSaturationChange)
        Text("CONTRAST", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        SliderRow(value = appStyle.contrast, range = 60..160, onValueChange = onContrastChange)
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectWheelGestures(
    onInteractionChange: (Boolean) -> Unit,
    onColorSelected: (Triple<Int, Int, Int>) -> Unit,
) {
    while (true) {
        awaitPointerEventScope {
            val pointerSize = this@detectWheelGestures.size.toSize()
            var change = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: return@awaitPointerEventScope
            onInteractionChange(true)
            onColorSelected(offsetToColor(size = pointerSize, offset = change.position))
            while (change.pressed) {
                val event = awaitPointerEvent()
                change = event.changes.firstOrNull { it.id == change.id }
                    ?: event.changes.firstOrNull()
                    ?: break
                onColorSelected(offsetToColor(size = pointerSize, offset = change.position))
            }
            onInteractionChange(false)
        }
    }
}

private fun swatchPreviewColor(swatch: SavedSwatch): Color {
    val factor = swatch.brightness.coerceIn(1, 100) / 100f
    return Color(
        red = (swatch.color.first / 255f) * factor,
        green = (swatch.color.second / 255f) * factor,
        blue = (swatch.color.third / 255f) * factor,
    )
}

private fun offsetToColor(size: Size, offset: Offset): Triple<Int, Int, Int> {
    val radius = min(size.width, size.height) / 2f - 12f
    val center = Offset(size.width / 2f, size.height / 2f)
    val dx = offset.x - center.x
    val dy = offset.y - center.y
    val distance = hypot(dx, dy).coerceAtMost(radius)
    val hue = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360.0) % 360.0).toFloat()
    val saturation = (distance / radius).coerceIn(0f, 1f)
    val colorInt = HSVToColor(floatArrayOf(hue, saturation, 1f))
    return Triple(
        android.graphics.Color.red(colorInt),
        android.graphics.Color.green(colorInt),
        android.graphics.Color.blue(colorInt),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwatchLibrary(
    swatches: List<SavedSwatch>,
    current: Triple<Int, Int, Int>,
    onApply: (Long) -> Unit,
    onRemove: (Long) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(swatches, key = { it.id }) { swatch ->
            val selected = swatch.color == current
            Box(
                modifier = Modifier.animateItem(),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(swatchPreviewColor(swatch))
                        .combinedClickable(
                            onClick = { onApply(swatch.id) },
                            onLongClick = { onRemove(swatch.id) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.9f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorPreview(color: Color, textColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "#%02X%02X%02X".format(
                (color.red * 255).toInt(),
                (color.green * 255).toInt(),
                (color.blue * 255).toInt(),
            ),
            color = textColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ColorSlider(
    label: String,
    value: Int,
    color: Color,
    enabled: Boolean = true,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = if (enabled) color else color.copy(alpha = 0.45f), fontWeight = FontWeight.SemiBold)
            Text(value.toString(), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..255f,
            enabled = enabled,
        )
    }
}

@Composable
private fun SliderRow(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(value.toString(), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
private fun FooterStatus(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
        AnimatedContent(targetState = text, label = "footer-status") { value ->
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

private fun Triple<Int, Int, Int>.toColor(): Color = Color(first, second, third)

private fun Triple<Int, Int, Int>.toHsv(): FloatArray {
    val hsv = FloatArray(3)
    colorToHSV(android.graphics.Color.rgb(first, second, third), hsv)
    return hsv
}
