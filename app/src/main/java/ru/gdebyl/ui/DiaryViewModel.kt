package ru.gdebyl.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.gdebyl.GdeBylApp
import ru.gdebyl.R
import ru.gdebyl.data.*
import ru.gdebyl.tracking.TrackingService
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter

fun dayStart(date: LocalDate): Long = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
fun clipped(start: Long, end: Long, from: Long, until: Long) = (minOf(end, until) - maxOf(start, from)).coerceAtLeast(0)
data class Segment(val start: Long, val end: Long, val entry: VisitWithPlace? = null)
fun timeline(visits: List<VisitWithPlace>, sessions: List<RecordingSession>, from: Long, until: Long, now: Long): List<Segment> {
    val result = mutableListOf<Segment>()
    visits.forEach { v ->
        val a = maxOf(v.visit.startTs, from); val b = minOf(v.visit.endTs ?: now, until)
        if (b > a) result += Segment(a, b, v)
    }
    sessions.forEach { s ->
        var cursor = maxOf(s.start, from)
        val end = minOf(s.end ?: now, until)
        visits.filter { it.visit.sessionId == s.id }.sortedBy { it.visit.startTs }.forEach { v ->
            val a = maxOf(v.visit.startTs, cursor).coerceAtMost(end)
            if (a > cursor) result += Segment(cursor, a)
            cursor = maxOf(cursor, minOf(v.visit.endTs ?: now, end))
        }
        if (end > cursor) result += Segment(cursor, end)
    }
    return result.sortedBy { it.start }
}
data class DiaryState(val visits: List<VisitWithPlace> = emptyList(), val places: List<Place> = emptyList(), val sessions: List<RecordingSession> = emptyList(), val settings: Settings = Settings(), val now: Long = System.currentTimeMillis())
class DiaryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GdeBylApp
    private val dao = app.database.diary()
    private val ticker = flow { while (true) { emit(System.currentTimeMillis()); delay(1_000) } }
    val state = combine(dao.observeVisits(), dao.observePlaces(), dao.observeSessions(), app.preferences.flow, ticker) { visits, places, sessions, settings, now ->
        DiaryState(visits, places, sessions, settings, now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryState(settings = app.preferences.value))
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    fun consumeMessage() { mutableMessage.value = null }
    private fun work(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { mutableMessage.value = app.getString(R.string.operation_failed) } } }
    fun start() {
        try { ContextCompat.startForegroundService(app, Intent(app, TrackingService::class.java)) }
        catch (_: RuntimeException) { app.preferences.error(app.getString(R.string.start_failed)) }
    }
    fun stop() { app.startService(Intent(app, TrackingService::class.java).setAction(TrackingService.ACTION_STOP)) }
    fun onboarded() = app.preferences.onboarded()
    fun settings(radius: Int, minutes: Int) = app.preferences.configure(radius, minutes)
    fun edit(entry: VisitWithPlace, name: String, start: Long, end: Long?) = work {
        app.database.withTransaction {
            require(dao.visit(entry.visit.id) == entry.visit)
            require(name.isNotBlank() && start <= System.currentTimeMillis() && (end == null || end > start) && (end == null || end <= System.currentTimeMillis()))
            require(dao.overlaps(entry.visit.id, start, end ?: Long.MAX_VALUE) == 0)
            // The detector owns the start of an open visit until it is closed.
            require(entry.visit.endTs != null || start == entry.visit.startTs)
            dao.rename(entry.place.id, name.trim())
            dao.editVisit(entry.visit.id, start, end)
        }
    }
    fun rename(id: Long, name: String) = work { require(name.isNotBlank()); dao.rename(id, name.trim()) }
    fun delete(entry: VisitWithPlace) = work { app.database.withTransaction { require(dao.visit(entry.visit.id)?.endTs != null); dao.deleteVisit(entry.visit.id) } }
    fun merge(source: Long, target: Long) = work {
        require(source != target)
        app.database.withTransaction { require(dao.places().any { it.id == target }); dao.moveVisits(source, target); dao.deletePlace(source) }
    }
    fun clear() = work {
        require(!app.preferences.value.recording)
        app.detector.clear()
        withContext(Dispatchers.IO) { File(app.cacheDir, "exports").deleteRecursively() }
    }
    fun export(from: Long, until: Long) = work {
        val snapshot = state.value
        val entries = snapshot.visits.filter { clipped(it.visit.startTs, it.visit.endTs ?: snapshot.now, from, until) > 0 }
        if (entries.isEmpty()) { mutableMessage.value = app.getString(R.string.export_empty); return@work }
        val file = withContext(Dispatchers.IO) {
            val dir = File(app.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.filter { snapshot.now - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
            File(dir, "gde-byl-${snapshot.now}.csv").apply {
                bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write("\uFEFF")
                    writer.appendLine(app.getString(R.string.csv_header))
                    val zone = ZoneId.systemDefault()
                    val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    entries.forEach { item ->
                        // Split at local midnight; all exported durations match clipped statistics.
                        var start = maxOf(from, item.visit.startTs)
                        val end = minOf(until, item.visit.endTs ?: snapshot.now)
                        while (start < end) {
                            val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                            val partEnd = minOf(end, dayStart(date.plusDays(1)))
                            val fields = listOf(date.toString(), item.place.name, item.place.address.orEmpty(),
                                format.format(Instant.ofEpochMilli(start).atZone(zone)), format.format(Instant.ofEpochMilli(partEnd).atZone(zone)),
                                String.format(java.util.Locale.US, "%.4f", (partEnd - start) / 60_000.0))
                            writer.appendLine(fields.joinToString(";") { csvCell(it) })
                            start = partEnd
                        }
                    }
                }
            }
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = android.content.ClipData.newRawUri("CSV", uri) }
        app.startActivity(Intent.createChooser(send, app.getString(R.string.export)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
private fun csvCell(value: String): String {
    val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
    return "\"${safe.replace("\"", "\"\"")}\""
}
