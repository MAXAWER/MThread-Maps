package kz.smartcity.walk

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kz.smartcity.walk.notify.Notifier
import kz.smartcity.walk.walk.ActivityTransitions
import kz.smartcity.walk.work.Workers

class App : Application() {
    lateinit var graph: AppGraph
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifier.createChannels(this)
        // Детектор прогулки регистрируется при старте и после перезагрузки (раздел 10).
        ActivityTransitions.register(this)
        Workers.schedulePeriodicDeck(this)
        scope.launch { graph.history.purge(System.currentTimeMillis()) }
    }
}
