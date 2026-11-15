package ru.gdebyl

import android.app.Application
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import ru.gdebyl.data.RetentionWorker
import androidx.room.Room
import ru.gdebyl.data.DiaryDatabase
import ru.gdebyl.data.Preferences
import ru.gdebyl.tracking.VisitDetector

class GdeBylApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("prune-points", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<RetentionWorker>(6, TimeUnit.HOURS).build())
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { database.diary().prune(System.currentTimeMillis() - 48 * 60 * 60_000L) }
    }
    val database by lazy { Room.databaseBuilder(this, DiaryDatabase::class.java, "diary.db").build() }
    val preferences by lazy { Preferences(this) }
    val detector by lazy { VisitDetector(database, preferences, getString(R.string.unnamed)) }
}
