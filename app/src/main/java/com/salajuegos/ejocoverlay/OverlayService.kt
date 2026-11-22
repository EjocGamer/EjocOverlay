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
    private var diagnosticoHastaMillis: Long = 0L

    // Respaldo en memoria recibido directamente por Intent desde el celular
    private var memModo: String = "NONE" // "TIMER", "LIBRE", "NONE"
    private var memFin: Long = 0L
    private var memInicioLibre: Long = 0L

    private val DURACION_VISIBLE_MS = 5000L

    private val bucleVigilancia = object : Runnable {
        override fun run() {
            verificarArchivosEstado()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        iniciarNotificacionServicio()
        intentarCrearVentana()
        handler.post(bucleVigilancia)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intentarCrearVentana()

        // Leer extras si el celular los envió en el comando am startservice
        val accion = intent?.getStringExtra("accion")
        val valorTs = intent?.getLongExtra("ts", 0L) ?: 0L
        if (accion != null) {
            when (accion) {
                "TIMER" -> {
                    memModo = "TIMER"
                    memFin = valorTs
                    memInicioLibre = 0L
                }
                "LIBRE" -> {
                    memModo = "LIBRE"
                    memInicioLibre = valorTs
                    memFin = 0L
                }
                "CLEAR" -> {
                    memModo = "NONE"
                    memFin = 0L
                    memInicioLibre = 0L
                }
            }
        }

        handler.removeCallbacks(bucleVigilancia)
        handler.post(bucleVigilancia)
        return START_STICKY
    }

    private fun intentarCrearVentana() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) return

        try {
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
                visibility = View.VISIBLE
            }

            txtMensaje = TextView(this).apply {
                text = "✅ EjocOverlay conectado"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
            }

            txtReloj = TextView(this).apply {
                text = "Esperando tiempo..."
                textSize = 20f
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
            // Mostrar cartel de confirmación inicial durante 6 segundos
            diagnosticoHastaMillis = System.currentTimeMillis() + 6000L
        } catch (_: Exception) {
            overlayView = null
        }
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

    private fun leerTimestampLocal(nombreArchivo: String): Long {
        val rutas = listOf(
            "/data/local/tmp/$nombreArchivo",
            "/sdcard/Download/$nombreArchivo"
        )
        for (ruta in rutas) {
            try {
                val f = File(ruta)
                if (f.exists() && f.canRead()) {
                    val v = f.readText().trim().toLongOrNull() ?: 0L
                    if (v > 1700000000L) return v
                }
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    private fun verificarArchivosEstado() {
        intentarCrearVentana()
        val view = overlayView ?: return
        val ahoraSeg = System.currentTimeMillis() / 1000L
        val ahoraMs = System.currentTimeMillis()

        // 1. Verificar Tiempo Libre (archivo o memoria)
        var inicioLibre = leerTimestampLocal("sala_libre.txt")
        if (inicioLibre == 0L && memModo == "LIBRE") {
            inicioLibre = memInicioLibre
        }

        if (inicioLibre > 1700000000L && ahoraSeg >= inicioLibre) {
            ultimoFinRegistrado = 0L
            val transcurrido = (ahoraSeg - inicioLibre).toInt()
            val h = transcurrido / 3600
            val m = (transcurrido % 3600) / 60
            val s = transcurrido % 60

            txtMensaje?.text = "⏱️ Tiempo libre / adicional:"
            txtReloj?.text = String.format("%02d:%02d:%02d", h, m, s)
            txtReloj?.textSize = 26f
            actualizarEstiloCaja("#38BDF8", "#7DD3FC", "#FFFFFF")
            view.visibility = View.VISIBLE
            return
        }

        // 2. Verificar Temporizador (archivo o memoria)
        var fin = leerTimestampLocal("sala_fin.txt")
        if (fin == 0L && memModo == "TIMER") {
            fin = memFin
        }

        val restante = (fin - ahoraSeg).toInt()
        if (fin > 1700000000L && restante > 0) {
            if (fin != ultimoFinRegistrado) {
                ultimoFinRegistrado = fin
                ocultarEnMillis = ahoraMs + DURACION_VISIBLE_MS
            }

            val h = restante / 3600
            val m = (restante % 3600) / 60
            val s = restante % 60

            txtMensaje?.text = "⏳ El TV se apagará en:"
            txtReloj?.text = String.format("%02d:%02d:%02d", h, m, s)
            txtReloj?.textSize = 26f

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

        // 3. Mostrar confirmación inicial durante 6s al arrancar el servicio
        if (ahoraMs < diagnosticoHastaMillis) {
            view.visibility = View.VISIBLE
        } else {
            view.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(bucleVigilancia)
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
