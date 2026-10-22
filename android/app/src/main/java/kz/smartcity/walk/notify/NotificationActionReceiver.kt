package kz.smartcity.walk.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kz.smartcity.walk.data.Settings
import kz.smartcity.walk.graph

/** «Не сейчас»: пауза триггера удваивается (раздел 10). */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = intent.getStringExtra(Notifier.EXTRA_TRIGGER) ?: return
        val base = Settings.TRIGGER_COOLDOWN_MIN[trigger] ?: 60
        context.graph.settings.snoozeTrigger(trigger, base, System.currentTimeMillis())
        NotificationManagerCompat.from(context).cancel(Notifier.ID_SUGGEST)
    }
}
