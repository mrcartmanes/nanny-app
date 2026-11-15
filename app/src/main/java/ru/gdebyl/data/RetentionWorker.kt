package ru.gdebyl.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ru.gdebyl.GdeBylApp

class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as GdeBylApp).database.diary().prune(System.currentTimeMillis() - 48 * 60 * 60_000L)
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
