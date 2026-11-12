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

    @Insert
    suspend fun insertPerson(person: Person): Long

    @Update
    suspend fun updatePerson(person: Person)

    @Delete
    suspend fun deletePerson(person: Person)

    @Query("SELECT * FROM wear_segments WHERE personId = :personId ORDER BY startAt ASC")
    fun observeSegments(personId: Long): Flow<List<WearSegment>>

    @Query("SELECT * FROM wear_segments WHERE personId = :personId ORDER BY startAt ASC")
    suspend fun getSegments(personId: Long): List<WearSegment>

    @Query("SELECT * FROM wear_segments ORDER BY startAt ASC")
    suspend fun getAllSegments(): List<WearSegment>

    @Query("SELECT * FROM events WHERE personId = :personId ORDER BY occurredAt DESC")
    fun observeEvents(personId: Long): Flow<List<EventRecord>>

    @Query("SELECT * FROM events ORDER BY occurredAt ASC")
    suspend fun getAllEvents(): List<EventRecord>

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
}
