package com.test.phishy

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import kotlin.concurrent.thread

class MyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null

    @Volatile
    private var isRunning = false

    companion object {
        const val ACTION_CONNECT = "com.test.phishy.CONNECT"
        const val ACTION_DISCONNECT = "com.test.phishy.DISCONNECT"
        const val BROADCAST_VPN_STATE = "com.test.phishy.VPN_STATE"
        const val BROADCAST_BLOCKED_DOMAIN = "com.test.phishy.BROADCAST_BLOCKED_DOMAIN"

        const val EXTRA_BLOCKED_DOMAIN = "com.test.phishy.EXTRA_BLOCKED_DOMAIN"
        const val EXTRA_VPN_STATE = "com.test.phishy.VPN_STATE_EXTRA"

        private const val TAG = "MyVpnService"
        private const val VPN_ADDRESS = "10.8.0.2"
        private const val DNS_SERVER = "10.8.0.1"
        private const val REAL_DNS_SERVER = "8.8.8.8"

        // --- Notification Constants ---
        private const val NOTIFICATION_CHANNEL_ID = "VpnServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val BLOCK_WARNING_CHANNEL_ID = "BlockWarningChannel"
        private var blockNotificationId = 2
    }

    override fun onCreate() {
        super.onCreate()
        createServiceNotificationChannel()
        createBlockWarningNotificationChannel()
        DomainBlocklist.loadBlocklist(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> startVpn()
            ACTION_DISCONNECT -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return

        try {
            val builder = Builder()
                .addAddress(VPN_ADDRESS, 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(DNS_SERVER)
                .setSession(application.getString(R.string.app_name))
                .setMtu(1500)

            vpnInterface = builder.establish() ?: throw IllegalStateException("Failed to establish VPN interface.")
            isRunning = true
            vpnThread = thread(start = true, name = "VpnThread") { runVpnLoop(vpnInterface!!) }
            startForeground(NOTIFICATION_ID, createServiceNotification("VPN Connected"))
            broadcastVpnState()
            Log.i(TAG, "VPN started successfully for all applications.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN", e)
            stopVpn()
        }
    }

    private fun runVpnLoop(vpnInterface: ParcelFileDescriptor) {
        val vpnInput = FileInputStream(vpnInterface.fileDescriptor)
        val vpnOutput = FileOutputStream(vpnInterface.fileDescriptor)
        val packet = ByteBuffer.allocate(32767)

        while (isRunning) {
            try {
                val length = vpnInput.read(packet.array())
                if (length > 0) {
                    packet.limit(length)
                    val domain = extractDomain(packet)

                    if (domain != null) {
                        if (DomainBlocklist.isBlocked(domain)) {
                            Log.w(TAG, "Blocked adware/tracker site: $domain")
                            broadcastBlockedDomain(domain)
                            showBlockWarningNotification(domain)
                        } else {
                            forwardDnsPacket(packet, vpnOutput)
                        }
                    } else {
                        vpnOutput.write(packet.array(), 0, length)
                    }
                    packet.clear()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Error in VPN loop", e)
                break
            }
        }
    }

    private fun forwardDnsPacket(requestPacket: ByteBuffer, vpnOutput: FileOutputStream) {
        val upstreamDns = DatagramChannel.open()
        protect(upstreamDns.socket())
        upstreamDns.connect(InetSocketAddress(REAL_DNS_SERVER, 53))

        try {
            requestPacket.rewind()
            upstreamDns.write(requestPacket)
            val responsePacket = ByteBuffer.allocate(32767)
            val readBytes = upstreamDns.read(responsePacket)
            if (readBytes > 0) {
                responsePacket.limit(readBytes)
                vpnOutput.write(responsePacket.array(), 0, readBytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error forwarding DNS packet", e)
        } finally {
            upstreamDns.close()
        }
    }

    private fun extractDomain(packet: ByteBuffer): String? {
        try {
            val ipHeaderSize = (packet.get(0).toInt() and 0x0F) * 4
            // Assuming UDP, which has an 8-byte header
            val dnsPayloadOffset = ipHeaderSize + 8

            if (packet.limit() <= dnsPayloadOffset + 12) return null

            // Create a slice of the buffer that only contains the DNS payload
            val dnsPayload = packet.duplicate()
            dnsPayload.position(dnsPayloadOffset)

            // Skip DNS header (12 bytes) to get to the question section
            dnsPayload.position(dnsPayload.position() + 12)

            return readDomainName(dnsPayload, dnsPayload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse domain", e)
            return null
        }
    }

    // ✅ FIX: This function is now more robust and correctly handles DNS pointers.
    private fun readDomainName(readerBuffer: ByteBuffer, entireDnsPayload: ByteBuffer): String? {
        val domain = StringBuilder()
        if (!readerBuffer.hasRemaining()) return null

        var length = readerBuffer.get().toInt() and 0xFF
        while (length != 0) {
            // Check for DNS pointer compression
            if ((length and 0xC0) == 0xC0) {
                if (!readerBuffer.hasRemaining()) return null
                val offset = ((length and 0x3F) shl 8) + (readerBuffer.get().toInt() and 0xFF)

                // The offset is relative to the start of the DNS payload
                val newReader = entireDnsPayload.duplicate()
                if (offset >= newReader.limit()) return null // Invalid offset
                newReader.position(offset)

                val pointedDomain = readDomainName(newReader, entireDnsPayload)
                if (pointedDomain != null) {
                    if (domain.isNotEmpty()) domain.append('.')
                    domain.append(pointedDomain)
                }
                return domain.toString() // Pointers are always the end of a name
            } else {
                if (domain.isNotEmpty()) domain.append('.')
                if (readerBuffer.remaining() < length) return null
                val domainPart = ByteArray(length)
                readerBuffer.get(domainPart)
                domain.append(String(domainPart))
            }

            if (!readerBuffer.hasRemaining()) return null
            length = readerBuffer.get().toInt() and 0xFF
        }
        return if (domain.isEmpty()) null else domain.toString()
    }


    private fun stopVpn() {
        if (!isRunning) return
        isRunning = false
        vpnThread?.interrupt()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        broadcastVpnState()
        Log.i(TAG, "VPN stopped.")
    }

    private fun broadcastVpnState() {
        sendBroadcast(Intent(BROADCAST_VPN_STATE).apply { putExtra(EXTRA_VPN_STATE, isRunning) })
    }

    private fun broadcastBlockedDomain(domain: String) {
        sendBroadcast(Intent(BROADCAST_BLOCKED_DOMAIN).apply { putExtra(EXTRA_BLOCKED_DOMAIN, domain) })
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createServiceNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN Service Status",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for the active VPN connection"
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createBlockWarningNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BLOCK_WARNING_CHANNEL_ID,
                "Blocked Site Warnings",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows a warning when a malicious or ad site is blocked"
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createServiceNotification(text: String): Notification {
        val disconnectIntent = Intent(this, MyVpnService::class.java).apply { action = ACTION_DISCONNECT }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        val disconnectPendingIntent = PendingIntent.getService(this, 0, disconnectIntent, flags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .addAction(R.drawable.ic_launcher_foreground, "Disconnect", disconnectPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun showBlockWarningNotification(domain: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted.")
                return
            }
        }

        val notification = NotificationCompat.Builder(this, BLOCK_WARNING_CHANNEL_ID)
            .setContentTitle("Site Blocked")
            .setContentText("Phishy Guard blocked access to: $domain")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        with(NotificationManagerCompat.from(this)) {
            notify(blockNotificationId++, notification)
        }
    }
}
