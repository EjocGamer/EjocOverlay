package com.salajuegos.ejocoverlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.File

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: LinearLayout? = null
    private var imgFondoBloqueo: ImageView? = null
    private var tarjetaBloqueo: LinearLayout? = null
    private var imgLogo: ImageView? = null
    private var txtMensaje: TextView? = null
    private var txtReloj: TextView? = null
    private var txtSubBloqueo: TextView? = null

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
    private var ultimaRutaFondoCargada: String? = null

    private val DURACION_TIMER_MS = 5000L  // 5 segundos visible al agregar tiempo
    private val DURACION_LIBRE_MS = 10000L // 10 segundos visible al iniciar Tiempo Libre

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
        intentarCrearVentana()
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        handler.post(bucleVigilancia)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intentarCrearVentana()

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

    private fun buscarArchivoMedio(nombresBase: List<String>, extensiones: List<String>): File? {
        val carpetas = listOf("/data/local/tmp", "/sdcard/Download")
        for (carpeta in carpetas) {
            for (nombre in nombresBase) {
                for (ext in extensiones) {
                    val f = File("$carpeta/$nombre.$ext")
                    if (f.exists() && f.canRead() && f.length() > 0L) {
                        return f
                    }
                }
            }
        }
        return null
    }

    private fun cargarDrawableDesdeArchivo(archivo: File): Drawable? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(archivo)
                ImageDecoder.decodeDrawable(source)
            } else {
                val bitmap: Bitmap? = BitmapFactory.decodeFile(archivo.absolutePath)
                bitmap?.let { BitmapDrawable(resources, it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun aplicarFondoDinamicoBloqueo() {
        val fondoView = imgFondoBloqueo ?: return
        val archivoFondo = buscarArchivoMedio(
            nombresBase = listOf("fondo_bloqueo", "fondo_ejoc", "fondo"),
            extensiones = listOf("gif", "webp", "png", "jpg", "jpeg")
        )

        if (archivoFondo != null) {
            val clave = "${archivoFondo.absolutePath}_${archivoFondo.lastModified()}"
            if (clave != ultimaRutaFondoCargada) {
                val drawable = cargarDrawableDesdeArchivo(archivoFondo)
                if (drawable != null) {
                    fondoView.setImageDrawable(drawable)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is android.graphics.drawable.AnimatedImageDrawable) {
                        drawable.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
                        drawable.start()
                    }
                    ultimaRutaFondoCargada = clave
                }
            }
            fondoView.visibility = View.VISIBLE
            overlayView?.background = Color.parseColor("#66050A07").let {
                GradientDrawable().apply { setColor(it) }
            }
        } else {
            ultimaRutaFondoCargada = null
            fondoView.setImageDrawable(null)
            fondoView.visibility = View.GONE
            overlayView?.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#FF062414"),
                    Color.parseColor("#FF07130D"),
                    Color.parseColor("#FF040705")
                )
            )
        }
    }

    private fun aplicarLogoDinamico() {
        val logoView = imgLogo ?: return
        val archivoLogo = buscarArchivoMedio(
            nombresBase = listOf("logo_ejoc", "logo_bloqueo", "logo"),
            extensiones = listOf("png", "webp", "jpg", "jpeg")
        )

        if (archivoLogo != null) {
            val drawable = cargarDrawableDesdeArchivo(archivoLogo)
            if (drawable != null) {
                logoView.setImageDrawable(drawable)
                logoView.visibility = View.VISIBLE
                return
            }
        }

        val logoResId = resources.getIdentifier("logo_ejoc", "drawable", packageName)
        if (logoResId != 0) {
            logoView.setImageResource(logoResId)
            logoView.visibility = View.VISIBLE
        } else {
            logoView.visibility = View.GONE
        }
    }

    private fun activarBloqueoPantalla() {
        intentarCrearVentana()
        val view = overlayView ?: return
        bloqueoPantallaActivo = true

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        view.gravity = Gravity.CENTER
        view.setPadding(dp(32), dp(24), dp(32), dp(24))

        aplicarFondoDinamicoBloqueo()
        aplicarLogoDinamico()

        tarjetaBloqueo?.apply {
            background = null
            setPadding(0, dp(12), 0, 0)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        txtMensaje?.apply {
            text = "EJOC GAME'S"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            visibility = if (imgLogo?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        txtReloj?.apply {
            text = "00:00:00"
            textSize = 34f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, 0)
            visibility = View.VISIBLE
        }

        txtSubBloqueo?.visibility = View.GONE

        val tipoVentana = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

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

        try {
            windowManager.updateViewLayout(view, paramsFull)
        } catch (_: Exception) {
        }
        view.visibility = View.VISIBLE
        silenciarAudioTv(true)
    }

    private fun desactivarBloqueoPantalla() {
        if (!bloqueoPantallaActivo && !audioSilenciadoPorBloqueo) return
        bloqueoPantallaActivo = false
        silenciarAudioTv(false)

        val view = overlayView ?: return
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        view.gravity = Gravity.START
        view.setPadding(dp(18), dp(12), dp(20), dp(12))

        imgFondoBloqueo?.visibility = View.GONE
        imgLogo?.visibility = View.GONE
        txtSubBloqueo?.visibility = View.GONE

        tarjetaBloqueo?.apply {
            background = null
            setPadding(0, 0, 0, 0)
            gravity = Gravity.START
        }

        txtMensaje?.apply {
            textSize = 14f
            gravity = Gravity.START
            visibility = View.VISIBLE
        }
        txtReloj?.apply {
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START
            setPadding(0, 0, 0, 0)
        }

        val tipoVentana = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

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

        try {
            windowManager.updateViewLayout(view, paramsEsquina)
        } catch (_: Exception) {
        }
        view.visibility = View.GONE
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

    private fun intentarCrearVentana() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) return

        try {
            val ctx = applicationContext
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val fondo = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(Color.parseColor("#F20F172A"))
                setStroke((2f * density).toInt(), Color.parseColor("#38BDF8"))
            }

            overlayView = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = fondo
                setPadding(dp(18), dp(12), dp(20), dp(12))
                visibility = View.VISIBLE
            }

            imgFondoBloqueo = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                visibility = View.GONE
            }

            imgLogo = ImageView(ctx).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(340), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setMargins(0, 0, 0, dp(12))
                }
                visibility = View.GONE
            }

            tarjetaBloqueo = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
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

            txtSubBloqueo = TextView(ctx).apply {
                text = ""
                textSize = 18f
                setTextColor(Color.parseColor("#94A3B8"))
                visibility = View.GONE
            }

            tarjetaBloqueo?.addView(txtMensaje)
            tarjetaBloqueo?.addView(txtReloj)
            tarjetaBloqueo?.addView(txtSubBloqueo)

            overlayView?.addView(imgFondoBloqueo)
            overlayView?.addView(imgLogo)
            overlayView?.addView(tarjetaBloqueo)

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
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(32)
                y = dp(32)
            }

            windowManager.addView(overlayView, params)
            diagnosticoHastaMillis = System.currentTimeMillis() + 6000L
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                applicationContext,
                "Error Overlay: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
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
            setColor(Color.parseColor("#F20F172A"))
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
                view.visibility = View.VISIBLE
            } else {
                if (inicioLibre != ultimoLibreRegistrado) {
                    ultimoLibreRegistrado = inicioLibre
                    ocultarEnMillis = ahoraMs + DURACION_LIBRE_MS
                }
                txtMensaje?.text = "⏱️ Tiempo libre activo:"
                actualizarEstiloCaja("#38BDF8", "#7DD3FC", "#FFFFFF")
                view.visibility = if (ahoraMs < ocultarEnMillis) View.VISIBLE else View.GONE
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
                view.visibility = View.VISIBLE
            } else {
                actualizarEstiloCaja("#22C55E", "#86EFAC", "#FFFFFF")
                view.visibility = if (ahoraMs < ocultarEnMillis) View.VISIBLE else View.GONE
            }
            return
        } else {
            ultimoFinRegistrado = 0L
        }

        // 3. Mantener visible la pantalla de bloqueo si está activa
        if (bloqueoPantallaActivo) {
            aplicarFondoDinamicoBloqueo()
            view.visibility = View.VISIBLE
            return
        }

        // 4. Cartel inicial de diagnóstico (6 segundos al iniciar)
        if (ahoraMs < diagnosticoHastaMillis) {
            view.visibility = View.VISIBLE
        } else {
            view.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        silenciarAudioTv(false)
        handler.removeCallbacks(bucleVigilancia)
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
