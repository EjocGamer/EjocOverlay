package com.salajuegos.ejocoverlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.File

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var cartelView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var mostrando = false

    private val vigilante = object : Runnable {
        override fun run() {
            verificarTiempo()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        iniciarNotificacionPersistente()
        handler.post(vigilante)
    }

    private fun iniciarNotificacionPersistente() {
        val channelId = "ejoc_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "EjocGames Overlay", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("EjocGames TV Activo")
            .setContentText("Vigilando tiempo de sesión")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notif)
    }

    private fun verificarTiempo() {
        if (!Settings.canDrawOverlays(this)) return

        val archivo = File("/sdcard/Download/sala_fin.txt")
        if (!archivo.exists()) {
            ocultarCartel()
            return
        }

        val fin = archivo.readText().trim().toLongOrNull() ?: 0L
        val ahora = System.currentTimeMillis() / 1000L
        val restante = (fin - ahora).toInt()

        // Muestra el cartel flotante en los últimos 120 segundos (2 minutos)
        if (restante in 1..120) {
            val min = restante / 60
            val seg = restante % 60
            val texto = String.format("⚠️ TIEMPO RESTANTE: %02d:%02d", min, seg)
            mostrarOActualizarCartel(texto, restante <= 30)
        } else {
            ocultarCartel()
        }
    }

    private fun mostrarOActualizarCartel(texto: String, urgente: Boolean) {
        if (!mostrando) {
            val bg = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.parseColor("#E6DC2626"))
                setStroke(4, Color.WHITE)
            }

            cartelView = TextView(this).apply {
                text = texto
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = bg
                setPadding(36, 20, 36, 20)
            }

            val tipoVentana = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                tipoVentana,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 40
                y = 40
            }

            windowManager.addView(cartelView, params)
            mostrando = true
        } else {
            cartelView?.text = texto
            if (urgente) {
                // Parpadeo sutil los últimos 30 segundos
                cartelView?.alpha = if ((System.currentTimeMillis() / 500) % 2 == 0L) 1.0f else 0.65f
            }
        }
    }

    private fun ocultarCartel() {
        if (mostrando && cartelView != null) {
            try {
                windowManager.removeView(cartelView)
            } catch (_: Exception) {}
            cartelView = null
            mostrando = false
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(vigilante)
        ocultarCartel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
