package kz.smartcity.walk.ui.profile

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kz.smartcity.walk.BuildConfig
import kz.smartcity.walk.data.Categories
import kz.smartcity.walk.data.Geo
import kz.smartcity.walk.data.Settings
import kz.smartcity.walk.data.db.ProfileFact
import kz.smartcity.walk.data.repo.ProfileMath
import kz.smartcity.walk.data.repo.ProfileRepository
import kz.smartcity.walk.ui.components.CategoryBadge
import kz.smartcity.walk.ui.components.TRANSPORTS
import kz.smartcity.walk.ui.components.groupColor
import kz.smartcity.walk.ui.components.transportIcon
import kz.smartcity.walk.ui.components.transportLabel
import kz.smartcity.walk.ui.fx.BubbleButton
import kz.smartcity.walk.ui.fx.BubbleCard
import kz.smartcity.walk.ui.fx.BubbleChip
import kz.smartcity.walk.ui.fx.BubbleGhostButton
import kz.smartcity.walk.ui.fx.BubbleIconButton
import kz.smartcity.walk.ui.fx.BubbleShapes
import kz.smartcity.walk.ui.fx.Depth
import kz.smartcity.walk.ui.fx.Fills
import kz.smartcity.walk.ui.fx.LocalHaptics
import kz.smartcity.walk.ui.fx.PopIn
import kz.smartcity.walk.ui.fx.bubble
import kz.smartcity.walk.ui.fx.rememberFrictionFling
import kz.smartcity.walk.walk.WalkDetector

@Composable
fun ProfileScreen(vm: ProfileViewModel, modifier: Modifier = Modifier, bottomSpace: Dp = 0.dp) {
    var showPortrait by remember { mutableStateOf(false) }
    // Плотная подложка: сквозь неё едва видна размытая карта на дальнем слое.
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))) {
        if (showPortrait) PortraitScreen(vm, bottomSpace, onBack = { showPortrait = false })
        else ProfileMain(vm, bottomSpace, onPortrait = { showPortrait = true })
    }
}

