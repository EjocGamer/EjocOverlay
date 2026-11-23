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
    private var imgLogoBloqueo: ImageView? = null
    private var txtRelojBloqueo: TextView? = null

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

    private val DURACION_TIMER_MS = 5000L  // 5 segundos visible al agregar tiempo
    private val DURACION_LIBRE_MS = 10000L // 10 segundos visible al iniciar Tiempo Libre

    private var cachedFondoDrawable: Drawable? = null
    @Volatile private var cargandoFondo: Boolean = false

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
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        iniciarNotificacionServicio()
        precargarFondoEnSegundoPlano()
        intentarCrearVentanas()
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        handler.post(bucleVigilancia)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intentarCrearVentanas()

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

    private fun precargarFondoEnSegundoPlano(onListo: (() -> Unit)? = null) {
        if (cachedFondoDrawable != null) {
            onListo?.invoke()
            return
        }
        if (cargandoFondo) return
        cargandoFondo = true

        val fondoResId = resources.getIdentifier("fondo_ejoc", "drawable", packageName)
        if (fondoResId == 0) {
            cargandoFondo = false
            return
        }

        Thread {
            val decodificado: Drawable? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(resources, fondoResId)
                    ImageDecoder.decodeDrawable(source).apply {
                        if (this is AnimatedImageDrawable) {
                            repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                        }
                    }
                } else {
                    ContextCompat.getDrawable(applicationContext, fondoResId)
                }
            } catch (_: Exception) {
                ContextCompat.getDrawable(applicationContext, fondoResId)
            }

            handler.post {
                cachedFondoDrawable = decodificado
                cargandoFondo = false
                if (decodificado != null && imgFondoBloqueo != null) {
                    imgFondoBloqueo?.setImageDrawable(decodificado)
                    if (bloqueoPantallaActivo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && decodificado is AnimatedImageDrawable) {
                        decodificado.start()
                    }
                }
                onListo?.invoke()
            }
        }.start()
    }

    private fun activarBloqueoPantalla() {
        intentarCrearVentanas()
        val bloqueoView = rootBloqueoView ?: return
        bloqueoPantallaActivo = true

        // Base sólida oscura (~98-100%) para que no se transparente lo que hay detrás
        bloqueoView.alpha = 0.9f
        bloqueoView.setBackgroundColor(Color.parseColor("#FC030704"))

        imgFondoBloqueo?.alpha = 0.9f
        imgFondoBloqueo?.visibility = View.VISIBLE
        imgLogoBloqueo?.visibility = View.GONE
        txtRelojBloqueo?.visibility = View.GONE

        val drawable = cachedFondoDrawable
        if (drawable != null) {
            imgFondoBloqueo?.setImageDrawable(drawable)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) {
                drawable.stop()
                drawable.start()
            }
        } else {
            // Respaldo inmediato mientras termina la precarga si aún no estaba listo
            val fondoResId = resources.getIdentifier("fondo_ejoc", "drawable", packageName)
            if (fondoResId != 0 && imgFondoBloqueo?.drawable == null) {
                imgFondoBloqueo?.setImageResource(fondoResId)
            }
            precargarFondoEnSegundoPlano()
        }

        // Mostrar al instante sin esperar decodificación
        bloqueoView.visibility = View.VISIBLE
        tarjetaOverlayView?.visibility = View.GONE
        silenciarAudioTv(true)
    }

    private fun desactivarBloqueoPantalla() {
        if (!bloqueoPantallaActivo && !audioSilenciadoPorBloqueo) return
        bloqueoPantallaActivo = false
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

        // 1. Pantalla completa fija de bloqueo (GIF animado con alta opacidad)
        if (rootBloqueoView == null) {
            try {
                val frame = FrameLayout(ctx).apply {
                    setBackgroundColor(Color.parseColor("#FC030704"))
                    visibility = View.GONE
                }

                val fondoImg = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    alpha = 0.9f
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    val fondoResId = resources.getIdentifier("fondo_ejoc", "drawable", packageName)
                    val inicial = cachedFondoDrawable ?: if (fondoResId != 0) {
                        ContextCompat.getDrawable(ctx, fondoResId)
                    } else {
                        null
                    }
                    if (inicial != null) {
                        setImageDrawable(inicial)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && inicial is AnimatedImageDrawable) {
                            inicial.start()
                        }
                    }
                    visibility = View.VISIBLE
                }
                imgFondoBloqueo = fondoImg

                val columnaCentro = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                }

                val logoImg = ImageView(ctx).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(dp(420), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                }
                imgLogoBloqueo = logoImg

                val relojBloqueo = TextView(ctx).apply {
                    text = "00:00:00"
                    textSize = 36f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        setMargins(0, dp(10), 0, 0)
                    }
                }
                txtRelojBloqueo = relojBloqueo

                columnaCentro.addView(logoImg)
                columnaCentro.addView(relojBloqueo)

                frame.addView(fondoImg)
                frame.addView(columnaCentro)

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
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    applicationContext,
                    "Error Overlay: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
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
            return
        }

        // 4. Cartel inicial de diagnóstico (6 segundos al iniciar)
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
