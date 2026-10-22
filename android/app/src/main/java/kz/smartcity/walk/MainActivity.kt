package kz.smartcity.walk

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.serialization.builtins.ListSerializer
import kz.smartcity.walk.data.api.AnswerItem
import kz.smartcity.walk.notify.Notifier
import kz.smartcity.walk.ui.AppRoot
import kz.smartcity.walk.ui.theme.WalkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent { WalkTheme { AppRoot(graph) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Кнопки уведомления: «Маршрут» — сразу навигация, «Ещё варианты» — поиск с готовым запросом. */
    private fun handle(intent: Intent?) {
        val g = graph
        when (intent?.getStringExtra(Notifier.EXTRA_ACTION)) {
            "route" -> intent.getStringExtra(Notifier.EXTRA_ITEM)?.let {
                runCatching { g.json.decodeFromString(AnswerItem.serializer(), it) }.getOrNull()
                    ?.let { item -> g.intents.tryEmit(AppIntent.RouteTo(item, startNavigation = true)) }
            }
            "search" -> intent.getStringExtra(Notifier.EXTRA_QUERY)?.let { g.intents.tryEmit(AppIntent.Search(it)) }
            "items" -> intent.getStringExtra(Notifier.EXTRA_ITEMS)?.let {
                val items = runCatching { g.json.decodeFromString(ListSerializer(AnswerItem.serializer()), it) }.getOrNull().orEmpty()
                if (items.isNotEmpty()) g.intents.tryEmit(AppIntent.ShowItems(intent.getStringExtra(Notifier.EXTRA_TITLE) ?: "Подсказка", items))
            }
        }
        intent?.removeExtra(Notifier.EXTRA_ACTION)
    }
}
