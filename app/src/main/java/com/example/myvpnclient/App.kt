package com.example.myvpnclient

import android.app.Application
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend

class App : Application() {

    companion object {
        lateinit var backend: Backend
            private set
    }

    override fun onCreate() {
        super.onCreate()
        backend = GoBackend(this)
    }
}