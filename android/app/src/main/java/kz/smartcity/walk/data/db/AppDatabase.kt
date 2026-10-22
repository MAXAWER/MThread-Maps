package kz.smartcity.walk.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ProfileFact::class, CategoryCounter::class, Visit::class, PendingEvent::class, DeckCardEntity::class, SavedPlace::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profile(): ProfileDao
    abstract fun visits(): VisitDao
    abstract fun events(): EventDao
    abstract fun deck(): DeckDao
    abstract fun saved(): SavedDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "walk.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
