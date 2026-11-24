package com.example.reloj.fall

import kotlin.math.sqrt

/**
 * Umbrales del detector. Valores por defecto basados en el esquema clásico de 3 fases
 * (caída libre → impacto → inmovilidad) usado en la literatura de detección con acelerómetro.
 */
data class FallDetectorConfig(
    /** |a| por debajo de este valor (en g) se considera caída libre. */
    val freeFallThresholdG: Double = 0.5,
    /** Duración mínima de caída libre para considerarla (filtra vibraciones). */
    val minFreeFallMs: Long = 60,
    /** |a| por encima de este valor (en g) se considera impacto. */
    val impactThresholdG: Double = 2.5,
    /** Ventana desde el fin de la caída libre para que ocurra el impacto. */
    val impactWindowMs: Long = 1_000,
    /** Inicio de la ventana de inmovilidad, medido desde el impacto (deja pasar rebotes). */
    val inactivityStartMs: Long = 2_000,
    /** Fin de la ventana de inmovilidad, medido desde el impacto. */
    val inactivityEndMs: Long = 4_000,
    /** Desvío estándar máximo de |a| (en g) en la ventana para considerar "quieto". */
    val inactivityMaxStdG: Double = 0.15,
    /** Muestras mínimas en la ventana de inmovilidad (evita decidir con datos insuficientes). */
    val minInactivitySamples: Int = 20,
    /** Tras una detección, se ignoran nuevas caídas durante este tiempo. */
    val refractoryMs: Long = 30_000,
    /** Si entre dos muestras pasa más que esto (sensor pausado), se reinicia la máquina de estados. */
    val maxSampleGapMs: Long = 1_000,
)

/** Caída candidata emitida por el detector (aún sin confirmar por el usuario). */
data class FallCandidate(
    val impactTimestampNs: Long,
    val peakG: Double,
    val freeFallMs: Long,
    val postImpactStdG: Double,
)

/**
 * Máquina de estados sobre la magnitud de la aceleración |a| = sqrt(x²+y²+z²).
 *
 * 1. CAÍDA LIBRE: |a| < [FallDetectorConfig.freeFallThresholdG] durante ≥ minFreeFallMs.
 * 2. IMPACTO:     |a| > [FallDetectorConfig.impactThresholdG] dentro de impactWindowMs.
 * 3. INMOVILIDAD: entre inactivityStartMs e inactivityEndMs tras el impacto, desvío de |a| < inactivityMaxStdG.
 *
 * No depende de Android: se alimenta con [ingest] y se testea con secuencias sintéticas.
 * No es thread-safe: llamarlo siempre desde el mismo hilo (el callback del SensorManager).
 */
class FallDetector(
    private val config: FallDetectorConfig = FallDetectorConfig(),
    private val onFall: (FallCandidate) -> Unit,
) {
    private enum class Fase { REPOSO, CAIDA_LIBRE, ESPERANDO_IMPACTO, POST_IMPACTO, REFRACTARIO }

    private var fase = Fase.REPOSO
    private var ultimoTs = -1L

    private var inicioCaidaLibreNs = 0L
    private var finCaidaLibreNs = 0L
    private var impactoNs = 0L
    private var picoG = 0.0
    private var finRefractarioNs = 0L

    // Acumuladores para el desvío estándar en la ventana de inmovilidad.
    private var n = 0
    private var suma = 0.0
    private var sumaCuadrados = 0.0

    /**
     * @param timestampNs timestamp monotónico del evento (SensorEvent.timestamp).
     * @param x,y,z aceleración en m/s² (TYPE_ACCELEROMETER, incluye gravedad).
     */
    fun ingest(timestampNs: Long, x: Float, y: Float, z: Float) {
        val g = sqrt((x * x + y * y + z * z).toDouble()) / GRAVEDAD
        ingestMagnitudeG(timestampNs, g)
    }

    /** Variante que recibe directamente |a| en g (útil para tests). */
    fun ingestMagnitudeG(timestampNs: Long, g: Double) {
        if (ultimoTs >= 0 && ms(timestampNs - ultimoTs) > config.maxSampleGapMs && fase != Fase.REFRACTARIO) {
            reiniciar()
        }
        ultimoTs = timestampNs

        when (fase) {
            Fase.REPOSO -> if (g < config.freeFallThresholdG) {
                inicioCaidaLibreNs = timestampNs
                finCaidaLibreNs = timestampNs
                fase = Fase.CAIDA_LIBRE
            }

            Fase.CAIDA_LIBRE -> {
                if (g < config.freeFallThresholdG) {
                    finCaidaLibreNs = timestampNs
                    return
                }
                val duracion = ms(finCaidaLibreNs - inicioCaidaLibreNs)
                if (duracion < config.minFreeFallMs) {
                    fase = Fase.REPOSO
                    return
                }
                fase = Fase.ESPERANDO_IMPACTO
                // La primera muestra fuera de caída libre puede ser ya el impacto.
                evaluarImpacto(timestampNs, g)
            }

            Fase.ESPERANDO_IMPACTO -> evaluarImpacto(timestampNs, g)

            Fase.POST_IMPACTO -> {
                val desdeImpacto = ms(timestampNs - impactoNs)
                if (desdeImpacto < config.inactivityStartMs) {
                    if (g > picoG) picoG = g
                    return
                }
                if (desdeImpacto <= config.inactivityEndMs) {
                    n++
                    suma += g
                    sumaCuadrados += g * g
                    return
                }
                decidir(timestampNs)
            }

            Fase.REFRACTARIO -> if (timestampNs >= finRefractarioNs) {
                fase = Fase.REPOSO
            }
        }
    }

    private fun evaluarImpacto(timestampNs: Long, g: Double) {
        if (g > config.impactThresholdG) {
            impactoNs = timestampNs
            picoG = g
            n = 0
            suma = 0.0
            sumaCuadrados = 0.0
            fase = Fase.POST_IMPACTO
        } else if (ms(timestampNs - finCaidaLibreNs) > config.impactWindowMs) {
            fase = Fase.REPOSO
        }
    }

    private fun decidir(timestampNs: Long) {
        val std = desvio()
        if (n >= config.minInactivitySamples && std < config.inactivityMaxStdG) {
            fase = Fase.REFRACTARIO
            finRefractarioNs = timestampNs + config.refractoryMs * NS_POR_MS
            onFall(
                FallCandidate(
                    impactTimestampNs = impactoNs,
                    peakG = picoG,
                    freeFallMs = ms(finCaidaLibreNs - inicioCaidaLibreNs),
                    postImpactStdG = std,
                )
            )
        } else {
            fase = Fase.REPOSO
        }
    }

    private fun desvio(): Double {
        if (n == 0) return Double.MAX_VALUE
        val media = suma / n
        val varianza = (sumaCuadrados / n - media * media).coerceAtLeast(0.0)
        return sqrt(varianza)
    }

    fun reiniciar() {
        fase = Fase.REPOSO
        n = 0
        suma = 0.0
        sumaCuadrados = 0.0
    }

    private fun ms(ns: Long): Long = ns / NS_POR_MS

    companion object {
        private const val GRAVEDAD = 9.80665
        private const val NS_POR_MS = 1_000_000L
    }
}
