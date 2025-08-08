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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat // ✅ 1. ADD THIS IMPORT
import com.test.phishy.ui.theme.PhishyTheme

class MainActivity : ComponentActivity() {

    private var isVpnConnected by mutableStateOf(false)

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MyVpnService.BROADCAST_VPN_STATE) {
                isVpnConnected = intent.getBooleanExtra(MyVpnService.EXTRA_VPN_STATE, false)
                Log.d(TAG, "Received VPN state update: isConnected = $isVpnConnected")
            }
        }
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Log.i(TAG, "VPN permission granted. Connecting...")
                startVpnService()
            } else {
                Log.w(TAG, "VPN permission denied.")
                isVpnConnected = false
            }
        }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleVpnPrepareIntent(intent)

        setContent {
            PhishyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VpnToggleScreen(
                        isConnected = isVpnConnected,
                        onToggleClick = {
                            Log.d("MainActivity", "Button clicked! Current state isConnected = $isVpnConnected")
                            if (isVpnConnected) {
                                stopVpnService()
                            } else {
                                requestVpnPermissionAndConnect()
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val intentFilter = IntentFilter(MyVpnService.BROADCAST_VPN_STATE)

        // ✅ 2. USE THE COMPATIBILITY VERSION OF THE CALL
        ContextCompat.registerReceiver(
            this,
            vpnStateReceiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(vpnStateReceiver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleVpnPrepareIntent(intent)
    }

    private fun handleVpnPrepareIntent(intent: Intent) {
        intent.getParcelableExtra<Intent>("VPN_PREPARE_INTENT")?.let {
            Log.d(TAG, "Received VPN_PREPARE_INTENT from service")
            vpnPermissionLauncher.launch(it)
            intent.removeExtra("VPN_PREPARE_INTENT")
        }
    }

    private fun requestVpnPermissionAndConnect() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            Log.i(TAG, "VPN permission not granted. Launching permission intent.")
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            Log.i(TAG, "VPN permission already granted. Connecting...")
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_CONNECT
        }
        startService(intent)
    }



    private fun stopVpnService() {
        val intent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }
}


// --- Composable UI (No changes needed) ---

@Composable
fun VpnToggleScreen(isConnected: Boolean, onToggleClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = if (isConnected) "VPN Status: Connected" else "VPN Status: Disconnected")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onToggleClick) {
            Text(text = if (isConnected) "Disconnect VPN" else "Connect VPN")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    PhishyTheme {
        VpnToggleScreen(isConnected = false, onToggleClick = {})
    }
}