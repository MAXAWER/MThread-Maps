package kz.smartcity.walk.walk

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kz.smartcity.walk.work.Workers

/** Подписка на переходы активности: WALKING, RUNNING, ON_BICYCLE, IN_VEHICLE, STILL. */
object ActivityTransitions {
    private const val TAG = "ActivityTransitions"

    fun permitted(ctx: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, ActivityTransitionReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    @SuppressLint("MissingPermission")
    fun register(ctx: Context) {
        if (!permitted(ctx)) return
        val types = listOf(DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_BICYCLE,
            DetectedActivity.IN_VEHICLE, DetectedActivity.STILL)
        val transitions = types.flatMap { t ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
                ActivityTransition.Builder().setActivityType(t).setActivityTransition(it).build()
            }
        }
        ActivityRecognition.getClient(ctx)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(ctx))
            .addOnFailureListener { Log.w(TAG, "transition updates failed: ${it.javaClass.simpleName}") }
    }
}

/** Переход активности — одно из исключений, при которых Android 12+ разрешает запуск сервиса переднего плана из фона. */
class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (e in result.transitionEvents) {
            WalkService.onActivity(context, Activity.fromDetected(e.activityType),
                e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ActivityTransitions.register(context)
        Workers.schedulePeriodicDeck(context)
    }
}