@Composable
private fun Section(index: Int, title: String, content: @Composable ColumnScope.() -> Unit) {
    PopIn(index) {
        BubbleCard(Modifier.fillMaxWidth().padding(vertical = 7.dp), depth = Depth.LOW) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val haptics = LocalHaptics.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { haptics?.tick(if (it) 0.6f else 0.35f); onChange(it) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileMain(vm: ProfileViewModel, bottomSpace: Dp, onPortrait: () -> Unit) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val walk by vm.walk.collectAsStateWithLifecycle()
    val facts by vm.facts.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.refreshPermissions(context) } }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState(), flingBehavior = rememberFrictionFling(1.3f))
        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomSpace + 12.dp)) {
        Text("Профиль", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(bottom = 8.dp))
        Section(0, "Транспорт по умолчанию") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TRANSPORTS.forEach { t ->
                    BubbleChip(transportLabel(t), onClick = { vm.setTransport(t) }, selected = ui.transport == t, leading = transportIcon(t))
                }
            }
        }
        Section(1, "Подсказки на прогулке") {
            val status = when (walk.state) {
                WalkDetector.State.WALKING -> "Сейчас: идёт прогулка, ${((System.currentTimeMillis() - walk.startedMs) / 60_000)} мин, ${walk.distanceM} м"
                WalkDetector.State.CANDIDATE -> "Сейчас: определяем прогулку…"
                WalkDetector.State.IDLE -> "Прогулка определится сама, когда вы пойдёте пешком"
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Не больше 3 уведомлений в день, тихие часы 22:00–08:00.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Settings.TRIGGERS.forEach { t -> ToggleRow(Settings.TRIGGER_LABELS[t] ?: t, ui.triggers[t] == true) { vm.setTrigger(t, it) } }
        }
        Section(2, "Ощущения") {
            ToggleRow("Тактильный отклик", ui.haptics) { vm.setHaptics(it) }
            Text("Короткие импульсы линейного вибромотора: нажатия, свайпы, шторка, подсказки навигатора.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Section(3, "Мой портрет") {
            Text("Портрет хранится только на телефоне. Фактов: ${facts.size}.", style = MaterialTheme.typography.bodySmall)
            BubbleGhostButton(onClick = onPortrait) { Text("Открыть «Мой портрет»") }
        }
        Section(4, "Разрешения") {
            PermRow("Геолокация", perms.location)
            PermRow("Геолокация «Разрешить всегда»", perms.background)
            PermRow("Распознавание активности", perms.activity)
            PermRow("Уведомления", perms.notifications)
            PermRow("Без ограничений батареи", perms.batteryUnrestricted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BubbleGhostButton(onClick = {
                    context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }) { Text("Настройки") }
                BubbleGhostButton(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
                    Text("Батарея")
                }
            }
        }
        Section(5, "Данные") {
            Text("Удаляет портрет, историю посещений, колоду и кэш на телефоне.", style = MaterialTheme.typography.bodySmall)
            BubbleButton(onClick = { confirmDelete = true }, fill = SolidColor(MaterialTheme.colorScheme.error),
                glow = MaterialTheme.colorScheme.error) { Text("Удалить все данные") }
        }
        if (BuildConfig.DEBUG) DebugSection(6, vm, ui)
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = BubbleShapes.XL,
            title = { Text("Удалить все данные?") },
            text = { Text("Портрет, история и колода будут стёрты с телефона. Действие не отменить.") },
            confirmButton = { TextButton(onClick = { vm.deleteAll { confirmDelete = false } }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(40.dp)) {
        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, contentDescription = if (ok) "выдано" else "не выдано",
            tint = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DebugSection(index: Int, vm: ProfileViewModel, ui: ProfileUi) {
    val context = LocalContext.current
    var url by remember(ui.apiBaseUrl) { mutableStateOf(ui.apiBaseUrl) }
    Section(index, "Отладка") {
        Text("Подстановка координат", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ProfileViewModel.PRESETS.forEach { (name, p) ->
                // Точка хранится как Float: сравниваем с допуском, а не на равенство.
                val selected = ui.mockLocation?.let { Geo.distance(it, p) < 5.0 } == true
                BubbleChip(name, onClick = { vm.setMock(p) }, selected = selected)
            }
            BubbleChip("GPS", onClick = { vm.setMock(null) }, selected = ui.mockLocation == null)
        }
        Text("Сдвиг времени: ${ui.timeOffsetMin / 60} ч ${ui.timeOffsetMin % 60} мин", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BubbleChip("−1 ч", onClick = { vm.shiftTime(-60) })
            BubbleChip("+1 ч", onClick = { vm.shiftTime(60) })
            BubbleChip("Сброс", onClick = { vm.shiftTime(0) })
        }
        Text("Прогулка", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BubbleGhostButton(onClick = { vm.forceWalk(context) }) { Text("Начать сейчас") }
            BubbleGhostButton(onClick = { vm.stopWalk(context) }) { Text("Остановить") }
        }
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Адрес сервера") }, singleLine = true,
            shape = BubbleShapes.M, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { vm.setApiUrl(url) }) { Text("Сохранить адрес") }
        Text("Подставной прогноз: флаг WEATHER_OVERRIDE на сервере.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PortraitScreen(vm: ProfileViewModel, bottomSpace: Dp, onBack: () -> Unit) {
    val facts by vm.facts.collectAsStateWithLifecycle()
    val counters by vm.counters.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ProfileFact?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState(), flingBehavior = rememberFrictionFling(1.3f))
        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomSpace + 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BubbleIconButton(onClick = onBack, icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Text("Мой портрет", style = MaterialTheme.typography.headlineLarge)
        }
        Text("Модель дополняет портрет после поисков, свайпов и прогулок. Всё хранится на телефоне.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp))
        Section(0, "Интересы") {
            val shown = counters.filter { it.alpha + it.beta > 2.0 }.sortedByDescending { ProfileMath.affinity(it.alpha, it.beta) }
            if (shown.isEmpty()) Text("Пока пусто: выбирайте места и свайпайте карточки.", style = MaterialTheme.typography.bodySmall)
            shown.forEach { c ->
                val a = ProfileMath.affinity(c.alpha, c.beta).toFloat()
                val color = groupColor(Categories.group(c.category))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    CategoryBadge(c.category, 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${Categories.name(c.category)} — ${(a * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                        Box(Modifier.fillMaxWidth().height(12.dp).bubble(BubbleShapes.Pill, Fills.tint(color), Depth.FLOOR)) {
                            Box(Modifier.fillMaxWidth(a).height(12.dp).bubble(BubbleShapes.Pill,
                                SolidColor(color), Depth.FLOOR))
                        }
                    }
                }
            }
        }
        Section(1, "Факты") {
            if (facts.isEmpty()) Text("Фактов пока нет.", style = MaterialTheme.typography.bodySmall)
            facts.forEach { f ->
                val c = ProfileMath.decayed(f.confidence, f.lastConfirmedMs, now)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .bubble(BubbleShapes.M, Fills.tint(MaterialTheme.colorScheme.primary), Depth.FLOOR)
                    .padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text("${ProfileRepository.KEY_LABELS[f.key] ?: f.key}: ${f.value}", style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text("уверенность ${(c * 100).toInt()}% · подтверждений ${f.confirmations} · " +
                            if (f.explicit) "сказано прямо" else "выведено", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BubbleIconButton(onClick = { editing = f }, icon = Icons.Filled.Edit, contentDescription = "Изменить факт", size = 42.dp,
                        depth = Depth.FLOOR)
                    Spacer(Modifier.width(6.dp))
                    BubbleIconButton(onClick = { vm.deleteFact(f) }, icon = Icons.Filled.Delete, contentDescription = "Удалить факт", size = 42.dp,
                        depth = Depth.FLOOR, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        BubbleButton(onClick = { confirmWipe = true }, fill = SolidColor(MaterialTheme.colorScheme.error),
            glow = MaterialTheme.colorScheme.error) { Text("Удалить весь портрет") }
    }
    editing?.let { f ->
        var text by remember(f.id) { mutableStateOf(f.value) }
        AlertDialog(
            onDismissRequest = { editing = null },
            shape = BubbleShapes.XL,
            title = { Text("Изменить факт") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(120) }, singleLine = false, shape = BubbleShapes.M) },
            confirmButton = { TextButton(onClick = { vm.editFact(f, text); editing = null }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Отмена") } },
        )
    }
    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            shape = BubbleShapes.XL,
            title = { Text("Удалить портрет?") },
            text = { Text("Интересы и факты будут стёрты с телефона.") },
            confirmButton = { TextButton(onClick = { vm.deletePortrait(); confirmWipe = false }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Отмена") } },
        )
    }
}
