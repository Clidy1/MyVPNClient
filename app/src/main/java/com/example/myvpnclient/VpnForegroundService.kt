package com.example.myvpnclient

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.*
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class VpnForegroundService : Service() {

    private val TAG = "VPN_SERVICE"
    private val DIAG_TAG = "VPN_DIAGNOSTIC"
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "vpn_channel"

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var healthJob: Job? = null
    private val tunnel = MyVpnTunnel()
    private var lastConfig: Config? = null

    private val _vpnState = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val vpnState = _vpnState.asStateFlow()

    private val _internetAvailable = MutableStateFlow(false)
    val internetAvailable = _internetAvailable.asStateFlow()

    private lateinit var cm: ConnectivityManager

    sealed class VpnState {
        object Disconnected : VpnState()
        object Connecting : VpnState()
        object Connected : VpnState()
    }

    inner class LocalBinder : Binder() {
        fun getService(): VpnForegroundService = this@VpnForegroundService
    }

    private val binder = LocalBinder()

    private var isRebounding = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = cm.getNetworkCapabilities(network)
            val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            Log.d(DIAG_TAG, "Сеть активна: $network (VPN=$isVpn)")
            
            // Игнорируем появление VPN-сети и события во время перезапуска
            if (isVpn || isRebounding) return

            if (_vpnState.value == VpnState.Connected) {
                Log.w(DIAG_TAG, "Физическая сеть изменилась. Инициируем Double Kick...")
                triggerDoubleKick(1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createNotificationChannel()
        cm.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startVpnInternal()
            "STOP" -> stopVpnInternal()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startVpnInternal() {
        serviceScope.launch {
            try {
                _vpnState.value = VpnState.Connecting
                updateNotification("Подключение...")
                startForeground(NOTIFICATION_ID, createNotification("Подключение..."))

                Log.d(TAG, "Шаг 1: Полный сброс")
                App.backend.setState(tunnel, Tunnel.State.DOWN, null)
                delay(1000)

                val config = buildSecureConfig()
                lastConfig = config

                Log.d(TAG, "Шаг 2: Запуск")
                App.backend.setState(tunnel, Tunnel.State.UP, config)
                
                triggerDoubleKick(2500)

                _vpnState.value = VpnState.Connected
                updateNotification("VPN Подключен")
                startHealthMonitor()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка запуска: ${e.message}")
                _vpnState.value = VpnState.Disconnected
                stopSelf()
            }
        }
    }

    private fun stopVpnInternal() {
        serviceScope.launch {
            isConnectedInternal = false
            healthJob?.cancel()
            App.backend.setState(tunnel, Tunnel.State.DOWN, null)
            _vpnState.value = VpnState.Disconnected
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private var isConnectedInternal = false

    private fun triggerDoubleKick(delayMs: Long) {
        val config = lastConfig ?: return
        if (isRebounding) return
        
        serviceScope.launch {
            isRebounding = true
            delay(delayMs)
            Log.d(TAG, "Выполнение рестарта (Double Kick)...")
            try {
                App.backend.setState(tunnel, Tunnel.State.DOWN, null)
                delay(1000)
                App.backend.setState(tunnel, Tunnel.State.UP, config)
                Log.i(DIAG_TAG, "Double Kick завершен")
                // Даем время системе переключиться на VPN, прежде чем слушать новые изменения сети
                delay(5000) 
            } catch (e: Exception) {
                Log.e(DIAG_TAG, "Ошибка при Double Kick: ${e.message}")
            } finally {
                isRebounding = false
            }
        }
    }

    private fun buildSecureConfig(): Config {
        val interfaceBuilder = Interface.Builder()
            .addAddress(InetNetwork.parse("10.66.66.2/32"))
            .addDnsServer(InetAddress.getByName("1.1.1.1"))
            .addDnsServer(InetAddress.getByName("8.8.8.8"))
            .setKeyPair(KeyPair(Key.fromBase64("wF9Kb4HO54tjHLZAIW6F0ETFoW4aZC/S09keBufxvU4=")))
            .setMtu(1280)

        val peerBuilder = Peer.Builder()
            .setPublicKey(Key.fromBase64("P3kT6S5zJWKlWzf6xU2ILRcwt2LwHt3V4vDWmXLHBAM="))
            .setPreSharedKey(Key.fromBase64("SplspvqVy1K8WwLrMJ8WjB1e72crS6vHg5IckH5oUkQ="))
            .setEndpoint(InetEndpoint.parse("213.239.159.215:52817"))
            .addAllowedIp(InetNetwork.parse("0.0.0.0/0"))
            .setPersistentKeepalive(10)

        return Config.Builder()
            .setInterface(interfaceBuilder.build())
            .addPeer(peerBuilder.build())
            .build()
    }

    private fun startHealthMonitor() {
        isConnectedInternal = true
        healthJob?.cancel()
        healthJob = serviceScope.launch {
            while (isActive && isConnectedInternal) {
                var hasInternet = false
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("1.1.1.1", 53), 3000)
                        hasInternet = true
                    }
                } catch (e: Exception) {
                    Log.w(DIAG_TAG, "Проверка связи через VPN не удалась: ${e.message}")
                }
                
                _internetAvailable.value = hasInternet
                updateNotification(if (hasInternet) "VPN Активен (Интернет есть)" else "VPN Активен (Ожидание сети...)")
                delay(5000)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Status",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("My VPN Client")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        cm.unregisterNetworkCallback(networkCallback)
        serviceScope.cancel()
    }
}
