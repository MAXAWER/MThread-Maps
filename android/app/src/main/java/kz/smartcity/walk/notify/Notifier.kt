package kz.smartcity.walk.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kz.smartcity.walk.MainActivity
import kz.smartcity.walk.R
import kz.smartcity.walk.data.api.NotifyResponse
import kz.smartcity.walk.graph

object Notifier {
    const val CH_WALK = "walk_service"
    const val CH_SUGGEST = "suggestions"
    const val CH_NAV = "navigation"
    const val ID_WALK = 1001
    const val ID_NAV = 1002
    const val ID_SUGGEST = 1003

    const val EXTRA_ACTION = "walk_action"
    const val EXTRA_ITEM = "walk_item"
    const val EXTRA_QUERY = "walk_query"
    const val EXTRA_ITEMS = "walk_items"
    const val EXTRA_TITLE = "walk_title"
    const val EXTRA_TRIGGER = "walk_trigger"

    val TITLES = mapOf(
        "rain" to "Скоро осадки", "cold" to "Похолодало", "long_walk" to "Пора отдохнуть", "meal" to "Время поесть",
        "sunset" to "Скоро закат", "low_battery" to "Низкий заряд", "closing" to "Цель скоро закроется",
    )
    private val MORE_QUERIES = mapOf(
        "rain" to "крытое место рядом, скоро дождь", "cold" to "тёплое место рядом согреться", "long_walk" to "где отдохнуть рядом",
        "meal" to "где поесть рядом", "sunset" to "смотровая площадка на закат", "low_battery" to "кафе с Wi-Fi и розеткой рядом",
        "closing" to "похожее место, открытое дольше",
    )

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_WALK, "Прогулка", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Постоянное уведомление: идёт прогулка, подсказки включены"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_SUGGEST, "Подсказки на прогулке", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Дождь, холод, закат, время поесть и другие контекстные подсказки"
        })
        nm.createNotificationChannel(NotificationChannel(CH_NAV, "Навигация", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Пошаговая навигация по маршруту"
            setShowBadge(false)
        })
    }

    private fun openApp(ctx: Context, requestCode: Int, block: Intent.() -> Unit): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            block()
        }
        return PendingIntent.getActivity(ctx, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun walkNotification(ctx: Context, text: String = "Идёт прогулка — подсказки включены"): Notification =
        NotificationCompat.Builder(ctx, CH_WALK)
            .setSmallIcon(R.drawable.ic_stat_walk)
            .setContentTitle(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(ctx, 10) {})
            .build()

    fun navNotification(ctx: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_NAV)
            .setSmallIcon(R.drawable.ic_stat_walk)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(openApp(ctx, 11) {})
            .build()

    @SuppressLint("MissingPermission")
    fun showSuggestion(ctx: Context, resp: NotifyResponse) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val json = ctx.graph.json
        val first = resp.items.firstOrNull()
        val title = TITLES[resp.trigger] ?: "Подсказка"
        val text = resp.text ?: first?.let { "Рядом: ${it.name}" } ?: return
        val builder = NotificationCompat.Builder(ctx, CH_SUGGEST)
            .setSmallIcon(R.drawable.ic_stat_walk)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, 20) {
                putExtra(EXTRA_ACTION, "items")
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ITEMS, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(
                    kz.smartcity.walk.data.api.AnswerItem.serializer()), resp.items))
            })
        if (first != null) {
            builder.addAction(0, "Маршрут", openApp(ctx, 21) {
                putExtra(EXTRA_ACTION, "route")
                putExtra(EXTRA_ITEM, json.encodeToString(kz.smartcity.walk.data.api.AnswerItem.serializer(), first))
            })
        }
        builder.addAction(0, "Ещё варианты", openApp(ctx, 22) {
            putExtra(EXTRA_ACTION, "search")
            putExtra(EXTRA_QUERY, MORE_QUERIES[resp.trigger] ?: "что рядом")
        })
        val snooze = PendingIntent.getBroadcast(ctx, 23,
            Intent(ctx, NotificationActionReceiver::class.java).putExtra(EXTRA_TRIGGER, resp.trigger),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        builder.addAction(0, "Не сейчас", snooze)
        nm.notify(ID_SUGGEST, builder.build())
    }

    @SuppressLint("MissingPermission")
    fun showPlanOffer(ctx: Context, minutes: Int?) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val text = if (minutes != null) "По прогнозу дождь через $minutes мин, крытых мест рядом нет. Построить план?"
        else "По прогнозу скоро дождь, крытых мест рядом нет. Построить план?"
        val query = "нет зонта, через ${minutes ?: 30} минут дождь, хочу в кафе"
        nm.notify(ID_SUGGEST, NotificationCompat.Builder(ctx, CH_SUGGEST)
            .setSmallIcon(R.drawable.ic_stat_walk)
            .setContentTitle("Скоро осадки")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, 24) { putExtra(EXTRA_ACTION, "search"); putExtra(EXTRA_QUERY, query) })
            .build())
    }
}
