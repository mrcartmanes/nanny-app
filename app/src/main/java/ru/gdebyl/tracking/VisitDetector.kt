package ru.gdebyl.tracking

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.gdebyl.data.*
import kotlin.math.*

fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
    return 6371000 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
class VisitDetector(private val db: DiaryDatabase, private val prefs: Preferences, private val unnamed: String) {
    private val mutex = Mutex()
    private val dao = db.diary()
    suspend fun begin(now: Long) = mutex.withLock { db.withTransaction {
        if (dao.state() == null) dao.state(DetectorState(sessionId = dao.addSession(RecordingSession(start = now))))
    } }
    suspend fun stop(now: Long) = mutex.withLock { db.withTransaction {
        dao.state()?.let { s ->
            s.visitId?.let { dao.closeVisit(it, if (now - s.lastSeen >= 30 * 60_000) s.last else now) }
            dao.closeSession(s.sessionId, now)
        }
        dao.clearState()
    } }
    suspend fun expire(now: Long) = mutex.withLock { db.withTransaction {
        dao.prune(now - 48 * 60 * 60_000L)
        dao.state()?.let { s -> if (s.lastSeen > 0 && now - s.lastSeen >= 30 * 60_000) {
            s.visitId?.let { dao.closeVisit(it, s.last) }
            dao.closeSession(s.sessionId, s.lastSeen)
            dao.state(DetectorState(sessionId = s.sessionId, lastSeen = s.lastSeen))
        } }
    } }
    suspend fun point(p: LocationPoint): Boolean = mutex.withLock { db.withTransaction {
        if (!p.accuracyM.isFinite() || p.accuracyM > 100 || p.accuracyM < 0 || !p.lat.isFinite() || !p.lon.isFinite() || p.lat !in -90.0..90.0 || p.lon !in -180.0..180.0) return@withTransaction false
        var s = dao.state() ?: return@withTransaction false
        if (p.ts < (dao.session(s.sessionId)?.start ?: p.ts) || p.ts <= s.lastSeen) return@withTransaction s.visitId != null && s.outsideTs == null
        dao.addPoint(p)
        dao.prune(p.ts - 48 * 60 * 60_000L)
        if (s.lastSeen > 0 && p.ts - s.lastSeen >= 30 * 60_000) {
            s.visitId?.let { dao.closeVisit(it, s.last) }
            dao.closeSession(s.sessionId, s.lastSeen)
            s = DetectorState(sessionId = dao.addSession(RecordingSession(start = p.ts)))
        }
        if (s.count == 0) {
            s = s.copy(first = p.ts, last = p.ts, lat = p.lat, lon = p.lon, count = 1)
        } else if (distance(s.lat, s.lon, p.lat, p.lon) <= prefs.value.radius) {
            s = s.copy(last = p.ts, lat = (s.lat * s.count + p.lat) / (s.count + 1), lon = (s.lon * s.count + p.lon) / (s.count + 1), count = s.count + 1, outsideTs = null)
        } else if (s.outsideTs == null) {
            s = s.copy(outsideTs = p.ts, outsideLat = p.lat, outsideLon = p.lon)
        } else {
            s.visitId?.let { dao.closeVisit(it, s.last) }
            val clustered = distance(s.outsideLat, s.outsideLon, p.lat, p.lon) <= prefs.value.radius
            s = DetectorState(sessionId = s.sessionId, first = if (clustered) s.outsideTs!! else p.ts,
                last = p.ts, lat = if (clustered) (s.outsideLat + p.lat) / 2 else p.lat,
                lon = if (clustered) (s.outsideLon + p.lon) / 2 else p.lon, count = if (clustered) 2 else 1)
        }
        if (s.visitId == null && s.last - s.first >= prefs.value.minutes * 60_000L) {
            val place = dao.places().map { it to distance(it.lat, it.lon, s.lat, s.lon) }.filter { it.second <= it.first.radiusM }.minByOrNull { it.second }?.first
            val placeId = place?.id ?: dao.addPlace(Place(name = unnamed, lat = s.lat, lon = s.lon, radiusM = prefs.value.radius, createdAt = p.ts))
            s = s.copy(visitId = dao.addVisit(Visit(placeId = placeId, startTs = s.first, sessionId = s.sessionId)))
        }
        dao.state(s.copy(lastSeen = p.ts))
        s.visitId != null && s.outsideTs == null
    } }
    suspend fun clear() = mutex.withLock { db.withTransaction {
        dao.clearState(); dao.clearPoints(); dao.clearVisits(); dao.clearPlaces(); dao.clearSessions()
    } }
}
