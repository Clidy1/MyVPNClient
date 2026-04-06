package com.example.myvpnclient

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class MainActivity : AppCompatActivity() {

    private val TAG = "VPN_MAIN"
    private var vpnService: VpnForegroundService? = null
    private var isBound = false

    private lateinit var vpnButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var internetStatusText: TextView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VpnForegroundService.LocalBinder
            vpnService = binder.getService()
            isBound = true
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            vpnService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vpnButton = findViewById(R.id.vpnButton)
        statusText = findViewById(R.id.statusText)
        internetStatusText = findViewById(R.id.internetStatusText)

        vpnButton.setOnClickListener {
            toggleVpn()
        }

        val intent = Intent(this, VpnForegroundService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    private fun observeService() {
        lifecycleScope.launch {
            vpnService?.vpnState?.collectLatest { state ->
                updateUi(state)
            }
        }
        lifecycleScope.launch {
            vpnService?.internetAvailable?.collectLatest { available ->
                updateInternetStatus(available)
            }
        }
    }

    private fun toggleVpn() {
        val currentState = vpnService?.vpnState?.value ?: VpnForegroundService.VpnState.Disconnected
        if (currentState == VpnForegroundService.VpnState.Disconnected) {
            checkPermissionsAndConnect()
        } else {
            val intent = Intent(this, VpnForegroundService::class.java).apply { action = "STOP" }
            startService(intent)
        }
    }

    private fun updateUi(state: VpnForegroundService.VpnState) {
        when (state) {
            is VpnForegroundService.VpnState.Connected -> {
                statusText.text = "ПОДКЛЮЧЕНО"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
                vpnButton.text = "ОТКЛЮЧИТЬ"
                vpnButton.isEnabled = true
            }
            is VpnForegroundService.VpnState.Connecting -> {
                statusText.text = "ПОДКЛЮЧЕНИЕ..."
                statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                vpnButton.text = "ПОДОЖДИТЕ"
                vpnButton.isEnabled = false
            }
            is VpnForegroundService.VpnState.Disconnected -> {
                statusText.text = "ОТКЛЮЧЕНО"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
                vpnButton.text = "ПОДКЛЮЧИТЬ"
                vpnButton.isEnabled = true
                internetStatusText.text = "ОТКЛЮЧЕНО"
                internetStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }
    }

    private fun updateInternetStatus(available: Boolean) {
        if (vpnService?.vpnState?.value != VpnForegroundService.VpnState.Connected) return
        
        internetStatusText.text = if (available) "ИНТЕРНЕТ: ЕСТЬ" else "ИНТЕРНЕТ: ОЖИДАНИЕ ПАКЕТОВ..."
        internetStatusText.setTextColor(ContextCompat.getColor(this, 
            if (available) R.color.status_connected else R.color.status_disconnected))
    }

    private fun checkPermissionsAndConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            prepareVpn()
        }
    }

    private fun prepareVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent) else startVpnService()
    }

    private fun startVpnService() {
        val intent = Intent(this, VpnForegroundService::class.java).apply { action = "START" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { 
        if (it.resultCode == Activity.RESULT_OK) startVpnService()
    }
    
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { 
        if (it) prepareVpn() 
    }
}
