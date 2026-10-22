package kz.smartcity.walk.ui.fx

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Прокрутка с инерцией и заметным трением: экспоненциальное затухание скорости
 * (frictionMultiplier > 1 — вязче, < 1 — дольше катится).
 */
class FrictionFling(private val friction: Float) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) < 1f) return initialVelocity
        var velocityLeft = initialVelocity
        var last = 0f
        AnimationState(initialValue = 0f, initialVelocity = initialVelocity).animateDecay(exponentialDecay(friction)) {
            val delta = value - last
            val consumed = scrollBy(delta)
            last = value
            velocityLeft = velocity
            if (abs(delta - consumed) > 0.5f) cancelAnimation() // упёрлись в край
        }
        return velocityLeft
    }
}

@Composable
fun rememberFrictionFling(friction: Float = 1.4f): FlingBehavior = remember(friction) { FrictionFling(friction) }

/**
 * Физика шторки: высота следует за пальцем, за границами — «резинка» (сопротивление),
 * при отпускании позиция проецируется по инерции с трением на ближайший якорь,
 * и пружина доводит её туда с исходной скоростью броска.
 */
@Stable
class SheetPhysics(private val scope: CoroutineScope, private val haptics: Haptics?) {
    val height = Animatable(0f)
    var anchors by mutableStateOf(emptyList<Float>())
        private set
    private val decay = exponentialDecay<Float>(frictionMultiplier = 2.4f)
    private var crossed = -1

    private val min get() = anchors.firstOrNull() ?: 0f
    private val max get() = anchors.lastOrNull() ?: 0f

    /** 0 — свёрнута, 1 — раскрыта полностью. */
    val progress: Float
        get() = if (max <= min) 0f else ((height.value - min) / (max - min)).coerceIn(0f, 1f)

    val settled: Boolean get() = anchors.any { abs(it - height.value) < 1f }

    fun setAnchors(values: List<Float>, initialIndex: Int) {
        val first = anchors.isEmpty() || height.value == 0f
        anchors = values.sorted()
        scope.launch {
            if (first) height.snapTo(anchors[initialIndex.coerceIn(0, anchors.lastIndex)])
            else if (!settled) settle(0f)
        }
    }

    fun animateTo(index: Int) {
        if (anchors.isEmpty()) return
        scope.launch { height.animateTo(anchors[index.coerceIn(0, anchors.lastIndex)], spring(0.78f, 300f)) }
    }

    /** Перетаскивание (deltaUp > 0 — вверх). За границами — сопротивление 0,3. */
    fun dragBy(deltaUp: Float) {
        if (anchors.isEmpty()) return
        val h = height.value
        val outside = (h >= max && deltaUp > 0) || (h <= min && deltaUp < 0)
        val next = (h + if (outside) deltaUp * 0.3f else deltaUp).coerceIn(min * 0.7f, max * 1.06f)
        scope.launch { height.snapTo(next) }
        // Пересекли якорь пальцем — лёгкий щелчок.
        val idx = anchors.indexOfFirst { (h - it) * (next - it) <= 0f && h != next }
        if (idx >= 0 && idx != crossed) {
            crossed = idx
            haptics?.tick(0.35f)
        }
    }

    /** Бросок: проекция по инерции с трением → ближайший якорь → пружина с исходной скоростью. */
    suspend fun settle(velocityUp: Float) {
        if (anchors.isEmpty()) return
        crossed = -1
        val projected = decay.calculateTargetValue(height.value, velocityUp)
        val target = anchors.minBy { abs(it - projected) }
        val impact = (abs(velocityUp) / 5000f).coerceIn(0f, 1f)
        height.animateTo(target, spring(dampingRatio = 0.72f, stiffness = 340f), initialVelocity = velocityUp)
        haptics?.snap(impact)
    }

    /** Вложенная прокрутка: сначала двигается шторка, потом список внутри. */
    val nestedScroll = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y < 0 && height.value < max - 0.5f) {
                dragBy(-available.y)
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y > 0) {
                dragBy(-available.y)
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!settled) {
                settle(-available.y)
                return available
            }
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (!settled) settle(-available.y)
            return available
        }
    }
}
