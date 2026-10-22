package kz.smartcity.walk.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kz.smartcity.walk.AppGraph
import kz.smartcity.walk.ui.components.TRANSPORTS
import kz.smartcity.walk.ui.components.transportIcon
import kz.smartcity.walk.ui.components.transportLabel
import kz.smartcity.walk.ui.fx.BubbleButton
import kz.smartcity.walk.ui.fx.BubbleCard
import kz.smartcity.walk.ui.fx.BubbleChip
import kz.smartcity.walk.ui.fx.BubbleGhostButton
import kz.smartcity.walk.ui.fx.Depth
import kz.smartcity.walk.ui.fx.Fills
import kz.smartcity.walk.ui.fx.LocalHaptics
import kz.smartcity.walk.ui.fx.PopIn
import kz.smartcity.walk.ui.fx.bubble
import kz.smartcity.walk.ui.fx.rememberFrictionFling
import kz.smartcity.walk.walk.ActivityTransitions
import kz.smartcity.walk.work.Workers

/** Онбординг из 3 шагов: что умеет приложение; разрешения с объяснением каждого; транспорт по умолчанию. */
@Composable
fun OnboardingScreen(graph: AppGraph, onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val haptics = LocalHaptics.current
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background)))
            .statusBarsPadding().navigationBarsPadding().padding(20.dp),
    ) {
        StepDots(step)
        Spacer(Modifier.height(12.dp))
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(spring(0.8f, 300f)) { it * dir } + fadeIn()) togetherWith
                    (slideOutHorizontally(spring(0.8f, 300f)) { -it * dir } + fadeOut())
            },
            modifier = Modifier.weight(1f),
            label = "onboarding",
        ) { s ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), flingBehavior = rememberFrictionFling(1.3f))) {
                when (s) {
                    0 -> IntroStep()
                    1 -> PermissionsStep()
                    else -> TransportStep(graph)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (step > 0) BubbleGhostButton(onClick = { step-- }) { Text("Назад") }
            BubbleButton(onClick = {
                if (step < 2) step++ else {
                    graph.settings.onboardingDone = true
                    ActivityTransitions.register(graph.app)
                    Workers.refreshDeckNow(graph.app)
                    haptics?.success()
                    onDone()
                }
            }, modifier = Modifier.weight(1f)) { Text(if (step < 2) "Дальше" else "Начать") }
        }
    }
}

@Composable
private fun StepDots(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val w by animateDpAsState(if (i == step) 34.dp else 12.dp, spring(dampingRatio = 0.5f, stiffness = 400f), label = "dot")
            Box(Modifier.width(w).height(12.dp).bubble(kz.smartcity.walk.ui.fx.BubbleShapes.Pill,
                if (i <= step) Fills.primary() else Fills.glass(0.6f), Depth.FLOOR, highlight = 0.3f))
        }
    }
}

@Composable
private fun Feature(index: Int, icon: ImageVector, text: String) {
    PopIn(index) {
        BubbleCard(Modifier.fillMaxWidth().padding(vertical = 6.dp), depth = Depth.LOW) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).bubble(CircleShape, Fills.primary(), Depth.LOW, highlight = 0.35f), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(14.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun IntroStep() {
    Text("Помощник по прогулкам по Астане", style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(16.dp))
    Feature(0, Icons.Filled.AutoAwesome, "Напишите, чего хочется, — получите 1–3 места с причиной, временем на посещение и действием.")
    Feature(1, Icons.Filled.Map, "Карта 2ГИС, поиск, маршруты на любом транспорте и пошаговая навигация прямо в приложении.")
    Feature(2, Icons.Filled.Style, "Раздел «Для вас» подбирает места заранее по вашим вкусам, времени и погоде.")
    Feature(3, Icons.Filled.NotificationsActive, "Во время прогулки приложение само подскажет, если скоро дождь, закат или пора поесть.")
    Feature(4, Icons.Filled.Lock, "Портрет и история хранятся только на телефоне; на сервер уходит точка, округлённая до ~110 м.")
}

private data class PermItem(val title: String, val why: String, val granted: Boolean, val request: () -> Unit)

@Composable
private fun PermissionsStep() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    @Suppress("UNUSED_EXPRESSION") tick
    val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    val items = buildList {
        add(PermItem("Геолокация", "Чтобы искать места рядом и вести по маршруту. Точные координаты остаются на телефоне.", fine) {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(PermItem("Геолокация «Разрешить всегда»",
                "Без неё Android не даст определять прогулку, когда приложение закрыто. На Android 11+ выбирается в системных настройках.",
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                if (fine) single.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            })
            add(PermItem("Распознавание активности", "Чтобы прогулка начиналась сама, без кнопок: телефон понимает, что вы идёте пешком.",
                granted(Manifest.permission.ACTIVITY_RECOGNITION)) { single.launch(Manifest.permission.ACTIVITY_RECOGNITION) })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(PermItem("Уведомления", "Для подсказок на прогулке: не больше 3 в день, ночью — тишина.",
                granted(Manifest.permission.POST_NOTIFICATIONS)) { single.launch(Manifest.permission.POST_NOTIFICATIONS) })
        }
    }
    Text("Разрешения", style = MaterialTheme.typography.headlineLarge)
    Text("Нужны один раз. Можно пропустить — тогда прогулка не будет определяться автоматически.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    items.forEachIndexed { i, p ->
        PopIn(i) {
            BubbleCard(Modifier.fillMaxWidth().padding(vertical = 6.dp), depth = Depth.LOW) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (p.granted) Icon(Icons.Filled.CheckCircle, contentDescription = "выдано", tint = MaterialTheme.colorScheme.tertiary)
                    }
                    Text(p.why, style = MaterialTheme.typography.bodySmall)
                    if (!p.granted) BubbleGhostButton(onClick = p.request) { Text("Разрешить") }
                }
            }
        }
    }
    PopIn(items.size) {
        BubbleCard(Modifier.fillMaxWidth().padding(vertical = 6.dp), depth = Depth.LOW) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Оптимизация батареи", style = MaterialTheme.typography.titleMedium)
                Text("Некоторые производители выгружают фоновые сервисы. Отключите оптимизацию для приложения, чтобы прогулка не терялась.",
                    style = MaterialTheme.typography.bodySmall)
                BubbleGhostButton(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
                    Text("Настройки батареи")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("DEPRECATION")
@Composable
private fun TransportStep(graph: AppGraph) {
    var selected by remember { mutableStateOf(graph.settings.defaultTransport) }
    Text("Как вы обычно передвигаетесь?", style = MaterialTheme.typography.headlineLarge)
    Text("От этого зависят время в пути и радиус поиска. Поменять можно в профиле.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(18.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(120.dp).bubble(CircleShape, Fills.accent(), Depth.FLOAT, glow = MaterialTheme.colorScheme.secondary, highlight = 0.4f),
            contentAlignment = Alignment.Center) {
            Icon(transportIcon(selected), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondary, modifier = Modifier.size(56.dp))
        }
    }
    Spacer(Modifier.height(18.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TRANSPORTS.forEach { t ->
            BubbleChip(transportLabel(t), onClick = { selected = t; graph.settings.defaultTransport = t }, selected = selected == t,
                leading = transportIcon(t))
        }
    }
}
