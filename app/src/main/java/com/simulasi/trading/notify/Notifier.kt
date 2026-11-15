package com.simulasi.trading.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.simulasi.trading.MainActivity
import com.simulasi.trading.R
import com.simulasi.trading.data.Patokan
import com.simulasi.trading.data.Prefs
import com.simulasi.trading.data.ServiceState
import com.simulasi.trading.service.MonitorService
import java.util.Locale

object Notifier {
    const val CHANNEL_SIGNAL = "simulasi_signal"
    const val CHANNEL_PATOKAN = "simulasi_patokan"
    const val CHANNEL_SERVICE = "simulasi_service"
    const val SERVICE_NOTIF_ID = 4201

    @Volatile
    private var sound: SoundPlayer? = null

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val signal = NotificationChannel(
            CHANNEL_SIGNAL,
            "Sinyal Trading",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(null, null) // suara diputar sendiri supaya notif.mp3 + ucapan konsisten
            enableVibration(true)
        }
        val patokan = NotificationChannel(
            CHANNEL_PATOKAN,
            "Patokan Harga",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(null, null)
            enableVibration(true)
        }
        val service = NotificationChannel(
            CHANNEL_SERVICE,
            "Status Layanan",
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(signal)
        nm.createNotificationChannel(patokan)
        nm.createNotificationChannel(service)
    }

    @Synchronized
    fun prepare(ctx: Context) {
        val app = ctx.applicationContext
        if (Prefs.soundEnabled(app) && sound == null) sound = SoundPlayer(app)
    }

    @Synchronized
    fun setSoundEnabled(ctx: Context, enabled: Boolean) {
        val app = ctx.applicationContext
        Prefs.setSoundEnabled(app, enabled)
        ServiceState.soundEnabled = enabled
        if (enabled) {
            if (sound == null) sound = SoundPlayer(app)
        } else {
            sound?.stopAll()
        }
    }

    /**
     * Bunyikan notif.mp3 + TTS dari WebView. Dipakai jembatan `SimulasiNative.alert`
     * karena WebView tidak punya Web Speech API. Hanya saat aplikasi terlihat:
     * kalau di latar belakang, MonitorService yang menangani supaya tidak dobel.
     */
    fun playInApp(ctx: Context, text: String, cycles: Int, speechRepeats: Int) {
        if (!Prefs.appVisible(ctx) || !Prefs.soundEnabled(ctx)) return
        prepare(ctx)
        sound?.play(
            text = text,
            cycles = cycles.coerceIn(1, 5),
            speechRepeats = speechRepeats.coerceIn(1, 5),
        )
    }

    private fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            ctx,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun fireSignal(ctx: Context, model: String, side: Int, price: Double) {
        if (Prefs.appVisible(ctx)) return
        createChannels(ctx)
        val long = side >= 0
        val title = "Sinyal ${if (long) "LONG" else "SHORT"} · $model"
        val body = "SOLUSDT ${formatPrice(price)} · dari model $model"

        if (canNotify(ctx)) {
            val n = NotificationCompat.Builder(ctx, CHANNEL_SIGNAL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx, 200))
                .build()
            NotificationManagerCompat.from(ctx).notify(("$model-$side-${price.toLong()}").hashCode(), n)
        }

        if (Prefs.soundEnabled(ctx)) {
            prepare(ctx)
            sound?.play(
                text = "sinyal ${if (long) "long" else "short"} dari model $model",
                cycles = 1,
                speechRepeats = 3,
            )
        }
    }

    fun firePatokan(ctx: Context, patokan: Patokan, price: Double) {
        if (Prefs.appVisible(ctx)) return
        createChannels(ctx)
        val title = "Patokan tercapai"
        val body = "${patokan.label()} · harga sekarang ${formatPrice(price)}"

        if (canNotify(ctx)) {
            val n = NotificationCompat.Builder(ctx, CHANNEL_PATOKAN)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx, 201))
                .build()
            NotificationManagerCompat.from(ctx).notify(patokan.id.hashCode(), n)
        }

        if (Prefs.soundEnabled(ctx)) {
            prepare(ctx)
            sound?.play(
                text = "patokan ${speechValue(patokan.value)} sudah di tercapai",
                cycles = 3,
                speechRepeats = 1,
            )
        }
    }

    /** Notifikasi kejadian automation Binance (entry, TP, SL, cek ulang, keluar). */
    fun fireAuto(ctx: Context, text: String) {
        createChannels(ctx)
        if (canNotify(ctx)) {
            val n = NotificationCompat.Builder(ctx, CHANNEL_SIGNAL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Auto Binance")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx, 203))
                .build()
            NotificationManagerCompat.from(ctx).notify(("auto-$text").hashCode(), n)
        }
        if (Prefs.soundEnabled(ctx)) {
            prepare(ctx)
            sound?.play(text = text, cycles = 1, speechRepeats = 2)
        }
    }

    fun buildService(ctx: Context, text: String, soundOn: Boolean): Notification {
        val stop = PendingIntent.getService(
            ctx,
            210,
            Intent(ctx, MonitorService::class.java).setAction(MonitorService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getService(
            ctx,
            211,
            Intent(ctx, MonitorService::class.java).setAction(MonitorService.ACTION_TOGGLE_SOUND),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Simulasi Trading aktif")
            .setContentText(if (soundOn) text else "$text · suara mati")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx, 202))
            .addAction(0, if (soundOn) "Bisukan suara" else "Nyalakan suara", toggle)
            .addAction(0, "Matikan", stop)
            .build()
    }

    fun updateService(ctx: Context, text: String = "memantau sinyal…") {
        try {
            createChannels(ctx)
            NotificationManagerCompat.from(ctx)
                .notify(SERVICE_NOTIF_ID, buildService(ctx, text, Prefs.soundEnabled(ctx)))
        } catch (_: Exception) {
            // notifikasi layanan bisa ditolak pada kondisi tertentu; layanan tetap berjalan
        }
    }

    fun formatPrice(value: Double): String =
        if (value.isFinite() && value > 0) String.format(Locale("id", "ID"), "%,.2f", value) else "—"

    private fun speechValue(value: Double): String {
        val text = if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.US, "%.2f", value)
        return text.replace('.', ',')
    }
}
