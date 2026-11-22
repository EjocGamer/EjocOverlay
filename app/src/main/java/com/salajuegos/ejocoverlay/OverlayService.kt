package com.salajuegos.ejocoverlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.File

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: LinearLayout? = null
    private var txtMensaje: TextView? = null
    private var txtReloj: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private var ultimoFinRegistrado: Long = 0L
    private var ocultarEnMillis: Long = 0L

    private val DURACION_VISIBLE_MS = 5000L // 5 segundos visible al agregar tiempo

    private val bucleVigilancia = object : Runnable {
        override fun run() {
            verificarArchivosEstado()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        iniciarNotificacionServicio()
        if (Settings.canDrawOverlays(this)) {
            crearVentanaFlotante()
            handler.post(bucleVigilancia)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView == null && Settings.canDrawOverlays(this)) {
            crearVentanaFlotante()
            handler.removeCallbacks(bucleVigilancia)
            handler.post(bucleVigilancia)
        }
        return START_STICKY
    }

    private fun iniciarNotificacionServicio() {
        val channelId = "ejoc_overlay_tv"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "EjocOverlay TV",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("EjocOverlay Activo")
            .setContentText("Mostrando avisos de tiempo en pantalla")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .build()
        startForeground(301, notif)
    }

    private fun crearVentanaFlotante() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val fondo = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(Color.parseColor("#E60F172A"))
            setStroke((2f * density).toInt(), Color.parseColor("#38BDF8"))
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondo
            setPadding(dp(16), dp(10), dp(18), dp(10))
            visibility = View.GONE
        }

        txtMensaje = TextView(this).apply {
            text = "⏳ El TV se apagará en:"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
        }

        txtReloj = TextView(this).apply {
            text = "00:00:00"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        overlayView?.addView(txtMensaje)
        overlayView?.addView(txtReloj)

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(24)
            y = dp(24)
        }

        windowManager.addView(overlayView, params)
    }

    private fun actualizarEstiloCaja(bordeHex: String, colorTextoTitulo: String, colorReloj: String) {
        val density = resources.displayMetrics.density
        val fondo = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(Color.parseColor("#E60F172A"))
            setStroke((2f * density).toInt(), Color.parseColor(bordeHex))
        }
        overlayView?.background = fondo
        txtMensaje?.setTextColor(Color.parseColor(colorTextoTitulo))
        txtReloj?.setTextColor(Color.parseColor(colorReloj))
    }

    private fun leerTimestampLocal(ruta: String): Long {
        // 1. Lectura directa estándar
        try {
            val f = File(ruta)
            if (f.exists() && f.canRead()) {
                val v = f.readText().trim().toLongOrNull() ?: 0L
                if (v > 1700000000L) return v
            }
        } catch (_: Exception) {
        }

        // 2. Lectura mediante subproceso local 'cat' (evita bloqueo Scoped Storage sin usar Wi-Fi)
        try {
            val proceso = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cat $ruta 2>/dev/null"))
            val salida = proceso.inputStream.bufferedReader().use { it.readText().trim() }
            proceso.waitFor()
            val v = salida.toLongOrNull() ?: 0L
            if (v > 1700000000L) return v
        } catch (_: Exception) {
        }

        return 0L
    }

    private fun verificarArchivosEstado() {
        val view = overlayView ?: return
        val ahoraSeg = System.currentTimeMillis() / 1000L
        val ahoraMs = System.currentTimeMillis()

        // 1. Tiempo Libre / Ventana de Espera: visible de forma PERMANENTE
        val inicioLibre = leerTimestampLocal("/sdcard/Download/sala_libre.txt")
        if (inicioLibre > 0L && ahoraSeg >= inicioLibre) {
            ultimoFinRegistrado = 0L

            val transcurrido = (ahoraSeg - inicioLibre).toInt()
            val h = transcurrido / 3600
            val m = (transcurrido % 3600) / 60
            val s = transcurrido % 60

            txtMensaje?.text = "⏱️ Tiempo libre / adicional:"
            txtReloj?.text = String.format("%02d:%02d:%02d", h, m, s)
            actualizarEstiloCaja("#38BDF8", "#7DD3FC", "#FFFFFF")
            view.visibility = View.VISIBLE
            return
        }

        // 2. Temporizador normal: 5s al agregar tiempo + permanente en alerta de 2 min
        val fin = leerTimestampLocal("/sdcard/Download/sala_fin.txt")
        val restante = (fin - ahoraSeg).toInt()
        if (fin > 0L && restante > 0) {
            if (fin != ultimoFinRegistrado) {
                ultimoFinRegistrado = fin
                ocultarEnMillis = ahoraMs + DURACION_VISIBLE_MS
            }

            val h = restante / 3600
            val m = (restante % 3600) / 60
            val s = restante % 60

            txtMensaje?.text = "⏳ El TV se apagará en:"
            txtReloj?.text = String.format("%02d:%02d:%02d", h, m, s)

            if (restante <= 120) {
                actualizarEstiloCaja("#EF4444", "#FCA5A5", "#FACC15")
                view.visibility = View.VISIBLE
            } else {
                actualizarEstiloCaja("#22C55E", "#86EFAC", "#FFFFFF")
                view.visibility = if (ahoraMs < ocultarEnMillis) View.VISIBLE else View.GONE
            }
            return
        } else {
            ultimoFinRegistrado = 0L
        }

        // 3. Sin tiempo activo: ocultar completamente
        view.visibility = View.GONE
    }

    override fun onDestroy() {
        handler.removeCallbacks(bucleVigilancia)
        overlayView?.let { windowManager.removeView(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
