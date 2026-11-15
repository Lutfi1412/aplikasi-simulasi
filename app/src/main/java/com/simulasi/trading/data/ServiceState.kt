package com.simulasi.trading.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Status yang dibaca Compose dari layanan latar belakang. */
object ServiceState {
    var enabled by mutableStateOf(true)
    var connected by mutableStateOf(false)
    var soundEnabled by mutableStateOf(true)
    var price by mutableStateOf<Double?>(null)
    var lastUpdate by mutableStateOf(0L)
    var lastError by mutableStateOf<String?>(null)
}
