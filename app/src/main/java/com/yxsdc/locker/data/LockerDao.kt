package com.yxsdc.locker.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LockerDao {
    @Query("SELECT * FROM persons ORDER BY id ASC")
    fun observePersons(): Flow<List<Person>>

    @Query("SELECT * FROM persons ORDER BY id ASC")
    suspend fun getPersons(): List<Person>

    @Query("SELECT * FROM persons WHERE id = :id LIMIT 1")
    suspend fun getPerson(id: Long): Person?

    @Insert
    suspend fun insertPerson(person: Person): Long

    @Update
    suspend fun updatePerson(person: Person)

    @Delete
    suspend fun deletePerson(person: Person)

    @Query(
        "SELECT * FROM wear_segments WHERE personId = :personId AND endAt IS NULL " +
            "ORDER BY startAt DESC LIMIT 1"
    )
    suspend fun getActiveSegment(personId: Long): WearSegment?

    @Query(
        "SELECT * FROM wear_segments WHERE personId = :personId AND endAt IS NULL " +
            "ORDER BY startAt DESC LIMIT 1"
    )
    fun observeActiveSegment(personId: Long): Flow<List<WearSegment>>

    @Query("SELECT * FROM wear_segments WHERE id = :id AND personId = :personId LIMIT 1")
    suspend fun getSegment(id: Long, personId: Long): WearSegment?

    @Query(
        "SELECT * FROM wear_segments WHERE personId = :personId " +
            "AND startAt BETWEEN :from AND :to ORDER BY startAt ASC"
    )
    suspend fun getSegmentsInRange(personId: Long, from: Long, to: Long): List<WearSegment>

    @Query(
        "SELECT * FROM wear_segments WHERE personId = :personId " +
            "AND startAt < :endExclusive AND (endAt IS NULL OR endAt > :start) " +
            "ORDER BY startAt ASC"
    )
    suspend fun getSegmentsOverlapping(
        personId: Long,
        start: Long,
        endExclusive: Long,
    ): List<WearSegment>

    @Query(
        "SELECT * FROM wear_segments WHERE personId = :personId " +
            "AND startAt < :endExclusive AND (endAt IS NULL OR endAt > :start) " +
            "ORDER BY startAt ASC"
    )
    fun observeSegmentsOverlapping(
        personId: Long,
        start: Long,
        endExclusive: Long,
    ): Flow<List<WearSegment>>

    @Query("SELECT COUNT(*) FROM wear_segments WHERE personId = :personId")
    fun observeSegmentCount(personId: Long): Flow<Int>

    @Query("SELECT * FROM wear_segments ORDER BY startAt ASC")
    suspend fun getAllSegments(): List<WearSegment>

    @Query("SELECT * FROM events ORDER BY occurredAt ASC")
    suspend fun getAllEvents(): List<EventRecord>

    @Query(
        "SELECT * FROM events WHERE personId = :personId " +
            "AND occurredAt BETWEEN :from AND :to ORDER BY occurredAt ASC"
    )
    suspend fun getEventsInRange(personId: Long, from: Long, to: Long): List<EventRecord>

    @Query(
        "SELECT * FROM events WHERE personId = :personId " +
            "AND occurredAt >= :from AND occurredAt < :toExclusive ORDER BY occurredAt ASC"
    )
    fun observeEventsInRange(
        personId: Long,
        from: Long,
        toExclusive: Long,
    ): Flow<List<EventRecord>>

    @Query("SELECT COUNT(*) FROM events WHERE personId = :personId")
    fun observeEventCount(personId: Long): Flow<Int>

    @Query(
        "SELECT * FROM daily_photos " +
            "WHERE personId = :personId AND epochDay BETWEEN :startEpochDay AND :endEpochDay " +
            "ORDER BY epochDay ASC"
    )
    fun observeDailyPhotos(
        personId: Long,
        startEpochDay: Long,
        endEpochDay: Long,
    ): Flow<List<DailyPhoto>>

    @Query(
        "SELECT * FROM daily_photos WHERE personId = :personId AND epochDay = :epochDay LIMIT 1"
    )
    fun observeDailyPhoto(personId: Long, epochDay: Long): Flow<List<DailyPhoto>>

    @Query(
        "SELECT * FROM daily_photos " +
            "WHERE personId = :personId AND epochDay BETWEEN :startEpochDay AND :endEpochDay " +
            "ORDER BY epochDay ASC"
    )
    suspend fun getDailyPhotosInRange(
        personId: Long,
        startEpochDay: Long,
        endEpochDay: Long,
    ): List<DailyPhoto>

    @Query(
        "SELECT epochDay, updatedAt, fingerprint, LENGTH(jpeg) AS byteSize FROM daily_photos " +
            "WHERE personId = :personId AND epochDay BETWEEN :startEpochDay AND :endEpochDay " +
            "ORDER BY epochDay ASC"
    )
    suspend fun getDailyPhotoMetas(
        personId: Long,
        startEpochDay: Long,
        endEpochDay: Long,
    ): List<DailyPhotoMeta>

    @Query("SELECT * FROM daily_photos WHERE personId = :personId AND epochDay = :epochDay LIMIT 1")
    suspend fun getDailyPhoto(personId: Long, epochDay: Long): DailyPhoto?

    /** 只判断当天是否有照片，不读取 JPEG 字节。 */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM daily_photos " +
            "WHERE personId = :personId AND epochDay = :epochDay)"
    )
    suspend fun hasDailyPhoto(personId: Long, epochDay: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyPhoto(photo: DailyPhoto)

    @Query("DELETE FROM daily_photos WHERE personId = :personId AND epochDay = :epochDay")
    suspend fun deleteDailyPhoto(personId: Long, epochDay: Long)

    @Query("DELETE FROM daily_photos WHERE personId = :personId")
    suspend fun deleteDailyPhotosForPerson(personId: Long)

    @Query("DELETE FROM daily_photos")
    suspend fun clearDailyPhotos()

    @Query(
        "SELECT * FROM daily_random_numbers " +
            "WHERE personId = :personId AND epochDay = :epochDay LIMIT 1"
    )
    fun observeDailyRandomNumber(
        personId: Long,
        epochDay: Long,
    ): Flow<List<DailyRandomNumber>>

    @Query(
        "SELECT * FROM daily_random_numbers " +
            "WHERE personId = :personId AND epochDay = :epochDay LIMIT 1"
    )
    suspend fun getDailyRandomNumber(
        personId: Long,
        epochDay: Long,
    ): DailyRandomNumber?

    @Query("SELECT number FROM daily_random_numbers WHERE epochDay = :epochDay")
    suspend fun getRandomNumbersForDay(epochDay: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDailyRandomNumber(number: DailyRandomNumber): Long

    @Query("SELECT * FROM daily_random_numbers ORDER BY epochDay ASC, personId ASC")
    suspend fun getAllDailyRandomNumbers(): List<DailyRandomNumber>

    @Query(
        "SELECT * FROM daily_random_numbers WHERE personId = :personId " +
            "AND epochDay BETWEEN :startEpochDay AND :endEpochDay ORDER BY epochDay ASC"
    )
    fun observeDailyRandomNumbersInRange(
        personId: Long,
        startEpochDay: Long,
        endEpochDay: Long,
    ): Flow<List<DailyRandomNumber>>

    @Query(
        "SELECT * FROM daily_random_numbers WHERE personId = :personId " +
            "AND epochDay BETWEEN :startEpochDay AND :endEpochDay ORDER BY epochDay ASC"
    )
    suspend fun getDailyRandomNumbersInRange(
        personId: Long,
        startEpochDay: Long,
        endEpochDay: Long,
    ): List<DailyRandomNumber>

    @Query(
        "SELECT * FROM daily_random_numbers WHERE epochDay = :epochDay " +
            "AND number = :number AND personId != :personId LIMIT 1"
    )
    suspend fun findNumberConflict(
        epochDay: Long,
        number: String,
        personId: Long,
    ): DailyRandomNumber?

    @Update
    suspend fun updateDailyRandomNumber(entry: DailyRandomNumber)

    @Query("DELETE FROM daily_random_numbers WHERE personId = :personId AND epochDay = :epochDay")
    suspend fun deleteDailyRandomNumber(personId: Long, epochDay: Long)

    @Query("DELETE FROM daily_random_numbers WHERE personId = :personId")
    suspend fun deleteDailyRandomNumbersForPerson(personId: Long)

    @Query("DELETE FROM daily_random_numbers")
    suspend fun clearDailyRandomNumbers()

    @Insert
    suspend fun insertDailyRandomNumbers(numbers: List<DailyRandomNumber>)

    @Query("SELECT * FROM event_types ORDER BY sortOrder ASC, id ASC")
    fun observeEventTypes(): Flow<List<EventTypeEntity>>

    @Query("SELECT * FROM event_types ORDER BY sortOrder ASC, id ASC")
    suspend fun getEventTypes(): List<EventTypeEntity>

    @Insert
    suspend fun insertEventType(type: EventTypeEntity): Long

    @Update
    suspend fun updateEventType(type: EventTypeEntity)

    @Delete
    suspend fun deleteEventType(type: EventTypeEntity)

    @Query("SELECT COUNT(*) FROM events WHERE type = :typeKey")
    suspend fun countEventsOfType(typeKey: String): Int

    @Query("DELETE FROM events WHERE type = :typeKey")
    suspend fun deleteEventsOfType(typeKey: String)

    @Insert
    suspend fun insertSegment(segment: WearSegment): Long

    @Update
    suspend fun updateSegment(segment: WearSegment)

    @Delete
    suspend fun deleteSegment(segment: WearSegment)

    @Query("DELETE FROM wear_segments WHERE personId = :personId")
    suspend fun deleteSegmentsForPerson(personId: Long)

    @Insert
    suspend fun insertSegments(segments: List<WearSegment>)

    @Insert
    suspend fun insertEvent(event: EventRecord): Long

    @Update
    suspend fun updateEvent(event: EventRecord)

    @Delete
    suspend fun deleteEvent(event: EventRecord)

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteEventById(id: Long)

    @Query("DELETE FROM events WHERE personId = :personId")
    suspend fun deleteEventsForPerson(personId: Long)

    @Insert
    suspend fun insertEvents(events: List<EventRecord>)

    @Query("DELETE FROM wear_segments")
    suspend fun clearSegments()

    @Query("DELETE FROM events")
    suspend fun clearEvents()

    @Query("DELETE FROM persons")
    suspend fun clearPersons()

    @Query("DELETE FROM event_types")
    suspend fun clearEventTypes()

    @Insert
    suspend fun insertEventTypes(types: List<EventTypeEntity>)

    @Query("SELECT * FROM sync_identities WHERE personId = :personId LIMIT 1")
    suspend fun getSyncIdentityForPerson(personId: Long): SyncIdentity?

    @Query("SELECT * FROM sync_identities WHERE identityId = :identityId LIMIT 1")
    suspend fun getSyncIdentity(identityId: String): SyncIdentity?

    @Query("SELECT * FROM sync_identities")
    suspend fun getAllSyncIdentities(): List<SyncIdentity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncIdentity(identity: SyncIdentity)

    @Query("DELETE FROM sync_identities WHERE personId = :personId")
    suspend fun deleteSyncIdentityForPerson(personId: Long)

    @Query("DELETE FROM sync_identities")
    suspend fun clearSyncIdentities()
}
