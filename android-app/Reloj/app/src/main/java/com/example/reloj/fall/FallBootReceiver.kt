package com.example.reloj.fall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reactiva la detección de caídas tras reiniciar el teléfono, si el usuario la había dejado encendida. */
class FallBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && FallPrefs.isEnabled(context)) {
            FallDetectionService.iniciar(context)
        }
    }
}
