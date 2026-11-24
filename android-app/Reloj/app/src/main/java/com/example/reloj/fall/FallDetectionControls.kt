package com.example.reloj.fall

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Bloque de UI para MainActivity: switch "Detección de caídas" + botón de simulación (solo debug).
 * También vuelca en [historial] las líneas que publica el servicio por [FallAlertBus].
 */
@Composable
fun SeccionDeteccionCaidas(historial: MutableList<String>) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var activa by remember { mutableStateOf(FallPrefs.isEnabled(appContext)) }
    val esDebug = remember {
        (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    fun activar() {
        FallPrefs.setEnabled(appContext, true)
        activa = true
        FallDetectionService.iniciar(appContext)
    }

    // Android 13+: sin permiso de notificaciones no se ve la cuenta regresiva fuera de la app.
    val permisoNotificaciones = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (!concedido) {
            historial.add(0, "Aviso: sin permiso de notificaciones la alerta de caída solo se verá con la app abierta")
        }
        activar()
    }

    LaunchedEffect(Unit) {
        // Si quedó activada de una sesión anterior, asegurar que el servicio esté corriendo.
        if (FallPrefs.isEnabled(appContext)) FallDetectionService.iniciar(appContext)
        FallAlertBus.historial.collect { historial.add(0, it) }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Detección de caídas", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Usa el acelerómetro del celular. Llevalo encima (bolsillo o riñonera).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = activa,
                onCheckedChange = { nuevo ->
                    if (nuevo) {
                        val necesitaPermiso = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        if (necesitaPermiso) {
                            permisoNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            activar()
                        }
                    } else {
                        FallPrefs.setEnabled(appContext, false)
                        activa = false
                        FallDetectionService.detener(appContext)
                    }
                },
            )
        }

        if (esDebug) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { FallDetectionService.simular(appContext) }) {
                Text("Simular caída (debug)")
            }
        }
    }
}
