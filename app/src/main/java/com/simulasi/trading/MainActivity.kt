package com.simulasi.trading

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.simulasi.trading.data.BinanceState
import com.simulasi.trading.data.BinanceStore
import com.simulasi.trading.data.Prefs
import com.simulasi.trading.data.ServiceState
import com.simulasi.trading.notify.Notifier
import com.simulasi.trading.service.MonitorService
import com.simulasi.trading.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* hasil dipakai sistem */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Notifier.createChannels(this)
        Notifier.prepare(this)
        ServiceState.enabled = Prefs.isEnabled(this)
        ServiceState.soundEnabled = Prefs.soundEnabled(this)
        ServiceState.price = Prefs.lastPrice(this).takeIf { it > 0 }

        val creds = BinanceStore.loadCredentials(this)
        BinanceState.configured = creds != null
        BinanceState.comment = creds?.comment.orEmpty()
        BinanceState.apiEnabled = BinanceStore.apiEnabled(this)
        BinanceState.autoEnabled = BinanceStore.autoEnabled(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Default AKTIF: layanan latar belakang langsung berjalan saat aplikasi dibuka.
        if (Prefs.isEnabled(this)) MonitorService.start(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF22D3EE),
                    background = Color(0xFF0B0D10),
                    surface = Color(0xFF11151A),
                ),
            ) {
                Surface(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0D10))) {
                    MainScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Prefs.setAppVisible(this, true)
    }

    override fun onPause() {
        Prefs.setAppVisible(this, false)
        super.onPause()
    }
}
