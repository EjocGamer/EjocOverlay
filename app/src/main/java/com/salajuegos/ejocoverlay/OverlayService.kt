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

        Thread {
            val decodificado: Drawable? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(resources, fondoResId)
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
                    ContextCompat.getDrawable(applicationContext, fondoResId)
                }
            } catch (_: Exception) {
                ContextCompat.getDrawable(applicationContext, fondoResId)
            }

            handler.post {
                fondoDrawable = decodificado
                preparandoFondo = false
                if (decodificado != null) {
                    imgFondoBloqueo?.setImageDrawable(decodificado)
                    if (bloqueoPantallaActivo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && decodificado is AnimatedImageDrawable) {
                        try {
                            decodificado.start()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }.start()
    }

    private fun asegurarAnimacionActiva() {
        val d = imgFondoBloqueo?.drawable ?: fondoDrawable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable) {
            try {
                d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                if (!d.isRunning) {
                    d.start()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun activarBloqueoPantalla() {
        intentarCrearVentanas()
        val bloqueoView = rootBloqueoView ?: return
        bloqueoPantallaActivo = true

        if (fondoDrawable == null && !preparandoFondo) {
            prepararGifUnaVez()
        } else if (fondoDrawable != null && imgFondoBloqueo?.drawable == null) {
            imgFondoBloqueo?.setImageDrawable(fondoDrawable)
        }

        imgFondoBloqueo?.alpha = 1f
        bloqueoView.visibility = View.VISIBLE
        imgFondoBloqueo?.visibility = View.VISIBLE
        tarjetaOverlayView?.visibility = View.GONE
        silenciarAudioTv(true)

        imgFondoBloqueo?.post {
            if (bloqueoPantallaActivo) {
                asegurarAnimacionActiva()
                imgFondoBloqueo?.invalidate()
            }
        }
    }
