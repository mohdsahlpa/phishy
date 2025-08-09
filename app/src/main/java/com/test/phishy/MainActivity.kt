package com.test.phishy

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.test.phishy.ui.theme.PhishyTheme

class MainActivity : ComponentActivity() {

    private var vpnState by mutableStateOf(VpnState.DISCONNECTED)
    // ✅ FIX: Explicitly define the state type as Screen
    private var currentScreen by mutableStateOf<Screen>(Screen.Home)
    private val blockedDomains = mutableStateListOf<String>()

    // --- Broadcast Receivers ---
    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MyVpnService.BROADCAST_VPN_STATE) {
                val isConnected = intent.getBooleanExtra(MyVpnService.EXTRA_VPN_STATE, false)
                if (vpnState != VpnState.STOPPING) {
                    vpnState = if (isConnected) VpnState.CONNECTED else VpnState.DISCONNECTED
                } else if (!isConnected) {
                    vpnState = VpnState.DISCONNECTED
                }
            }
        }
    }

    private val blockedDomainReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // ✅ FIX: Correctly reference the constant from MyVpnService
            if (intent?.action == MyVpnService.BROADCAST_BLOCKED_DOMAIN) {
                intent.getStringExtra(MyVpnService.EXTRA_BLOCKED_DOMAIN)?.let {
                    blockedDomains.add(0, it) // Add to the top of the list
                }
            }
        }
    }

    // --- Activity Result Launchers ---
    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startVpnService()
            } else {
                Log.w("MainActivity", "VPN permission denied.")
                vpnState = VpnState.DISCONNECTED
            }
        }

    // --- Lifecycle Methods ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhishyApp(
                vpnState = vpnState,
                currentScreen = currentScreen,
                blockedDomains = blockedDomains,
                onScreenChange = { screen -> currentScreen = screen },
                onToggleClick = {
                    when (vpnState) {
                        VpnState.CONNECTED -> stopVpnService()
                        VpnState.DISCONNECTED -> requestVpnPermission()
                        else -> {} // Ignore clicks in transient states
                    }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Register VPN state receiver
        ContextCompat.registerReceiver(
            this, vpnStateReceiver, IntentFilter(MyVpnService.BROADCAST_VPN_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Register blocked domain receiver
        // ✅ FIX: Correctly reference the constant from MyVpnService
        ContextCompat.registerReceiver(
            this, blockedDomainReceiver, IntentFilter(MyVpnService.BROADCAST_BLOCKED_DOMAIN),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(vpnStateReceiver)
        unregisterReceiver(blockedDomainReceiver)
    }

    // --- VPN Control Methods ---
    private fun requestVpnPermission() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        vpnState = VpnState.CONNECTING
        startService(Intent(this, MyVpnService::class.java).apply { action = MyVpnService.ACTION_CONNECT })
    }

    private fun stopVpnService() {
        vpnState = VpnState.STOPPING
        startService(Intent(this, MyVpnService::class.java).apply { action = MyVpnService.ACTION_DISCONNECT })
    }
}

// --- State & Navigation Definitions ---
enum class VpnState { CONNECTED, CONNECTING, DISCONNECTED, STOPPING }

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Default.Home)
    object Analytics : Screen("analytics", "Analytics", Icons.Default.Analytics)
}

// --- Composable UI ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhishyApp(
    vpnState: VpnState,
    currentScreen: Screen,
    blockedDomains: List<String>,
    onScreenChange: (Screen) -> Unit,
    onToggleClick: () -> Unit
) {
    val navItems = listOf(Screen.Home, Screen.Analytics)

    PhishyTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    navItems.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.label) },
                            label = { Text(screen.label) },
                            selected = currentScreen == screen,
                            onClick = { onScreenChange(screen) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (currentScreen) {
                    Screen.Home -> VpnScreen(vpnState = vpnState, onToggleClick = onToggleClick)
                    is Screen.Analytics -> AnalyticsScreen(blockedDomains = blockedDomains)
                }
            }
        }
    }
}

@Composable
fun VpnScreen(vpnState: VpnState, onToggleClick: () -> Unit) {
    val statusText = when (vpnState) {
        VpnState.CONNECTED -> "You are protected"
        VpnState.CONNECTING -> "Connecting..."
        VpnState.DISCONNECTED -> "You are vulnerable"
        VpnState.STOPPING -> "Disconnecting..."
    }

    val statusColor by animateColorAsState(
        targetValue = when (vpnState) {
            VpnState.CONNECTED -> Color(0xFF4CAF50) // Green
            VpnState.CONNECTING -> Color(0xFFFFA000) // Amber
            VpnState.DISCONNECTED -> MaterialTheme.colorScheme.error
            VpnState.STOPPING -> Color.Gray
        },
        animationSpec = tween(500)
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceAround
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Phishy", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Phishing and AdWare Protection", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PowerButton(vpnState = vpnState, onClick = onToggleClick, color = statusColor)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Status", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(statusText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = statusColor, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun PowerButton(vpnState: VpnState, onClick: () -> Unit, color: Color, modifier: Modifier = Modifier) {
    val isEnabled = vpnState == VpnState.CONNECTED || vpnState == VpnState.DISCONNECTED
    Box(
        modifier = modifier.size(200.dp).clip(CircleShape)
            .background(color.copy(alpha = 0.1f))
            .border(width = 8.dp, color = color, shape = CircleShape)
            .clickable(enabled = isEnabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.PowerSettingsNew, "Toggle VPN", tint = color, modifier = Modifier.size(80.dp))
    }
}

@Composable
fun AnalyticsScreen(blockedDomains: List<String>) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Blocked Domain History", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
        if (blockedDomains.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No domains have been blocked yet.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(blockedDomains) { domain ->
                    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                        Text(text = domain, modifier = Modifier.fillMaxWidth().padding(16.dp))
                    }
                }
            }
        }
    }
}

// --- Previews ---
@Preview(showBackground = true)
@Composable
fun AnalyticsScreenPreview() {
    PhishyTheme {
        AnalyticsScreen(blockedDomains = listOf("evil-site.com", "another-bad-one.net", "phishing-example.org"))
    }
}
