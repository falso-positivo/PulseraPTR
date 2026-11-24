package com.example.reloj.fall

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado de la alerta de caída en curso. Lo maneja [FallDetectionService]; la UI solo lo observa. */
sealed class FallAlertState {
    data object Inactivo : FallAlertState()
    data class CuentaRegresiva(val segundosRestantes: Int, val candidato: FallCandidate) : FallAlertState()
}

/** Bus en memoria entre el servicio, la pantalla de alerta y el historial de MainActivity. */
object FallAlertBus {
    private val _estado = MutableStateFlow<FallAlertState>(FallAlertState.Inactivo)
    val estado: StateFlow<FallAlertState> = _estado.asStateFlow()

    private val _historial = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val historial: SharedFlow<String> = _historial.asSharedFlow()

    internal fun setEstado(nuevo: FallAlertState) {
        _estado.value = nuevo
    }

    fun log(linea: String) {
        _historial.tryEmit(linea)
    }
}

/** Preferencia persistente: si el usuario dejó activada la detección de caídas. */
object FallPrefs {
    private const val PREFS = "pulsera_fall_prefs"
    private const val KEY_ENABLED = "fall_detection_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
