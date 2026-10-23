package kz.smartcity.walk.data.repo

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** Правила слияния портрета (раздел 8) и формулы колоды (раздел 9). Чистые функции — покрыты тестами. */
object ProfileMath {
    const val C_EXPLICIT = 0.8
    const val C_IMPLICIT = 0.3
    const val MIN_CONFIDENCE = 0.1
    const val MAX_FACTS = 200
    const val SUMMARY_FACTS = 15
    private const val DAY_MS = 86_400_000.0

    /** c_day = c · 2^(−Δd/30) */
    fun decayed(c: Double, lastConfirmedMs: Long, nowMs: Long): Double {
        val days = ((nowMs - lastConfirmedMs).coerceAtLeast(0)) / DAY_MS
        return c * 2.0.pow(-days / 30.0)
    }

    fun added(explicit: Boolean) = if (explicit) C_EXPLICIT else C_IMPLICIT
    fun reinforced(c: Double) = c + 0.2 * (1 - c)
    fun weakened(c: Double) = 0.8 * c
    fun affinity(alpha: Double, beta: Double) = alpha / (alpha + beta)

    /** θ ~ Beta(α, β) через две гамма-выборки. */
    fun sampleBeta(alpha: Double, beta: Double, rnd: Random): Double {
        val x = sampleGamma(alpha, rnd)
        val y = sampleGamma(beta, rnd)
        return if (x + y == 0.0) 0.5 else x / (x + y)
    }

    /** Марсалья–Цанг; для shape < 1 — поправка через u^(1/shape). */
    fun sampleGamma(shape: Double, rnd: Random): Double {
        if (shape < 1.0) return sampleGamma(shape + 1.0, rnd) * rnd.nextDouble().pow(1.0 / shape)
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = gaussian(rnd)
                v = 1.0 + c * x
            } while (v <= 0)
            v = v * v * v
            val u = rnd.nextDouble()
            if (u < 1 - 0.0331 * x * x * x * x) return d * v
            if (ln(u) < 0.5 * x * x + d * (1 - v + ln(v))) return d * v
        }
    }

    private fun gaussian(rnd: Random): Double {
        val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
        val u2 = rnd.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    /** s = 0.30θ + 0.20K + 0.20e^(−t/10) + 0.15Q + 0.15N */
    fun deckScore(theta: Double, k: Double, etaMin: Double, q: Double, novelty: Double): Double =
        0.30 * theta + 0.20 * k + 0.20 * exp(-etaMin / 10.0) + 0.15 * q + 0.15 * novelty

    /**
     * Черты карточки, на которые влияют свайпы (кроме самой категории): под крышей или на улице, цена,
     * расстояние, высокий рейтинг. «Не то» кафе далеко и дорого — снижает вес «далеко» и «дорого», а не только кафе.
     */
    fun features(indoor: Boolean, avgCheck: Int?, etaMin: Int?, rating: Double?): List<String> = buildList {
        add(if (indoor) "feat:indoor" else "feat:outdoor")
        when {
            avgCheck == null -> Unit
            avgCheck <= 3000 -> add("feat:cheap")
            avgCheck <= 7000 -> add("feat:mid")
            else -> add("feat:pricey")
        }
        when {
            etaMin == null -> Unit
            etaMin <= 8 -> add("feat:near")
            etaMin >= 20 -> add("feat:far")
        }
        if (rating != null && rating >= 4.6) add("feat:toprated")
    }

    /** Множитель за черты: средний интерес (0,5) — 1,0; каждая черта даёт от 0,75 до 1,25; итог в пределах 0,5…1,6. */
    fun featureFactor(features: List<String>, affinity: (String) -> Double?): Double =
        features.fold(1.0) { acc, f -> acc * (0.75 + 0.5 * (affinity(f) ?: 0.5)) }.coerceIn(0.5, 1.6)

    /** Разнообразие: next = argmax [0.7·s_i − 0.3·max sim(i, j∈S)]; sim = 1 та же категория, 0.5 та же группа. */
    fun <T> diversify(items: List<T>, score: (T) -> Double, category: (T) -> String, group: (T) -> String?, take: Int): List<T> {
        val left = items.toMutableList()
        val chosen = mutableListOf<T>()
        while (chosen.size < take && left.isNotEmpty()) {
            val best = left.maxBy { item ->
                val sim = chosen.maxOfOrNull { other ->
                    when {
                        category(other) == category(item) -> 1.0
                        group(other) != null && group(other) == group(item) -> 0.5
                        else -> 0.0
                    }
                } ?: 0.0
                0.7 * score(item) - 0.3 * sim
            }
            chosen += best
            left -= best
        }
        return chosen
    }
}
