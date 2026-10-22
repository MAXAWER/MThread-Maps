package kz.smartcity.walk.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kz.smartcity.walk.data.api.ApiException
import kz.smartcity.walk.data.api.ProfileEvent
import kz.smartcity.walk.data.api.ProfileUpdateRequest
import kz.smartcity.walk.graph
import java.util.concurrent.TimeUnit

object Workers {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Колода: раз в 4 часа при наличии сети и нормальном заряде (раздел 9). */
    fun schedulePeriodicDeck(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<DeckRefreshWorker>(4, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("deck_periodic", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun refreshDeckNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork("deck_now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DeckRefreshWorker>().setConstraints(network).build())
    }

    /** Итоги поисков уходят на сервер, когда есть сеть; портрет обновляется на устройстве. */
    fun syncProfile(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork("profile_sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ProfileSyncWorker>()
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build())
    }
}

class DeckRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val at = g.location.current() ?: return Result.success() // без геолокации колоду не собрать
        return try {
            g.deck.refresh(at)
            Result.success()
        } catch (e: ApiException) {
            Log.w("DeckRefreshWorker", "deck failed: ${e.code}")
            if (e.code == "network") Result.retry() else Result.failure()
        }
    }
}

class ProfileSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val dao = g.db.events()
        while (true) {
            val batch = dao.batch()
            if (batch.isEmpty()) return Result.success()
            for (e in batch) {
                val event = runCatching { g.json.decodeFromString(ProfileEvent.serializer(), e.payload) }.getOrNull()
                if (event == null) {
                    dao.delete(e.id)
                    continue
                }
                try {
                    val nowMs = System.currentTimeMillis()
                    val resp = g.api.profileUpdate(ProfileUpdateRequest(event, g.profile.summary(nowMs)))
                    g.profile.applyOps(resp.ops, nowMs)
                    dao.delete(e.id)
                } catch (ex: ApiException) {
                    Log.w("ProfileSyncWorker", "profile update failed: ${ex.code}")
                    return if (ex.code == "network") Result.retry() else Result.success().also { dao.delete(e.id) }
                }
            }
        }
    }
}
