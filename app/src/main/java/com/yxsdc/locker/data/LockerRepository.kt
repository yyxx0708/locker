package com.yxsdc.locker.data

import androidx.room.withTransaction
import com.yxsdc.locker.domain.generateFiveDigitNumber
import kotlinx.coroutines.flow.Flow
import java.security.SecureRandom
import java.time.LocalDate
import java.util.UUID

class LockerRepository(
    private val db: AppDatabase,
    private val dao: LockerDao,
) {
    private val random = SecureRandom()

    val persons: Flow<List<Person>> = dao.observePersons()

    val eventTypes: Flow<List<EventTypeEntity>> = dao.observeEventTypes()

    fun segments(personId: Long): Flow<List<WearSegment>> = dao.observeSegments(personId)

    fun events(personId: Long): Flow<List<EventRecord>> = dao.observeEvents(personId)

    fun observeDailyPhotos(
        personId: Long,
        startEpochDay: Long,
        endEpochDay: Long,
    ): Flow<List<DailyPhoto>> =
        dao.observeDailyPhotos(personId, startEpochDay, endEpochDay)

    fun observeDailyPhoto(personId: Long, epochDay: Long): Flow<List<DailyPhoto>> =
        dao.observeDailyPhoto(personId, epochDay)

    fun observeDailyRandomNumber(
        personId: Long,
        epochDay: Long,
    ): Flow<List<DailyRandomNumber>> =
        dao.observeDailyRandomNumber(personId, epochDay)

    suspend fun ensureDailyRandomNumber(personId: Long, epochDay: Long): String =
        db.withTransaction {
            val existing = dao.getDailyRandomNumber(personId, epochDay)
            if (existing != null) {
                return@withTransaction existing.number
            }

            val taken = dao.getRandomNumbersForDay(epochDay).toSet()
            var result: String? = null
            for (attempt in 0 until 20) {
                val candidate = generateFiveDigitNumber(random)
                if (candidate in taken) {
                    continue
                }
                val inserted = dao.insertDailyRandomNumber(
                    DailyRandomNumber(
                        personId = personId,
                        epochDay = epochDay,
                        number = candidate,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                if (inserted != -1L) {
                    result = candidate
                    break
                }
                val own = dao.getDailyRandomNumber(personId, epochDay)?.number
                if (own != null) {
                    result = own
                    break
                }
            }
            result ?: error("无法生成不重复的随机数")
        }

    suspend fun setDailyPhoto(personId: Long, epochDay: Long, jpeg: ByteArray) {
        require(epochDay <= LocalDate.now().toEpochDay()) {
            "不能上传未来日期的照片"
        }
        dao.upsertDailyPhoto(
            DailyPhoto(
                personId = personId,
                epochDay = epochDay,
                jpeg = jpeg,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun deleteDailyPhoto(personId: Long, epochDay: Long) {
        dao.deleteDailyPhoto(personId, epochDay)
    }

    suspend fun addPerson(name: String): Long {
        val trimmed = normalizePersonName(name)
        return db.withTransaction {
            require(dao.getPersons().none { it.name == trimmed }) { "已存在同名人物：$trimmed" }
            dao.insertPerson(Person(name = trimmed))
        }
    }

    suspend fun renamePerson(id: Long, name: String) {
        val trimmed = normalizePersonName(name)
        db.withTransaction {
            val persons = dao.getPersons()
            val person = persons.firstOrNull { it.id == id } ?: error("人物不存在")
            require(persons.none { it.id != id && it.name == trimmed }) {
                "已存在同名人物：$trimmed"
            }
            dao.updatePerson(person.copy(name = trimmed))
        }
    }

    suspend fun deletePerson(person: Person) {
        db.withTransaction {
            require(dao.getPersons().size > 1) { "至少保留一个人物" }
            dao.deleteSegmentsForPerson(person.id)
            dao.deleteEventsForPerson(person.id)
            dao.deleteDailyPhotosForPerson(person.id)
            dao.deleteDailyRandomNumbersForPerson(person.id)
            dao.deletePerson(person)
        }
    }

    suspend fun addEventType(name: String, colorArgb: Long): Long {
        val trimmed = normalizeEventTypeName(name)
        return db.withTransaction {
            val types = dao.getEventTypes()
            require(types.none { it.name == trimmed }) {
                "已存在同名事件类型：$trimmed"
            }
            val nextOrder = (types.maxOfOrNull { it.sortOrder } ?: -1) + 1
            dao.insertEventType(
                EventTypeEntity(
                    typeKey = "custom_${UUID.randomUUID()}",
                    name = trimmed,
                    colorArgb = colorArgb or OPAQUE_ALPHA,
                    sortOrder = nextOrder,
                    builtIn = false,
                )
            )
        }
    }

    suspend fun updateEventType(typeKey: String, name: String, colorArgb: Long) {
        val trimmed = normalizeEventTypeName(name)
        db.withTransaction {
            val types = dao.getEventTypes()
            val type = types.firstOrNull { it.typeKey == typeKey }
                ?: error("事件类型不存在")
            require(types.none { it.typeKey != typeKey && it.name == trimmed }) {
                "已存在同名事件类型：$trimmed"
            }
            dao.updateEventType(
                type.copy(name = trimmed, colorArgb = colorArgb or OPAQUE_ALPHA)
            )
        }
    }

    suspend fun deleteEventType(type: EventTypeEntity) {
        db.withTransaction {
            val current = dao.getEventTypes().firstOrNull { it.typeKey == type.typeKey }
                ?: error("事件类型不存在")
            require(!current.builtIn) { "内置事件类型不能删除" }
            dao.deleteEventsOfType(current.typeKey)
            dao.deleteEventType(current)
        }
    }

    suspend fun countEventsOfType(typeKey: String): Int = dao.countEventsOfType(typeKey)

    suspend fun startWearing(personId: Long, now: Long) {
        db.withTransaction {
            validateSegment(personId = personId, id = null, startAt = now, endAt = null)
            dao.insertSegment(WearSegment(personId = personId, startAt = now))
        }
    }

    suspend fun stopWearing(personId: Long, now: Long) {
        db.withTransaction {
            val active = dao.getSegments(personId).lastOrNull { it.endAt == null }
                ?: error("当前人物未佩戴")
            require(now >= active.startAt) { "取下时间不能早于戴上时间" }
            dao.updateSegment(active.copy(endAt = now))
        }
    }

    suspend fun addManualSegment(personId: Long, startAt: Long, endAt: Long?) {
        db.withTransaction {
            validateSegment(personId = personId, id = null, startAt = startAt, endAt = endAt)
            dao.insertSegment(WearSegment(personId = personId, startAt = startAt, endAt = endAt))
        }
    }

    suspend fun updateSegment(id: Long, personId: Long, startAt: Long, endAt: Long?) {
        db.withTransaction {
            val existing = dao.getSegments(personId).firstOrNull { it.id == id }
                ?: error("分段不存在")
            validateSegment(personId = personId, id = id, startAt = startAt, endAt = endAt)
            dao.updateSegment(existing.copy(startAt = startAt, endAt = endAt))
        }
    }

    suspend fun deleteSegment(segment: WearSegment) {
        dao.deleteSegment(segment)
    }

    private suspend fun validateSegment(
        personId: Long,
        id: Long?,
        startAt: Long,
        endAt: Long?,
    ) {
        require(endAt == null || endAt >= startAt) { "取下时间不能早于戴上时间" }

        val others = dao.getSegments(personId).filter { it.id != id }
        val candidate = WearSegment(
            id = id ?: -1L,
            personId = personId,
            startAt = startAt,
            endAt = endAt,
        )
        val sorted = (others + candidate).sortedBy { it.startAt }

        var previousEnd = Long.MIN_VALUE
        for (segment in sorted) {
            val segmentEnd = segment.endAt ?: Long.MAX_VALUE
            require(segmentEnd >= segment.startAt) { "分段结束时间不能早于开始时间" }
            require(segment.startAt >= previousEnd) { "同一人物的分段之间不能重叠" }
            previousEnd = segmentEnd
        }
        require(sorted.count { it.endAt == null } <= 1) {
            "同一人物同时只能有一个佩戴中的分段"
        }
    }

    suspend fun addEvent(
        personId: Long,
        typeKey: String,
        occurredAt: Long,
        note: String?,
    ): Long = db.withTransaction {
        requireEventType(typeKey)
        dao.insertEvent(
            EventRecord(
                personId = personId,
                type = typeKey,
                occurredAt = occurredAt,
                note = note?.takeIf { it.isNotBlank() },
            )
        )
    }

    suspend fun deleteEventById(id: Long) {
        dao.deleteEventById(id)
    }

    suspend fun updateEvent(
        id: Long,
        personId: Long,
        typeKey: String,
        occurredAt: Long,
        note: String?,
    ) {
        db.withTransaction {
            requireEventType(typeKey)
            dao.updateEvent(
                EventRecord(
                    id = id,
                    personId = personId,
                    type = typeKey,
                    occurredAt = occurredAt,
                    note = note?.takeIf { it.isNotBlank() },
                )
            )
        }
    }

    private suspend fun requireEventType(typeKey: String) {
        require(dao.getEventTypes().any { it.typeKey == typeKey }) {
            "事件类型不存在"
        }
    }

    suspend fun deleteEvent(event: EventRecord) {
        dao.deleteEvent(event)
    }

    suspend fun exportAll(): BackupJson.BackupData = db.withTransaction {
        BackupJson.BackupData(
            persons = dao.getPersons(),
            eventTypes = dao.getEventTypes(),
            segments = dao.getAllSegments(),
            events = dao.getAllEvents(),
            dailyRandomNumbers = dao.getAllDailyRandomNumbers(),
        )
    }

    suspend fun importAll(data: BackupJson.BackupData) {
        db.withTransaction {
            dao.clearEvents()
            dao.clearSegments()
            dao.clearDailyPhotos()
            dao.clearDailyRandomNumbers()
            dao.clearPersons()
            dao.clearEventTypes()

            data.eventTypes.forEach { type ->
                dao.insertEventType(type.copy(id = 0))
            }

            val idMap = HashMap<Long, Long>()
            data.persons.forEach { person ->
                val newId = dao.insertPerson(
                    Person(
                        name = person.name.trim(),
                        createdAt = person.createdAt,
                    )
                )
                idMap[person.id] = newId
            }

            val segments = data.segments.map {
                it.copy(id = 0, personId = idMap.getValue(it.personId))
            }
            if (segments.isNotEmpty()) dao.insertSegments(segments)

            val events = data.events.map {
                it.copy(id = 0, personId = idMap.getValue(it.personId))
            }
            if (events.isNotEmpty()) dao.insertEvents(events)

            val randomNumbers = data.dailyRandomNumbers.map {
                it.copy(personId = idMap.getValue(it.personId))
            }
            if (randomNumbers.isNotEmpty()) {
                dao.insertDailyRandomNumbers(randomNumbers)
            }
        }
    }

    private fun normalizePersonName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "人物名称不能为空" }
        require(trimmed.length <= 20) { "人物名称最多 20 个字符" }
        return trimmed
    }

    private fun normalizeEventTypeName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "事件名称不能为空" }
        require(trimmed.length <= 20) { "事件名称最多 20 个字符" }
        return trimmed
    }

    private companion object {
        const val OPAQUE_ALPHA = 0xFF000000L
    }
}
