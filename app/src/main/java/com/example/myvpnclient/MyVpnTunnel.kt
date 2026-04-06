package com.example.myvpnclient

import com.wireguard.android.backend.Tunnel

class MyVpnTunnel : Tunnel {
    override fun getName(): String {
        return "MyVPS"
    }

    override fun onStateChange(newState: Tunnel.State) {
        android.util.Log.d("VPN_DEBUG", "Tunnel state changed to: $newState")
    }
}