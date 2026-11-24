package com.example.reloj.fall

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Pantalla roja de confirmación. Es solo una vista del [FallAlertBus.estado]:
 * la cuenta regresiva y el envío los maneja [FallDetectionService].
 */
class FallAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mostrarSobrePantallaBloqueada()

        setContent {
            MaterialTheme {
                val estado by FallAlertBus.estado.collectAsState()
                LaunchedEffect(estado) {
                    if (estado !is FallAlertState.CuentaRegresiva) finish()
                }
                (estado as? FallAlertState.CuentaRegresiva)?.let { cuenta ->
                    PantallaAlertaCaida(
                        segundos = cuenta.segundosRestantes,
                        picoG = cuenta.candidato.peakG,
                        onEstoyBien = { FallDetectionService.cancelar(this) },
                        onPedirAyuda = { FallDetectionService.confirmar(this) },
                    )
                }
            }
        }
    }

    private fun mostrarSobrePantallaBloqueada() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun PantallaAlertaCaida(
    segundos: Int,
    picoG: Double,
    onEstoyBien: () -> Unit,
    onPedirAyuda: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFB71C1C))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("¿Te caíste?", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Detectamos una posible caída (impacto de ${String.format(Locale.US, "%.1f", picoG)} g).\n" +
                "Si no respondés, avisamos a tu contacto de emergencia.",
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Text("$segundos", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold)
        Text("segundos", color = Color.White)
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onEstoyBien,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1B5E20)),
        ) {
            Text("ESTOY BIEN", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onPedirAyuda,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        ) {
            Text("PEDIR AYUDA YA", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}
