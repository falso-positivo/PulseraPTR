package com.example.reloj.fall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Secuencias sintéticas de |a| (en g) muestreadas a 50 Hz (20 ms), como SENSOR_DELAY_GAME.
 */
class FallDetectorTest {

    private class Secuencia {
        val muestras = mutableListOf<Double>()
        fun constante(g: Double, ms: Int) = apply { repeat(ms / PASO_MS) { muestras += g } }
        fun valor(g: Double) = apply { muestras += g }
        fun caminar(ms: Int) = apply {
            // Oscilación 0.7–1.3 g a ~2 pasos/s.
            repeat(ms / PASO_MS) { i -> muestras += 1.0 + 0.3 * sin(2 * PI * 2.0 * i * PASO_MS / 1000.0) }
        }
    }

    private fun correr(seq: Secuencia, config: FallDetectorConfig = FallDetectorConfig()): List<FallCandidate> {
        val detectadas = mutableListOf<FallCandidate>()
        val detector = FallDetector(config) { detectadas += it }
        seq.muestras.forEachIndexed { i, g -> detector.ingestMagnitudeG(i * PASO_MS * 1_000_000L, g) }
        return detectadas
    }

    @Test
    fun `caida real - caida libre, impacto y reposo - se detecta`() {
        val seq = Secuencia()
            .caminar(2_000)
            .constante(0.2, 300)   // caída libre
            .valor(3.5)            // impacto
            .valor(2.0)            // rebote
            .constante(1.0, 5_000) // persona quieta en el piso

        val r = correr(seq)

        assertEquals(1, r.size)
        assertEquals(3.5, r[0].peakG, 0.001)
        assertTrue("caída libre medida: ${r[0].freeFallMs}", r[0].freeFallMs >= 260)
        assertTrue(r[0].postImpactStdG < 0.15)
    }

    @Test
    fun `caminar no dispara`() {
        assertTrue(correr(Secuencia().caminar(30_000)).isEmpty())
    }

    @Test
    fun `sentarse bruscamente - impacto sin caida libre - no dispara`() {
        val seq = Secuencia()
            .constante(1.0, 1_000)
            .constante(0.7, 200)
            .valor(1.8)
            .constante(1.0, 5_000)
        assertTrue(correr(seq).isEmpty())
    }

    @Test
    fun `salto - caida libre e impacto pero sigue en movimiento - no dispara`() {
        val seq = Secuencia()
            .constante(1.0, 1_000)
            .constante(0.1, 300)
            .valor(3.0)
            .caminar(5_000) // se levanta y sigue caminando
        assertTrue(correr(seq).isEmpty())
    }

    @Test
    fun `caida libre muy corta - vibracion - no dispara`() {
        val seq = Secuencia()
            .constante(1.0, 1_000)
            .constante(0.3, 40) // 2 muestras: menos de 60 ms
            .valor(3.0)
            .constante(1.0, 5_000)
        assertTrue(correr(seq).isEmpty())
    }

    @Test
    fun `impacto fuera de ventana - no dispara`() {
        val seq = Secuencia()
            .constante(1.0, 1_000)
            .constante(0.2, 200)
            .constante(1.0, 1_500) // más que impactWindowMs sin impacto
            .valor(3.0)
            .constante(1.0, 5_000)
        assertTrue(correr(seq).isEmpty())
    }

    @Test
    fun `periodo refractario - dos caidas seguidas emiten una sola alerta`() {
        val caida = { s: Secuencia -> s.constante(0.2, 300).valor(3.5).constante(1.0, 5_000) }
        val seq = Secuencia().constante(1.0, 1_000)
        caida(seq)
        caida(seq) // dentro de los 30 s refractarios

        assertEquals(1, correr(seq).size)
    }

    @Test
    fun `tras el periodo refractario detecta otra caida`() {
        val caida = { s: Secuencia -> s.constante(0.2, 300).valor(3.5).constante(1.0, 5_000) }
        val seq = Secuencia().constante(1.0, 1_000)
        caida(seq)
        seq.constante(1.0, 31_000)
        caida(seq)

        assertEquals(2, correr(seq).size)
    }

    @Test
    fun `ingest convierte m por s2 a g`() {
        val detectadas = mutableListOf<FallCandidate>()
        val detector = FallDetector { detectadas += it }
        var t = 0L
        fun muestra(g: Double) {
            detector.ingest(t, 0f, 0f, (g * 9.80665).toFloat())
            t += PASO_MS * 1_000_000L
        }
        repeat(50) { muestra(1.0) }
        repeat(15) { muestra(0.2) }
        muestra(3.5)
        repeat(250) { muestra(1.0) }

        assertEquals(1, detectadas.size)
    }

    private companion object {
        const val PASO_MS = 20
    }
}
