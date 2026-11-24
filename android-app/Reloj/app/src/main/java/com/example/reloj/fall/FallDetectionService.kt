package com.example.reloj.fall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.reloj.MainActivity
import com.pulseraptr.network.BackendTime
import java.util.Locale

/**
 * Servicio en primer plano que:
 *  1. lee el acelerómetro y alimenta el [FallDetector];
 *  2. ante una caída candidata, inicia una cuenta regresiva de [SEGUNDOS_CONFIRMACION] s
 *     con alarma + vibración + notificación (con acciones "Estoy bien" / "Pedir ayuda");
 *  3. si nadie cancela, encola el envío del evento CAIDA con [FallEventoWorker].
 *
 * La cuenta regresiva vive acá (no en la Activity): en Android 14+ la pantalla completa
 * puede no abrirse sola, y la alerta igual tiene que salir.
 */
class FallDetectionService : Service(), SensorEventListener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sensorManager: SensorManager
    private var acelerometro: Sensor? = null
    private var monitoreando = false
    private var wakeLock: PowerManager.WakeLock? = null

    private var ringtone: Ringtone? = null
    private var candidatoActual: FallCandidate? = null
    private var segundosRestantes = 0
    /** Hora de pared (epoch ms) del impacto detectado. */
    private var impactoEpochMs = 0L

    private val detector = FallDetector(onFall = ::onCaidaCandidata)

    private val tick = object : Runnable {
        override fun run() {
            segundosRestantes--
            if (segundosRestantes <= 0) {
                enviarAlerta(motivo = "sin respuesta en $SEGUNDOS_CONFIRMACION s")
            } else {
                publicarCuentaRegresiva()
                handler.postDelayed(this, 1_000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        acelerometro = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        crearCanales()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Siempre pasar a primer plano primero (obligatorio tras startForegroundService).
        iniciarPrimerPlano()

        when (intent?.action) {
            ACTION_STOP -> detenerMonitoreo()
            ACTION_CANCELAR -> cancelarAlerta()
            ACTION_CONFIRMAR -> enviarAlerta(motivo = "confirmada por el usuario")
            ACTION_SIMULAR -> onCaidaCandidata(
                FallCandidate(
                    impactTimestampNs = SystemClock.elapsedRealtimeNanos(),
                    peakG = 3.0,
                    freeFallMs = 300,
                    postImpactStdG = 0.02,
                )
            )
            ACTION_START -> iniciarMonitoreo()
            // Reinicio del sistema tras matar el proceso (START_STICKY entrega intent null).
            else -> if (FallPrefs.isEnabled(this)) iniciarMonitoreo()
        }
        // Una acción de notificación vieja (o un STOP) no debe dejar vivo un servicio sin trabajo.
        if (!monitoreando && candidatoActual == null) {
            detenerServicio()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        detenerMonitoreo()
        detenerAlarma()
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Sensor

    private fun iniciarMonitoreo() {
        if (monitoreando) return
        val sensor = acelerometro
        if (sensor == null) {
            FallAlertBus.log("${hora()} - ✗ Este dispositivo no tiene acelerómetro")
            return
        }
        detector.reiniciar()
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        // Con la pantalla apagada la CPU puede dormirse y perder muestras: mantenerla despierta.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PulseraPTR:FallDetection").apply {
            setReferenceCounted(false)
            acquire()
        }
        monitoreando = true
        FallAlertBus.log("${hora()} - Detección de caídas activada")
    }

    private fun detenerMonitoreo() {
        if (!monitoreando) return
        sensorManager.unregisterListener(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        monitoreando = false
        FallAlertBus.log("${hora()} - Detección de caídas desactivada")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (candidatoActual != null) return // ya hay una alerta en curso
        detector.ingest(event.timestamp, event.values[0], event.values[1], event.values[2])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---------------------------------------------------------------- Alerta

    private fun onCaidaCandidata(candidato: FallCandidate) {
        if (candidatoActual != null) return
        candidatoActual = candidato
        // SensorEvent.timestamp usa la base de elapsedRealtimeNanos: convertir a hora de pared.
        val atrasMs = (SystemClock.elapsedRealtimeNanos() - candidato.impactTimestampNs) / 1_000_000
        impactoEpochMs = System.currentTimeMillis() - atrasMs.coerceAtLeast(0)
        segundosRestantes = SEGUNDOS_CONFIRMACION

        Log.w(TAG, "Caída candidata: $candidato")
        FallAlertBus.log(
            "${hora()} - ⚠ Posible caída (pico ${fmt(candidato.peakG)} g). Esperando confirmación…"
        )

        iniciarAlarma()
        publicarCuentaRegresiva()
        abrirPantallaAlerta()
        handler.postDelayed(tick, 1_000)
    }

    private fun publicarCuentaRegresiva() {
        val c = candidatoActual ?: return
        FallAlertBus.setEstado(FallAlertState.CuentaRegresiva(segundosRestantes, c))
        if (puedeNotificar()) {
            notificationManager().notify(NOTIF_ALERTA_ID, construirNotificacionAlerta())
        }
    }

    private fun cancelarAlerta() {
        if (candidatoActual == null) return
        FallAlertBus.log("${hora()} - Caída descartada por el usuario (\"Estoy bien\")")
        finalizarAlerta()
    }

    private fun enviarAlerta(motivo: String) {
        val c = candidatoActual ?: return
        val mensaje = "Caída detectada automáticamente " +
            "(pico ${fmt(c.peakG)} g, caída libre ${c.freeFallMs} ms, $motivo)"
        FallEventoWorker.encolar(this, BackendTime.isoLocal(impactoEpochMs), mensaje)
        FallAlertBus.log("${hora()} - 🚨 Alerta de caída enviada ($motivo)")
        finalizarAlerta()
    }

    private fun finalizarAlerta() {
        handler.removeCallbacks(tick)
        detenerAlarma()
        candidatoActual = null
        notificationManager().cancel(NOTIF_ALERTA_ID)
        FallAlertBus.setEstado(FallAlertState.Inactivo)
        detector.reiniciar()
        if (!monitoreando) {
            // Se usó "Simular caída" con la detección apagada: no dejar el servicio vivo.
            detenerServicio()
        }
    }

    private fun abrirPantallaAlerta() {
        // Funciona si la app está en primer plano; si no, la notificación full-screen se encarga.
        try {
            startActivity(
                Intent(this, FallAlertActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo abrir FallAlertActivity directamente", e)
        }
    }

    // ---------------------------------------------------------------- Alarma

    private fun iniciarAlarma() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo reproducir la alarma", e)
        }
        vibrador()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
    }

    private fun detenerAlarma() {
        ringtone?.stop()
        ringtone = null
        vibrador()?.cancel()
    }

    private fun vibrador(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    // ---------------------------------------------------------------- Notificaciones

    private fun iniciarPrimerPlano() {
        val notif = NotificationCompat.Builder(this, CANAL_MONITOREO)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Detección de caídas activa")
            .setContentText("PulseraPTR está monitoreando el acelerómetro")
            .setOngoing(true)
            .setContentIntent(pendingActivity(MainActivity::class.java, 0))
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_MONITOREO_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIF_MONITOREO_ID, notif)
        }
    }

    private fun construirNotificacionAlerta(): Notification {
        val pantalla = pendingActivity(FallAlertActivity::class.java, 1)
        return NotificationCompat.Builder(this, CANAL_ALERTA)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("¿Te caíste?")
            .setContentText("Se enviará una alerta en $segundosRestantes s si no respondés")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setFullScreenIntent(pantalla, true)
            .setContentIntent(pantalla)
            .addAction(0, "Estoy bien", pendingService(ACTION_CANCELAR, 2))
            .addAction(0, "Pedir ayuda", pendingService(ACTION_CONFIRMAR, 3))
            .build()
    }

    private fun crearCanales() {
        val nm = notificationManager()
        nm.createNotificationChannel(
            NotificationChannel(CANAL_MONITOREO, "Monitoreo de caídas", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CANAL_ALERTA, "Alerta de caída", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Cuenta regresiva cuando se detecta una posible caída"
                setSound(null, null) // el sonido lo maneja el servicio (alarma en loop)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun pendingActivity(clazz: Class<*>, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            this,
            requestCode,
            Intent(this, clazz).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun pendingService(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this,
            requestCode,
            Intent(this, FallDetectionService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun puedeNotificar(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun detenerServicio() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hora(): String =
        java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)

    companion object {
        private const val TAG = "FallDetection"
        const val SEGUNDOS_CONFIRMACION = 30

        private const val CANAL_MONITOREO = "fall_monitoring"
        private const val CANAL_ALERTA = "fall_alert"
        private const val NOTIF_MONITOREO_ID = 4101
        private const val NOTIF_ALERTA_ID = 4102

        const val ACTION_START = "com.example.reloj.fall.START"
        const val ACTION_STOP = "com.example.reloj.fall.STOP"
        const val ACTION_CANCELAR = "com.example.reloj.fall.CANCELAR"
        const val ACTION_CONFIRMAR = "com.example.reloj.fall.CONFIRMAR"
        const val ACTION_SIMULAR = "com.example.reloj.fall.SIMULAR"

        fun iniciar(context: Context) = enviar(context, ACTION_START)
        fun detener(context: Context) = enviar(context, ACTION_STOP)
        fun simular(context: Context) = enviar(context, ACTION_SIMULAR)
        fun cancelar(context: Context) = enviar(context, ACTION_CANCELAR)
        fun confirmar(context: Context) = enviar(context, ACTION_CONFIRMAR)

        private fun enviar(context: Context, action: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FallDetectionService::class.java).setAction(action),
            )
        }
    }
}
