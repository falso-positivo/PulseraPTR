package com.example.reloj.fall

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pulseraptr.network.FailureKind
import com.pulseraptr.network.NetworkingConfig
import com.pulseraptr.network.PulseraRemoteRepository
import com.pulseraptr.network.SyncOutcome
import com.pulseraptr.network.dto.EstadoClinico
import com.pulseraptr.network.dto.EventoSosRequest
import com.pulseraptr.network.dto.TipoEventoEmergencia
import java.util.concurrent.TimeUnit

/**
 * Envía el evento CAIDA al backend. Se encola con WorkManager para que, si no hay red
 * o el backend está caído, se reintente solo y la alerta no se pierda.
 */
class FallEventoWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val fechaHora = inputData.getString(KEY_FECHA_HORA) ?: return Result.failure()
        val mensaje = inputData.getString(KEY_MENSAJE) ?: "Caída detectada"

        val request = EventoSosRequest(
            pacienteId = NetworkingConfig.DEFAULT_PACIENTE_ID,
            fechaHora = fechaHora,
            tipoEvento = TipoEventoEmergencia.CAIDA,
            estado = EstadoClinico.CRITICO,
            mensaje = mensaje,
        )

        return when (val outcome = PulseraRemoteRepository().enviarCaida(request)) {
            is SyncOutcome.Success -> {
                FallAlertBus.log(outcome.lineaHistorial)
                Result.success()
            }
            is SyncOutcome.Failure -> {
                FallAlertBus.log(outcome.lineaHistorial)
                when (outcome.kind) {
                    // Errores de configuración: reintentar no sirve.
                    FailureKind.INVALID_URL -> Result.failure()
                    // Red, timeout, 5xx, etc.: reintentar con backoff.
                    else -> if (runAttemptCount < MAX_INTENTOS) Result.retry() else Result.failure()
                }
            }
        }
    }

    companion object {
        private const val KEY_FECHA_HORA = "fechaHora"
        private const val KEY_MENSAJE = "mensaje"
        private const val MAX_INTENTOS = 20

        fun encolar(context: Context, fechaHora: String, mensaje: String) {
            val request = OneTimeWorkRequestBuilder<FallEventoWorker>()
                .setInputData(workDataOf(KEY_FECHA_HORA to fechaHora, KEY_MENSAJE to mensaje))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .addTag("pulsera_caida")
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
