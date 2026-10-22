package kz.smartcity.walk.ui.profile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kz.smartcity.walk.AppGraph
import kz.smartcity.walk.data.LatLng
import kz.smartcity.walk.data.Settings
import kz.smartcity.walk.data.db.CategoryCounter
import kz.smartcity.walk.data.db.ProfileFact
import kz.smartcity.walk.walk.ActivityTransitions
import kz.smartcity.walk.walk.WalkService
import kz.smartcity.walk.walk.WalkStatus

data class PermissionStatus(
    val location: Boolean = false,
    val background: Boolean = false,
    val activity: Boolean = false,
    val notifications: Boolean = false,
    val batteryUnrestricted: Boolean = false,
)

data class ProfileUi(
    val transport: String,
    val triggers: Map<String, Boolean>,
    val mockLocation: LatLng?,
    val timeOffsetMin: Int,
    val apiBaseUrl: String,
    val haptics: Boolean,
)

class ProfileViewModel(private val g: AppGraph) : ViewModel() {
    val facts: StateFlow<List<ProfileFact>> = g.profile.facts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val counters: StateFlow<List<CategoryCounter>> = g.profile.counters.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val walk: StateFlow<WalkStatus> = WalkService.statusFlow
    val ui = MutableStateFlow(snapshot())
    val permissions = MutableStateFlow(PermissionStatus())

    private fun snapshot() = ProfileUi(
        transport = g.settings.defaultTransport,
        triggers = Settings.TRIGGERS.associateWith { g.settings.triggerEnabled(it) },
        mockLocation = g.settings.mockLocation,
        timeOffsetMin = g.settings.timeOffsetMinutes,
        apiBaseUrl = g.settings.apiBaseUrl,
        haptics = g.settings.hapticsOn,
    )

    fun setHaptics(on: Boolean) {
        g.settings.hapticsOn = on
        ui.value = snapshot()
    }

    fun refreshPermissions(ctx: Context) {
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        val pm = ctx.getSystemService(PowerManager::class.java)
        permissions.value = PermissionStatus(
            location = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            activity = ActivityTransitions.permitted(ctx),
            notifications = NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
            batteryUnrestricted = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true,
        )
        if (permissions.value.activity) ActivityTransitions.register(ctx)
    }

    fun setTransport(t: String) {
        g.settings.defaultTransport = t
        ui.value = snapshot()
    }

    fun setTrigger(t: String, on: Boolean) {
        g.settings.setTriggerEnabled(t, on)
        ui.value = snapshot()
    }

    fun editFact(f: ProfileFact, value: String) = viewModelScope.launch { g.profile.updateFactValue(f, value) }
    fun deleteFact(f: ProfileFact) = viewModelScope.launch { g.profile.deleteFact(f.id) }
    fun deletePortrait() = viewModelScope.launch { g.profile.deleteAll() }

    fun deleteAll(done: () -> Unit) = viewModelScope.launch {
        g.deleteAllData()
        ui.value = snapshot()
        done()
    }

    // ---- отладка ----
    fun setMock(p: LatLng?) {
        g.settings.mockLocation = p
        ui.value = snapshot()
    }

    fun shiftTime(minutes: Int) {
        g.settings.timeOffsetMinutes = if (minutes == 0) 0 else g.settings.timeOffsetMinutes + minutes
        ui.value = snapshot()
    }

    fun setApiUrl(url: String) {
        g.settings.apiBaseUrl = url
        ui.value = snapshot()
    }

    fun forceWalk(ctx: Context) = WalkService.forceWalk(ctx)
    fun stopWalk(ctx: Context) = WalkService.stop(ctx)

    class Factory(private val g: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ProfileViewModel(g) as T
    }

    companion object {
        val PRESETS = listOf(
            "Mega Silk Way" to LatLng(51.0890, 71.4086),
            "Expo" to LatLng(51.0889, 71.4158),
            "Аэропорт" to LatLng(51.0222, 71.4669),
            "Хан Шатыр" to LatLng(51.1324, 71.4036),
        )
    }
}
