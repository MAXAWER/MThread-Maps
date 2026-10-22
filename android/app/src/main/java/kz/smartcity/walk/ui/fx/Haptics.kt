package kz.smartcity.walk.ui.fx

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Тактильный отклик для линейного вибромотора (LRA): композиции примитивов VibrationEffect.
 * Короткие чёткие импульсы вместо «жужжания»; на старых устройствах — предустановленные эффекты,
 * затем волна с линейным нарастанием амплитуды, затем короткий импульс.
 */
class Haptics(context: Context, private val enabled: () -> Boolean) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    private val supported: Set<Int> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator?.hasVibrator() == true) {
        allPrimitives().filter { vibrator.areAllPrimitivesSupported(it) }.toSet()
    } else emptySet()

    private val amplitude = vibrator?.hasAmplitudeControl() == true
    private var lastTextureMs = 0L

    /** Шаг композиции: примитив, сила 0…1, пауза перед ним. */
    private data class Step(val primitive: Int, val scale: Float, val delayMs: Int = 0)

    private fun allPrimitives(): List<Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val list = mutableListOf(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
            VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
            VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += listOf(
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                VibrationEffect.Composition.PRIMITIVE_THUD,
                VibrationEffect.Composition.PRIMITIVE_SPIN,
            )
        }
        return list
    }

    @SuppressLint("MissingPermission")
    private fun play(steps: List<Step>, fallback: () -> VibrationEffect?) {
        val v = vibrator ?: return
        if (!enabled() || !v.hasVibrator()) return
        val effect = composition(steps) ?: fallback() ?: return
        try {
            v.vibrate(effect)
        } catch (_: SecurityException) {
            // Без разрешения VIBRATE отклик просто не воспроизводится.
        }
    }

    private fun composition(steps: List<Step>): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val usable = steps.filter { it.primitive in supported }
        if (usable.isEmpty()) return null
        val c = VibrationEffect.startComposition()
        usable.forEach { c.addPrimitive(it.primitive, it.scale.coerceIn(0f, 1f), it.delayMs) }
        return c.compose()
    }

    private fun predefined(id: Int): VibrationEffect? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.createPredefined(id) else null

    /** Линейное нарастание или спад амплитуды — запасной путь для «подъёма» и «спада». */
    private fun ramp(up: Boolean, ms: Long): VibrationEffect {
        if (!amplitude) return VibrationEffect.createOneShot(ms / 2, VibrationEffect.DEFAULT_AMPLITUDE)
        val n = 6
        val timings = LongArray(n) { ms / n }
        val amps = IntArray(n) { i -> val k = (i + 1) * 255 / n; if (up) k else 255 - k + 255 / n }
        return VibrationEffect.createWaveform(timings, amps, -1)
    }

    private fun oneShot(ms: Long, amp: Int) =
        VibrationEffect.createOneShot(ms, if (amplitude) amp else VibrationEffect.DEFAULT_AMPLITUDE)

    // --- Семантика: что произошло, а не какой примитив ---

    private val click get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) VibrationEffect.Composition.PRIMITIVE_CLICK else -1
    private val tickP get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) VibrationEffect.Composition.PRIMITIVE_TICK else -1
    private val lowTick get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else tickP
    private val thud get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) VibrationEffect.Composition.PRIMITIVE_THUD else click
    private val spin get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) VibrationEffect.Composition.PRIMITIVE_SPIN else click
    private val quickRise get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) VibrationEffect.Composition.PRIMITIVE_QUICK_RISE else -1
    private val slowRise get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) VibrationEffect.Composition.PRIMITIVE_SLOW_RISE else -1
    private val quickFall get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) VibrationEffect.Composition.PRIMITIVE_QUICK_FALL else -1

    /** Нажатие пузыря. */
    fun tap() = play(listOf(Step(click, 0.55f))) { predefined(VibrationEffect.EFFECT_CLICK) ?: oneShot(12, 120) }

    /** Лёгкий щелчок: переключатель, чип, смена вкладки. */
    fun tick(strength: Float = 0.5f) = play(listOf(Step(tickP, strength))) { predefined(VibrationEffect.EFFECT_TICK) ?: oneShot(8, 80) }

    /** «Фактура» при перетаскивании: частые слабые импульсы, сила растёт со скоростью. Только на LRA. */
    fun texture(speed: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTextureMs < 32 || supported.isEmpty()) return
        lastTextureMs = now
        play(listOf(Step(lowTick, (0.12f + speed * 0.45f).coerceAtMost(0.6f)))) { null }
    }

    /** Порог срабатывания (колода, шторка). */
    fun threshold() = play(listOf(Step(click, 0.85f))) { predefined(VibrationEffect.EFFECT_HEAVY_CLICK) ?: oneShot(18, 200) }

    /** Шторка «села» на якорь: сила зависит от скорости удара. */
    fun snap(impact: Float) = play(listOf(Step(thud, (0.3f + impact * 0.6f)), Step(tickP, 0.25f, 40))) {
        predefined(VibrationEffect.EFFECT_TICK) ?: oneShot(10, 140)
    }

    /** «Хочу»: нарастание и щелчок. */
    fun like() = play(listOf(Step(quickRise, 0.7f), Step(click, 1f, 24))) { ramp(up = true, ms = 60) }

    /** «Не то»: короткий спад. */
    fun dislike() = play(listOf(Step(quickFall, 0.75f))) { ramp(up = false, ms = 50) }

    /** Ответ пришёл. */
    fun success() = play(listOf(Step(slowRise, 0.45f), Step(click, 0.7f, 40))) { predefined(VibrationEffect.EFFECT_CLICK) }

    /** Подсказка навигатора: два глухих удара — чувствуется в кармане. */
    fun maneuver() = play(listOf(Step(thud, 0.9f), Step(thud, 0.7f, 140))) { predefined(VibrationEffect.EFFECT_DOUBLE_CLICK) }

    /** Прибытие. */
    fun arrival() = play(listOf(Step(spin, 0.6f), Step(click, 1f, 60), Step(click, 0.8f, 90))) {
        predefined(VibrationEffect.EFFECT_DOUBLE_CLICK) ?: oneShot(40, 255)
    }

    /** Ошибка или сход с маршрута. */
    fun warning() = play(listOf(Step(tickP, 0.9f), Step(tickP, 0.9f, 90))) { predefined(VibrationEffect.EFFECT_DOUBLE_CLICK) }
}

val LocalHaptics = staticCompositionLocalOf<Haptics?> { null }
