package ru.gdebyl.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "place")
data class Place(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String,
    val address: String? = null, val lat: Double, val lon: Double, val radiusM: Int, val createdAt: Long)
@Entity(tableName = "recording_session")
data class RecordingSession(@PrimaryKey(autoGenerate = true) val id: Long = 0, val start: Long, val end: Long? = null)
@Entity(tableName = "visit", foreignKeys = [ForeignKey(entity = Place::class, parentColumns = ["id"], childColumns = ["placeId"], onDelete = ForeignKey.CASCADE)], indices = [Index("placeId"), Index("startTs")])
data class Visit(@PrimaryKey(autoGenerate = true) val id: Long = 0, val placeId: Long, val startTs: Long, val endTs: Long? = null, val sessionId: Long)
@Entity(tableName = "location_point", indices = [Index("ts")])
data class LocationPoint(@PrimaryKey(autoGenerate = true) val id: Long = 0, val ts: Long, val lat: Double, val lon: Double, val accuracyM: Float)
// A single persisted checkpoint survives process death without replaying or duplicating visits.
@Entity(tableName = "detector_state")
data class DetectorState(@PrimaryKey val id: Int = 1, val sessionId: Long, val first: Long = 0,
    val last: Long = 0, val lat: Double = 0.0, val lon: Double = 0.0, val count: Int = 0,
    val visitId: Long? = null, val outsideTs: Long? = null, val outsideLat: Double = 0.0,
    val outsideLon: Double = 0.0, val lastSeen: Long = 0)
data class VisitWithPlace(@Embedded val visit: Visit, @Relation(parentColumn = "placeId", entityColumn = "id") val place: Place)

@Dao
interface DiaryDao {
    @Transaction @Query("SELECT * FROM visit ORDER BY startTs") fun observeVisits(): Flow<List<VisitWithPlace>>
    @Query("SELECT * FROM place ORDER BY name") fun observePlaces(): Flow<List<Place>>
    @Query("SELECT * FROM place") suspend fun places(): List<Place>
    @Query("SELECT * FROM recording_session ORDER BY start") fun observeSessions(): Flow<List<RecordingSession>>
    @Query("SELECT * FROM recording_session WHERE id=:id") suspend fun session(id: Long): RecordingSession?
    @Query("SELECT * FROM detector_state WHERE id=1") suspend fun state(): DetectorState?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun state(value: DetectorState)
    @Query("DELETE FROM detector_state") suspend fun clearState()
    @Insert suspend fun addPlace(value: Place): Long
    @Insert suspend fun addVisit(value: Visit): Long
    @Insert suspend fun addPoint(value: LocationPoint)
    @Insert suspend fun addSession(value: RecordingSession): Long
    @Query("UPDATE recording_session SET `end`=:ts WHERE id=:id AND `end` IS NULL") suspend fun closeSession(id: Long, ts: Long)
    @Query("UPDATE visit SET endTs=MAX(startTs,:ts) WHERE id=:id AND endTs IS NULL") suspend fun closeVisit(id: Long, ts: Long)
    @Query("UPDATE place SET name=:name WHERE id=:id") suspend fun rename(id: Long, name: String)
    @Query("UPDATE place SET address=:address, name=CASE WHEN name=:fallback THEN :address ELSE name END WHERE id=:id") suspend fun address(id: Long, address: String, fallback: String)
    @Query("UPDATE visit SET placeId=:target WHERE placeId=:source") suspend fun moveVisits(source: Long, target: Long)
    @Query("DELETE FROM place WHERE id=:id") suspend fun deletePlace(id: Long)
    @Query("UPDATE visit SET startTs=:start, endTs=:end WHERE id=:id") suspend fun editVisit(id: Long, start: Long, end: Long?)
    @Query("SELECT COUNT(*) FROM visit WHERE id != :id AND startTs < :end AND COALESCE(endTs,9223372036854775807) > :start") suspend fun overlaps(id: Long, start: Long, end: Long): Int
    @Query("SELECT * FROM visit WHERE id=:id") suspend fun visit(id: Long): Visit?
    @Query("DELETE FROM visit WHERE id=:id") suspend fun deleteVisit(id: Long)
    @Query("DELETE FROM location_point WHERE ts < :before") suspend fun prune(before: Long)
    @Query("DELETE FROM location_point") suspend fun clearPoints()
    @Query("DELETE FROM visit") suspend fun clearVisits()
    @Query("DELETE FROM place") suspend fun clearPlaces()
    @Query("DELETE FROM recording_session") suspend fun clearSessions()
}
@Database(entities = [Place::class, Visit::class, LocationPoint::class, DetectorState::class, RecordingSession::class], version = 1, exportSchema = true)
abstract class DiaryDatabase : RoomDatabase() { abstract fun diary(): DiaryDao }
