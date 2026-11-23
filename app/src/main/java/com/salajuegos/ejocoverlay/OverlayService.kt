package com.salajuegos.ejocoverlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var rootBloqueoView: FrameLayout? = null
    private var imgFondoBloqueo: ImageView? = null

    private var tarjetaOverlayView: LinearLayout? = null
    private var txtMensaje: TextView? = null
    private var txtReloj: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private var ultimoFinRegistrado: Long = 0L
    private var ultimoLibreRegistrado: Long = 0L
    private var ocultarEnMillis: Long = 0L
    private var diagnosticoHastaMillis: Long = 0L

    // Estados en memoria: "TIMER", "LIBRE", "ESPERA", "NONE"
    private var memModo: String = "NONE"
    private var memFin: Long = 0L
    private var memInicioLibre: Long = 0L

    // Control de bloqueo por encendido físico sin tiempo
    private var bloqueoPantallaActivo: Boolean = false
    private var autorizadoHastaMillis: Long = 0L
    private var audioSilenciadoPorBloqueo: Boolean = false

    private val DURACION_TIMER_MS = 5000L   // 5 segundos visible al agregar tiempo
    private val DURACION_LIBRE_MS = 10000L  // 10 segundos visible al iniciar Tiempo Libre
    private val INTERVALO_VIGILANCIA_MS = 2000L // 2 segundos para reducir carga continua en TV

    private var fondoDrawable: Drawable? = null
    @Volatile private var preparandoFondo: Boolean = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                evaluarEncendidoFisico()
            }
        }
    }

    private val bucleVigilancia = object : Runnable {
        override fun run() {
            verificarArchivosEstado()
            handler.postDelayed(this, INTERVALO_VIGILANCIA_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        iniciarNotificacionServicio()
        intentarCrearVentanas()
        prepararGifUnaVez()
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        handler.post(bucleVigilancia)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intentarCrearVentanas()
        if (fondoDrawable == null && !preparandoFondo) {
            prepararGifUnaVez()
        }

        val accion = intent?.getStringExtra("accion")
        val valorTs = intent?.getLongExtra("ts", 0L) ?: 0L
        if (accion != null) {
            when (accion) {
                "TIMER" -> {
                    desactivarBloqueoPantalla()
                    memModo = "TIMER"
                    memFin = valorTs
                    memInicioLibre = 0L
                    ultimoLibreRegistrado = 0L
                }
                "LIBRE" -> {
                    desactivarBloqueoPantalla()
                    memModo = "LIBRE"
                    memInicioLibre = valorTs
                    memFin = 0L
                    ultimoFinRegistrado = 0L
                    ultimoLibreRegistrado = valorTs
                    ocultarEnMillis = System.currentTimeMillis() + DURACION_LIBRE_MS
                }
                "ESPERA" -> {
                    desactivarBloqueoPantalla()
                    memModo = "ESPERA"
                    memInicioLibre = valorTs
                    memFin = 0L
                    ultimoFinRegistrado = 0L
                }
                "PERMITIR" -> {
                    // Ventana de gracia de 20s al encender el TV desde la app del celular
                    autorizadoHastaMillis = System.currentTimeMillis() + 20000L
                    desactivarBloqueoPantalla()
                }
                "CLEAR" -> {
                    desactivarBloqueoPantalla()
                    memModo = "NONE"
                    memFin = 0L
                    memInicioLibre = 0L
                    ultimoFinRegistrado = 0L
                    ultimoLibreRegistrado = 0L
                }
            }
        }

        handler.removeCallbacks(bucleVigilancia)
        handler.post(bucleVigilancia)
        return START_STICKY
    }

    private fun tieneSesionActiva(): Boolean {
        val ahoraSeg = System.currentTimeMillis() / 1000L

        var inicioLibre = leerTimestampLocal("sala_libre.txt")
        if (inicioLibre == 0L && (memModo == "LIBRE" || memModo == "ESPERA")) {
            inicioLibre = memInicioLibre
        }
        if (inicioLibre > 1700000000L && ahoraSeg >= inicioLibre) return true

        var fin = leerTimestampLocal("sala_fin.txt")
        if (fin == 0L && memModo == "TIMER") {
            fin = memFin
        }
        if (fin > 1700000000L && (fin - ahoraSeg) > 0) return true

        return false
    }

    private fun evaluarEncendidoFisico() {
        val ahoraMs = System.currentTimeMillis()
        if (ahoraMs < autorizadoHastaMillis) return
        if (tieneSesionActiva()) return

        activarBloqueoPantalla()
    }

    private fun prepararGifUnaVez() {
        if (fondoDrawable != null || preparandoFondo) return
        preparandoFondo = true

        val fondoResId = resources.getIdentifier("fondo_ejoc", "drawable", packageName)
        if (fondoResId == 0) {
            preparandoFondo = false
            return
        }

        val metrics = resources.displayMetrics
        val maxW = metrics.widthPixels.coerceAtLeast(1280)
        val maxH = metrics.heightPixels.coerceAtLeast(720)
        val ctx = applicationContext

        Thread {
            val decodificado: Drawable? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(ctx.resources, fondoResId)
                    ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                        val w = info.size.width
                        val h = info.size.height
                        if (w > maxW || h > maxH) {
                            val scale = minOf(maxW.toFloat() / w, maxH.toFloat() / h)
                            decoder.setTargetSize(
                                (w * scale).toInt().coerceAtLeast(1),
                                (h * scale).toInt().coerceAtLeast(1)
                            )
                        }
                    }.apply {
                        if (this is AnimatedImageDrawable) {
                            repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                        }
                    }
                } else {
                    ContextCompat.getDrawable(ctx, fondoResId)
                }
            } catch (_: Exception) {
                ContextCompat.getDrawable(ctx, fondoResId)
            }

            handler.post {
                fondoDrawable = decodificado
                preparandoFondo = false
                if (decodificado != null) {
                    imgFondoBloqueo?.setImageDrawable(decodificado)
                    if (bloqueoPantallaActivo) {
                        controlarAnimacion(true)
                    } else {
                        controlarAnimacion(false)
                    }
                }
            }
        }.start()
    }

    private fun controlarAnimacion(ejecutar: Boolean) {
        val d = imgFondoBloqueo?.drawable ?: fondoDrawable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable) {
            try {
                d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                if (ejecutar) {
                    if (!d.isRunning) d.start()
                } else {
                    if (d.isRunning) d.stop()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun activarBloqueoPantalla() {
        intentarCrearVentanas()
        val bloqueoView = rootBloqueoView ?: return
        bloqueoPantallaActivo = true

        if (fondoDrawable != null) {
            if (imgFondoBloqueo?.drawable !== fondoDrawable) {
                imgFondoBloqueo?.setImageDrawable(fondoDrawable)
            }
        } else if (!preparandoFondo) {
            prepararGifUnaVez()
        }

        bloqueoView.visibility = View.VISIBLE
        imgFondoBloqueo?.visibility = View.VISIBLE
        tarjetaOverlayView?.visibility = View.GONE
        silenciarAudioTv(true)
        controlarAnimacion(true)
    }

    private fun desactivarBloqueoPantalla() {
        if (!bloqueoPantallaActivo && !audioSilenciadoPorBloqueo) return
        bloqueoPantallaActivo = false
        controlarAnimacion(false)
        silenciarAudioTv(false)
        rootBloqueoView?.visibility = View.GONE
    }

    private fun silenciarAudioTv(silenciar: Boolean) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (silenciar && !audioSilenciadoPorBloqueo) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                audioSilenciadoPorBloqueo = true
            } else if (!silenciar && audioSilenciadoPorBloqueo) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                audioSilenciadoPorBloqueo = false
            }
        } catch (_: Exception) {
        }
    }

    private fun intentarCrearVentanas() {
        if (!Settings.canDrawOverlays(this)) return

        val tipoVentana = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val ctx = applicationContext
        if (!::windowManager.isInitialized) {
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // 1. Pantalla completa de bloqueo
        if (rootBloqueoView == null) {
            try {
                val frame = FrameLayout(ctx).apply {
                    setBackgroundColor(Color.BLACK)
                    visibility = View.GONE
                }

                val fondoImg = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    if (fondoDrawable != null) {
                        setImageDrawable(fondoDrawable)
                    }
                    visibility = View.VISIBLE
                }
                imgFondoBloqueo = fondoImg
                frame.addView(fondoImg)

                val paramsFull = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    tipoVentana,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                windowManager.addView(frame, paramsFull)
                rootBloqueoView = frame
            } catch (_: Exception) {
                rootBloqueoView = null
            }
        }

        // 2. Tarjeta compacta de avisos y tiempos en esquina superior derecha
        if (tarjetaOverlayView == null) {
            try {
                val fondo = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 14f * density
                    setColor(Color.parseColor("#F20F172A"))
                    setStroke((2f * density).toInt(), Color.parseColor("#38BDF8"))
                }

                tarjetaOverlayView = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = fondo
                    setPadding(dp(18), dp(12), dp(20), dp(12))
                    visibility = View.VISIBLE
                }

                txtMensaje = TextView(ctx).apply {
                    text = "✅ EjocOverlay conectado"
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#38BDF8"))
                }

                txtReloj = TextView(ctx).apply {
                    text = "Esperando tiempo..."
                    textSize = 22f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }

                tarjetaOverlayView?.addView(txtMensaje)
                tarjetaOverlayView?.addView(txtReloj)

                val paramsEsquina = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    tipoVentana,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = dp(32)
                    y = dp(32)
                }

                windowManager.addView(tarjetaOverlayView, paramsEsquina)
                diagnosticoHastaMillis = System.currentTimeMillis() + 6000L
            } catch (_: Exception) {
                tarjetaOverlayView = null
            }
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
            setColor(Color.parseColor("#F20F172A"))
            setStroke((2f * density).toInt(), Color.parseColor(bordeHex))
        }
        tarjetaOverlayView?.background = fondo
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
        intentarCrearVentanas()
        val tarjeta = tarjetaOverlayView ?: return
        val ahoraSeg = System.currentTimeMillis() / 1000L
        val ahoraMs = System.currentTimeMillis()

        // 1. Verificar Tiempo Libre (10 segundos) o Ventana de Espera (Fijo)
        var inicioLibre = leerTimestampLocal("sala_libre.txt")
        if (inicioLibre == 0L && (memModo == "LIBRE" || memModo == "ESPERA")) {
            inicioLibre = memInicioLibre
        }

        if (inicioLibre > 1700000000L && ahoraSeg >= inicioLibre) {
            if (bloqueoPantallaActivo) desactivarBloqueoPantalla()
            ultimoFinRegistrado = 0L
            val transcurrido = (ahoraSeg - inicioLibre).toInt()
            val h = transcurrido / 3600
            val m = (transcurrido % 3600) / 60
            val s = transcurrido % 60

            txtReloj?.text = String.format("%02d:%02d:%02d", h, m, s)
            txtReloj?.textSize = 26f

            if (memModo == "ESPERA") {
                txtMensaje?.text = "🛡️ Tiempo finalizado (En espera):"
                actualizarEstiloCaja("#F59E0B", "#FCD34D", "#FFFFFF")
                tarjeta.visibility = View.VISIBLE
            } else {
                if (inicioLibre != ultimoLibreRegistrado) {
                    ultimoLibreRegistrado = inicioLibre
                    ocultarEnMillis = ahoraMs + DURACION_LIBRE_MS
                }
                txtMensaje?.text = "⏱️ Tiempo libre activo:"
                actualizarEstiloCaja("#38BDF8", "#7DD3FC", "#FFFFFF")
                tarjeta.visibility = if (ahoraMs < ocultarEnMillis) View.VISIBLE else View.GONE
            }
            return
        } else {
            ultimoLibreRegistrado = 0L
        }

        // 2. Verificar Temporizador normal (5s al agregar + permanente en alerta 2 min)
        var fin = leerTimestampLocal("sala_fin.txt")
        if (fin == 0L && memModo == "TIMER") {
            fin = memFin
        }

        val restante = (fin - ahoraSeg).toInt()
        if (fin > 1700000000L && restante > 0) {
            if (bloqueoPantallaActivo) desactivarBloqueoPantalla()
            if (fin != ultimoFinRegistrado) {
                ultimoFinRegistrado = fin
                ocultarEnMillis = ahoraMs + DURACION_TIMER_MS
            }

            val h = restante / 3600
            val m = (restante % 3600) / 60
            val s = restante % 60

            txtMensaje?.text = "⏳ El TV se apagará en:"
            txtReloj?.text = String.format("%02d:%02d:%02d", h, m, s)
            txtReloj?.textSize = 26f

            if (restante <= 120) {
                actualizarEstiloCaja("#EF4444", "#FCA5A5", "#FACC15")
                tarjeta.visibility = View.VISIBLE
            } else {
                actualizarEstiloCaja("#22C55E", "#86EFAC", "#FFFFFF")
                tarjeta.visibility = if (ahoraMs < ocultarEnMillis) View.VISIBLE else View.GONE
            }
            return
        } else {
            ultimoFinRegistrado = 0L
        }

        // 3. Mantener visible la pantalla de bloqueo si está activa
        if (bloqueoPantallaActivo) {
            rootBloqueoView?.visibility = View.VISIBLE
            tarjeta.visibility = View.GONE
            controlarAnimacion(true)
            return
        }

        // 4. Cartel inicial de conexión (6 segundos al iniciar)
        if (ahoraMs < diagnosticoHastaMillis) {
            tarjeta.visibility = View.VISIBLE
        } else {
            tarjeta.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        silenciarAudioTv(false)
        handler.removeCallbacks(bucleVigilancia)
        controlarAnimacion(false)
        fondoDrawable = null
        rootBloqueoView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        tarjetaOverlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
