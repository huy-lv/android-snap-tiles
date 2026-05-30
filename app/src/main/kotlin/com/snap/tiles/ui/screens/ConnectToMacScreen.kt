package com.snap.tiles.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.snap.tiles.network.MacDevice
import com.snap.tiles.ui.viewmodel.ConnectToMacViewModel
import com.snap.tiles.ui.viewmodel.ConnectUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectToMacScreen(
    onBack: () -> Unit,
    vm: ConnectToMacViewModel = viewModel()
) {
    val state by vm.state.collectAsState()

    DisposableEffect(Unit) {
        vm.startScan()
        onDispose { vm.stopScan() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect to Mac") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state !is ConnectUiState.Connecting) {
                        IconButton(onClick = { vm.startScan() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                        }
                    }
                }
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.fillMaxSize().padding(padding),
            label = "connectState"
        ) { s ->
            when (s) {
                is ConnectUiState.Idle,
                is ConnectUiState.Scanning -> ScanningContent()

                is ConnectUiState.DevicesFound -> DevicesFoundContent(
                    devices = s.devices,
                    onConnect = { vm.connectTo(it) }
                )

                is ConnectUiState.NoDevicesFound -> NoDevicesContent(onRetry = { vm.startScan() })

                is ConnectUiState.Connecting -> ConnectingContent(device = s.device)

                is ConnectUiState.NeedsPairing -> NeedsPairingContent(
                    device = s.device,
                    instructions = s.instructions,
                    onRetry = { vm.connectTo(s.device) },
                    onBack = { vm.reset() }
                )

                is ConnectUiState.Success -> SuccessContent(
                    device = s.device,
                    message = s.message,
                    onDone = onBack
                )

                is ConnectUiState.Error -> ErrorContent(
                    message = s.message,
                    devices = s.devices,
                    onRetry = { vm.reset() },
                    onConnect = { vm.connectTo(it) }
                )
            }
        }
    }
}

@Composable
private fun ScanningContent() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(24.dp))
        Text("Scanning for Mac on LAN…", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Make sure adb-snap-daemon is running on your Mac.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun DevicesFoundContent(devices: List<MacDevice>, onConnect: (MacDevice) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(
            "FOUND ON NETWORK",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(devices, key = { it.name }) { device ->
                MacDeviceCard(device = device, onClick = { onConnect(device) })
            }
        }
    }
}

@Composable
private fun MacDeviceCard(device: MacDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                Icons.Default.Computer,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    "${device.host}:${device.port}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onClick,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Connect", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun NoDevicesContent(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Computer,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(20.dp))
        Text("No Mac Found", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Make sure adb-snap-daemon is running on your Mac and both devices are on the same Wi-Fi network.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 19.sp
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onRetry, shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Scan Again")
        }
    }
}

@Composable
private fun ConnectingContent(device: MacDevice) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(24.dp))
        Text("Connecting to ${device.name}…", fontWeight = FontWeight.SemiBold)
        Text(
            "Mac is running adb connect…",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NeedsPairingContent(
    device: MacDevice,
    instructions: String,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Default.Cable, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(22.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "One-time pairing required",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        instructions,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Text("Back")
            }
            Button(onClick = onRetry, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun SuccessContent(device: MacDevice, message: String, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(20.dp))
        Text("Connected!", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onDone, shape = RoundedCornerShape(12.dp)) {
            Text("Done")
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    devices: List<MacDevice>,
    onRetry: () -> Unit,
    onConnect: (MacDevice) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }

        if (devices.isNotEmpty()) {
            Text("Try another Mac:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            devices.forEach { device ->
                MacDeviceCard(device = device, onClick = { onConnect(device) })
            }
        } else {
            OutlinedButton(onClick = onRetry, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Try Again")
            }
        }
    }
}
