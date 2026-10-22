package kz.smartcity.walk

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kz.smartcity.walk.data.LocationProvider
import kz.smartcity.walk.data.Settings
import kz.smartcity.walk.data.api.AnswerItem
import kz.smartcity.walk.data.api.ApiClient
import kz.smartcity.walk.data.db.AppDatabase
import kz.smartcity.walk.data.repo.DeckRepository
import kz.smartcity.walk.data.repo.HistoryRepository
import kz.smartcity.walk.data.repo.ProfileRepository

/** Действия из уведомлений и других экранов, которые выполняет главный экран. */
sealed interface AppIntent {
    data class RouteTo(val item: AnswerItem, val startNavigation: Boolean) : AppIntent
    data class Search(val query: String) : AppIntent
    data class ShowItems(val title: String, val items: List<AnswerItem>) : AppIntent
    data class OpenPlace(val card: kz.smartcity.walk.data.api.PlaceCard) : AppIntent
}

/** Простой ручной DI: один экземпляр на процесс. */
class AppGraph(context: Context) {
    val app: Context = context.applicationContext
    val settings = Settings(app)
    val places = kz.smartcity.walk.data.QuickPlaces(app)
    val api = ApiClient({ settings.apiBaseUrl }, { settings.installId })
    val json get() = api.json
    val db = AppDatabase.create(app)
    val history = HistoryRepository(db)
    val profile = ProfileRepository(db, api.json)
    val deck = DeckRepository(db, api, profile, history, settings, api.json)
    val location = LocationProvider(app, settings)
    val haptics = kz.smartcity.walk.ui.fx.Haptics(app) { settings.hapticsOn }
    val intents = MutableSharedFlow<AppIntent>(replay = 1, extraBufferCapacity = 4)

    /** Одна кнопка в профиле: портрет, история, колода и кэш на устройстве. */
    suspend fun deleteAllData() {
        profile.deleteAll()
        history.deleteAll()
        db.deck().deleteAll()
        db.saved().deleteAll()
        db.events().deleteAll()
        settings.clearUserData()
        places.clear()
        app.cacheDir.deleteRecursively()
    }
}

val Context.graph: AppGraph get() = (applicationContext as App).graph
