package com.salajuegos.ejocoverlay

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(48, 48, 48, 48)
        }

        val titulo = TextView(this).apply {
            text = "🎮 EjocOverlay TV"
            textSize = 24f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
        }

        val estado = TextView(this).apply {
            val tieneOverlay = Settings.canDrawOverlays(this@MainActivity)
            text = if (tieneOverlay) {
                "✅ Permiso flotante activo.\nEl servicio ya está corriendo en segundo plano."
            } else {
                "⚠️ Falta permiso 'Mostrar sobre otras apps'.\nActívalo por ADB o con el botón de abajo."
            }
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 32)
        }

        val btnPermiso = Button(this).apply {
            text = "Abrir ajustes de permiso (Opcional)"
            setOnClickListener {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Este TV requiere activar el permiso por ADB", Toast.LENGTH_LONG).show()
                }
            }
        }

        val btnIniciar = Button(this).apply {
            text = "Iniciar Servicio y Cerrar"
            setOnClickListener {
                iniciarServicio()
                finish()
            }
        }

        root.addView(titulo)
        root.addView(estado)
        if (!Settings.canDrawOverlays(this)) {
            root.addView(btnPermiso)
        }
        root.addView(btnIniciar)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 102)
        }

        // Si ya tiene el permiso flotante (por ejemplo dado vía ADB), arranca directo sin pedir nada
        if (Settings.canDrawOverlays(this)) {
            iniciarServicio()
            finish()
        }
    }

    private fun iniciarServicio() {
        val svc = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
        Toast.makeText(this, "EjocOverlay activo en segundo plano", Toast.LENGTH_SHORT).show()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val svc = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        }
    }
}
