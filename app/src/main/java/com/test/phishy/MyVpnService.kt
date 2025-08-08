package com.test.phishy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.concurrent.thread

class MyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null

    // A simple flag to track the service's state
    @Volatile
    private var isRunning = false

    companion object {
        const val ACTION_CONNECT = "com.test.phishy.CONNECT"
        const val ACTION_DISCONNECT = "com.test.phishy.DISCONNECT"

        // Broadcast actions for status updates
        const val BROADCAST_VPN_STATE = "com.test.phishy.VPN_STATE"
        const val EXTRA_VPN_STATE = "com.test.phishy.VPN_STATE_EXTRA"

        private const val NOTIFICATION_CHANNEL_ID = "MyVpnServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "MyVpnService"

        // VPN Parameters
        private const val VPN_ADDRESS = "10.8.0.2"
        private const val VPN_ROUTE = "0.0.0.0"

        // This is the key for DNS filtering. We'll route DNS traffic to this local address.
        private const val DNS_SERVER = "10.8.0.1"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                Log.i(TAG, "Received CONNECT action")
                startVpn()
            }
            ACTION_DISCONNECT -> {
                Log.i(TAG, "Received DISCONNECT action")
                stopVpn()
            }
        }
        // If the service is killed, it will be automatically restarted.
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) {
            Log.i(TAG, "VPN is already running.")
            return
        }

        val prepareIntent = prepare(this)
        if (prepareIntent != null) {
            Log.w(TAG, "VPN not prepared. Requesting permission.")
            // Send an intent to the activity to request permission.
            val permissionIntent = Intent(this, MainActivity::class.java).apply {
                putExtra("VPN_PREPARE_INTENT", prepareIntent)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(permissionIntent)
            return // Wait for the user to grant permission.
        }

        try {
            // Configure the VPN interface
            val builder = Builder()
                .addAddress(VPN_ADDRESS, 24)
                .addRoute(VPN_ROUTE, 0)
                .addDnsServer(DNS_SERVER) // Route DNS requests through the VPN to our local handler.
                .setSession(application.getString(R.string.app_name))
                .setMtu(1500)

            vpnInterface = builder.establish() ?: throw IllegalStateException("Failed to establish VPN interface.")
            Log.i(TAG, "VPN interface established.")

            isRunning = true

            // Start the background thread to handle network traffic.
            vpnThread = thread(start = true, name = "VpnThread") {
                runVpnLoop(vpnInterface!!)
            }

            // Start foreground service and update state
            startForeground(NOTIFICATION_ID, createNotification("VPN Connected"))
            broadcastVpnState()
            Log.i(TAG, "VPN started successfully.")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN", e)
            stopVpn() // Clean up on failure.
        }
    }

    /**
     * This is the core loop for handling VPN traffic.
     * It's where you'll implement your packet inspection and DNS filtering.
     */
    private fun runVpnLoop(vpnInterface: ParcelFileDescriptor) {
        val vpnInput = FileInputStream(vpnInterface.fileDescriptor).channel
        val vpnOutput = FileOutputStream(vpnInterface.fileDescriptor).channel

        try {
            // This is a placeholder for your packet processing logic.
            // In a real implementation, you would read packets from vpnInput,
            // analyze them, and write them to vpnOutput (or a real network socket).
            while (isRunning) {
                // TODO: Implement your custom DNS filtering logic here. 🕵️‍♂️
                // 1. Read an IP packet from `vpnInput`.
                // 2. Parse the packet to see if it's a DNS query.
                //    - It will be a UDP packet.
                //    - The destination IP will be `DNS_SERVER` (10.8.0.1).
                //    - The destination port will be 53.
                // 3. If it is a DNS query, extract the domain name.
                // 4. Check the domain against your blocklist.
                // 5. If blocked, drop the packet. If not, forward it to a real DNS server
                //    (e.g., 8.8.8.8) and write the response back to `vpnOutput`.
                // 6. For non-DNS packets, you would typically forward them to the internet.

                // For this example, we'll just keep the thread alive.
                Thread.sleep(100)
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "VPN thread interrupted.")
        } catch (e: Exception) {
            if (isRunning) { // Only log if we weren't expecting to stop.
                Log.e(TAG, "Error in VPN loop", e)
            }
        } finally {
            Log.i(TAG, "VPN loop finished.")
        }
    }

    private fun stopVpn() {
        if (!isRunning) return

        Log.i(TAG, "Stopping VPN...")
        isRunning = false
        vpnThread?.interrupt() // Signal the thread to stop.

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        broadcastVpnState() // Notify the UI that the VPN has stopped.
        Log.i(TAG, "VPN stopped.")
    }

    private fun broadcastVpnState() {
        val intent = Intent(BROADCAST_VPN_STATE).apply {
            putExtra(EXTRA_VPN_STATE, isRunning)
        }
        sendBroadcast(intent)
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked by user!")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i(TAG, "VpnService onDestroy")
        stopVpn() // Ensure cleanup.
        super.onDestroy()
    }

    // --- Notification Management ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for VPN service status"
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val disconnectIntent = Intent(this, MyVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val disconnectPendingIntent = PendingIntent.getService(this, 0, disconnectIntent, flags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with your app's icon
            .addAction(R.drawable.ic_launcher_foreground, "Disconnect", disconnectPendingIntent)
            .setOngoing(true)
            .setSilent(true) // Prevent sound on every update.
            .build()
    }
}