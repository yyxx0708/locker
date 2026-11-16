package com.yxsdc.locker.data

import androidx.room.withTransaction
import com.yxsdc.locker.domain.generateFiveDigitNumber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class LockerRepository(
    private val db: AppDatabase,
    private val dao: LockerDao,
) {
    private val random = SecureRandom()

    val persons: Flow<List<Person>> = dao.observePersons()

    val eventTypes: Flow<List<EventTypeEntity>> = dao.observeEventTypes()

    fun observeActiveSegment(personId: Long): Flow<WearSegment?> =
        dao.observeActiveSegment(personId).map { it.firstOrNull() }

    fun observeSegmentCount(personId: Long): Flow<Int> = dao.observeSegmentCount(personId)

    fun observeEventCount(personId: Long): Flow<Int> = dao.observeEventCount(personId)

    fun observeSegmentsOverlapping(
        personId: Long,
        start: Long,
        endExclusive: Long,
    ): Flow<List<WearSegment>> = dao.observeSegmentsOverlapping(personId, start, endExclusive)

    fun observeEventsInRange(
        personId: Long,
        from: Long,
        toExclusive: Long,
    ): Flow<List<EventRecord>> = dao.observeEventsInRange(personId, from, toExclusive)

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
                fingerprint = DailyReport.photoFingerprint(jpeg),
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
            dao.deleteSyncIdentityForPerson(person.id)
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
            val active = dao.getActiveSegment(personId)
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
            val existing = dao.getSegment(id, personId)
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

        // 只取与该区间相交的分段，避免每次锁上/开锁都读取并排序全部分段
        val overlapping = dao.getSegmentsOverlapping(
            personId = personId,
            start = startAt,
            endExclusive = endAt ?: Long.MAX_VALUE,
        ).filter { it.id != id }
        require(overlapping.isEmpty()) { "同一人物的分段之间不能重叠" }
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
            dao.clearSyncIdentities()
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

    // ---- 掌管者同步 ----

    suspend fun getSyncIdentityForPerson(personId: Long): SyncIdentity? =
        dao.getSyncIdentityForPerson(personId)

    suspend fun getSyncIdentity(identityId: String): SyncIdentity? =
        dao.getSyncIdentity(identityId)

    suspend fun getAllSyncIdentities(): List<SyncIdentity> = dao.getAllSyncIdentities()

    suspend fun ensureSyncIdentity(person: Person): SyncIdentity {
        dao.getSyncIdentityForPerson(person.id)?.let { return it }
        val identity = SyncIdentity(
            identityId = UUID.randomUUID().toString(),
            personId = person.id,
            displayName = person.name,
            code = SyncCrypto.generateCode(),
            codeSalt = SyncCrypto.newSalt(),
            createdAt = System.currentTimeMillis(),
        )
        dao.upsertSyncIdentity(identity)
        return identity
    }

    suspend fun regenerateSyncCode(identity: SyncIdentity): SyncIdentity {
        val updated = identity.copy(
            code = SyncCrypto.generateCode(),
            codeSalt = SyncCrypto.newSalt(),
        )
        dao.upsertSyncIdentity(updated)
        return updated
    }

    suspend fun markIdentityExported(identityId: String, at: Long = System.currentTimeMillis()) {
        val identity = dao.getSyncIdentity(identityId) ?: return
        dao.upsertSyncIdentity(identity.copy(lastExportedAt = at))
    }

    suspend fun collectSyncExport(
        person: Person,
        fromEpochDay: Long,
        toEpochDay: Long,
    ): SyncExportData {
        val zone = ZoneId.systemDefault()
        val fromMillis = LocalDate.ofEpochDay(fromEpochDay)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val toMillis = LocalDate.ofEpochDay(toEpochDay).plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val identity = ensureSyncIdentity(person)
        // 用"与窗口相交"而不是"开始时间落在窗口内"：跨窗口起点的分段也要带上，
        // 否则 0 点前锁上、早上开锁的这一天会缺少分段。
        val segments = dao.getSegmentsOverlapping(
            person.id,
            fromMillis,
            toMillis + 1,
        )
        val events = dao.getEventsInRange(person.id, fromMillis, toMillis)
        // 只取照片元信息，JPEG 字节留到打包时逐张读取
        val photos = photoMetas(person.id, fromEpochDay, toEpochDay)
        val numbers = dao.getDailyRandomNumbersInRange(person.id, fromEpochDay, toEpochDay)
        val usedTypes = events.map { it.type }.toSet()
        val types = dao.getEventTypes().filter { it.typeKey in usedTypes }
        return SyncExportData(identity, person, types, segments, events, numbers, photos)
    }

    suspend fun createPersonForImport(name: String): Long {
        val trimmed = normalizePersonName(name)
        return dao.insertPerson(Person(name = trimmed, createdAt = System.currentTimeMillis()))
    }

    suspend fun bindSyncIdentity(
        manifest: SyncPackage.Manifest,
        code: String,
        personId: Long,
    ): SyncIdentity {
        val identity = SyncIdentity(
            identityId = manifest.identityId,
            personId = personId,
            displayName = manifest.identityName,
            code = code,
            codeSalt = manifest.codeSalt,
            createdAt = System.currentTimeMillis(),
        )
        dao.upsertSyncIdentity(identity)
        return identity
    }

    /**
     * 应用勾选的差异。整体在事务内执行；号码撞全局唯一约束时跳过并返回被跳过的日期描述。
     */
    suspend fun applySyncImport(
        personId: Long,
        payloadTypes: List<EventTypeEntity>,
        selected: List<SyncChange>,
        loadPhoto: (epochDay: Long) -> ByteArray? = { null },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<String> = db.withTransaction {
        val existingTypes = dao.getEventTypes().map { it.typeKey }.toHashSet()
        selected.mapNotNull { it.incomingEvent?.type }.toSet()
            .filter { it !in existingTypes }
            .forEach { typeKey ->
                payloadTypes.firstOrNull { it.typeKey == typeKey }?.let {
                    dao.insertEventType(it.copy(id = 0))
                }
            }

        val skipped = mutableListOf<String>()
        selected.forEachIndexed { index, change ->
            when (change.category) {
                SyncCategory.SEGMENT -> when (change.action) {
                    SyncAction.ADD -> {
                        val incoming = change.incomingSegment ?: return@forEachIndexed
                        dao.insertSegment(
                            WearSegment(
                                personId = personId,
                                startAt = incoming.startAt,
                                endAt = incoming.endAt,
                            )
                        )
                    }

                    SyncAction.REMOVE -> change.localSegment?.let { dao.deleteSegment(it) }

                    SyncAction.MODIFY -> {
                        val local = change.localSegment ?: return@forEachIndexed
                        val incoming = change.incomingSegment ?: return@forEachIndexed
                        dao.updateSegment(local.copy(endAt = incoming.endAt))
                    }
                }

                SyncCategory.EVENT -> when (change.action) {
                    SyncAction.ADD -> {
                        val incoming = change.incomingEvent ?: return@forEachIndexed
                        dao.insertEvent(
                            EventRecord(
                                personId = personId,
                                type = incoming.type,
                                occurredAt = incoming.occurredAt,
                                note = incoming.note?.takeIf { it.isNotBlank() },
                            )
                        )
                    }

                    SyncAction.REMOVE -> change.localEvent?.let { dao.deleteEvent(it) }

                    SyncAction.MODIFY -> {
                        val local = change.localEvent ?: return@forEachIndexed
                        val incoming = change.incomingEvent ?: return@forEachIndexed
                        dao.updateEvent(
                            local.copy(
                                type = incoming.type,
                                occurredAt = incoming.occurredAt,
                                note = incoming.note?.takeIf { it.isNotBlank() },
                            )
                        )
                    }
                }

                SyncCategory.PHOTO -> when (change.action) {
                    SyncAction.ADD, SyncAction.MODIFY -> {
                        val photo = change.incomingPhoto ?: return@forEachIndexed
                        val jpeg = loadPhoto(photo.epochDay) ?: return@forEachIndexed
                        dao.upsertDailyPhoto(
                            DailyPhoto(
                                personId = personId,
                                epochDay = photo.epochDay,
                                jpeg = jpeg,
                                updatedAt = System.currentTimeMillis(),
                                fingerprint = photo.fingerprint.takeIf { it.isNotEmpty() }
                                    ?: DailyReport.photoFingerprint(jpeg),
                            )
                        )
                    }

                    SyncAction.REMOVE -> change.localPhotoDay?.let {
                        dao.deleteDailyPhoto(personId, it)
                    }
                }

                SyncCategory.RANDOM_NUMBER -> {
                    val incoming = change.incomingNumber
                    when (change.action) {
                        SyncAction.ADD -> if (incoming != null) {
                            val conflict = dao.findNumberConflict(
                                incoming.epochDay, incoming.number, personId
                            ) != null
                            if (conflict) {
                                skipped += describeDay(incoming.epochDay)
                            } else {
                                val inserted = dao.insertDailyRandomNumber(
                                    DailyRandomNumber(
                                        personId = personId,
                                        epochDay = incoming.epochDay,
                                        number = incoming.number,
                                        createdAt = incoming.createdAt,
                                    )
                                )
                                if (inserted == -1L) skipped += describeDay(incoming.epochDay)
                            }
                        }

                        SyncAction.MODIFY -> {
                            val local = change.localNumber
                            if (local != null && incoming != null) {
                                val conflict = dao.findNumberConflict(
                                    incoming.epochDay, incoming.number, personId
                                ) != null
                                if (conflict) {
                                    skipped += describeDay(incoming.epochDay)
                                } else {
                                    dao.updateDailyRandomNumber(local.copy(number = incoming.number))
                                }
                            }
                        }

                        SyncAction.REMOVE -> change.localNumber?.let {
                            dao.deleteDailyRandomNumber(personId, it.epochDay)
                        }
                    }
                }
            }
            onProgress(index + 1, selected.size)
        }
        skipped
    }

    suspend fun getPersonById(id: Long): Person? = dao.getPerson(id)

    suspend fun getEventTypesList(): List<EventTypeEntity> = dao.getEventTypes()

    suspend fun getSegmentsOverlapping(
        personId: Long,
        start: Long,
        endExclusive: Long,
    ): List<WearSegment> = dao.getSegmentsOverlapping(personId, start, endExclusive)

    suspend fun getEventsInRange(
        personId: Long,
        from: Long,
        to: Long,
    ): List<EventRecord> = dao.getEventsInRange(personId, from, to)

    suspend fun getDailyPhotosInRange(
        personId: Long,
        startEpochDay: Long,
        endEpochDay: Long,
    ): List<DailyPhoto> = dao.getDailyPhotosInRange(personId, startEpochDay, endEpochDay)

    /** 读取单张照片的 JPEG 字节，供同步打包逐张流式读取。 */
    suspend fun getDailyPhotoBytes(personId: Long, epochDay: Long): ByteArray? =
        dao.getDailyPhoto(personId, epochDay)?.jpeg

    /** 读取单张照片，供同步打包逐张写入。 */
    suspend fun getDailyPhoto(personId: Long, epochDay: Long): DailyPhoto? =
        dao.getDailyPhoto(personId, epochDay)

    /**
     * 照片元信息列表。旧数据没有指纹（迁移时留空），这里按需补算并写回，
     * 之后比对就不再读取照片字节。
     */
    private suspend fun photoMetas(
        personId: Long,
        fromEpochDay: Long,
        toEpochDay: Long,
    ): List<SyncPackage.SyncPhotoRef> =
        photoMetaDetails(personId, fromEpochDay, toEpochDay)
            .map { SyncPackage.SyncPhotoRef(it.epochDay, it.fingerprint) }

    /**
     * 照片元信息；旧数据没有指纹（迁移时留空）时按需补算并写回，
     * 之后比对与打包都不再读取照片字节。
     */
    private suspend fun photoMetaDetails(
        personId: Long,
        fromEpochDay: Long,
        toEpochDay: Long,
    ): List<DailyPhotoMeta> =
        dao.getDailyPhotoMetas(personId, fromEpochDay, toEpochDay).map { meta ->
            if (meta.fingerprint.isNotEmpty()) {
                meta
            } else {
                val photo = dao.getDailyPhoto(personId, meta.epochDay)
                val fingerprint = photo?.let { DailyReport.photoFingerprint(it.jpeg) } ?: ""
                if (photo != null && fingerprint.isNotEmpty()) {
                    dao.upsertDailyPhoto(photo.copy(fingerprint = fingerprint))
                }
                meta.copy(fingerprint = fingerprint)
            }
        }

    /** 指定窗口内照片的总体积，用于发送前提示。 */
    suspend fun photoBytesInRange(
        personId: Long,
        fromEpochDay: Long,
        toEpochDay: Long,
    ): Long = photoMetaDetails(personId, fromEpochDay, toEpochDay).sumOf { it.byteSize }

    /** 发送前预检：窗口内的照片数量与体积。 */
    suspend fun syncExportEstimate(
        personId: Long,
        fromEpochDay: Long,
        toEpochDay: Long,
    ): SyncExportEstimate {
        val metas = photoMetaDetails(personId, fromEpochDay, toEpochDay)
        return SyncExportEstimate(
            photoCount = metas.size,
            photoBytes = metas.sumOf { it.byteSize },
        )
    }

    suspend fun getDailyRandomNumber(
        personId: Long,
        epochDay: Long,
    ): DailyRandomNumber? = dao.getDailyRandomNumber(personId, epochDay)

    suspend fun getPersonsList(): List<Person> = dao.getPersons()

    fun observeDailyRandomNumbers(
        personId: Long,
        startEpochDay: Long,
        endEpochDay: Long,
    ): Flow<List<DailyRandomNumber>> =
        dao.observeDailyRandomNumbersInRange(personId, startEpochDay, endEpochDay)

    /** 读取本地指定人物在窗口内的数据，供导入差异比对使用。 */
    suspend fun collectLocalRange(
        personId: Long?,
        fromEpochDay: Long,
        toEpochDay: Long,
    ): SyncLocalSlice {
        if (personId == null) {
            return SyncLocalSlice(emptyList(), emptyList(), emptyList(), emptyList())
        }
        val zone = ZoneId.systemDefault()
        val fromMillis = LocalDate.ofEpochDay(fromEpochDay)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val toMillis = LocalDate.ofEpochDay(toEpochDay).plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return SyncLocalSlice(
            segments = dao.getSegmentsOverlapping(personId, fromMillis, toMillis + 1),
            events = dao.getEventsInRange(personId, fromMillis, toMillis),
            photos = photoMetas(personId, fromEpochDay, toEpochDay),
            numbers = dao.getDailyRandomNumbersInRange(personId, fromEpochDay, toEpochDay),
        )
    }

    private fun describeDay(epochDay: Long): String = LocalDate.ofEpochDay(epochDay)
        .format(java.time.format.DateTimeFormatter.ofPattern("M月d日", java.util.Locale.CHINA))

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

/** 导出时从数据库收集到的同步数据（未打包）。 */
data class SyncExportData(
    val identity: SyncIdentity,
    val person: Person,
    val eventTypes: List<EventTypeEntity>,
    val segments: List<WearSegment>,
    val events: List<EventRecord>,
    val randomNumbers: List<DailyRandomNumber>,
    val photos: List<SyncPackage.SyncPhotoRef>,
)

/** 本地窗口数据切片，用于导入差异比对。 */
data class SyncLocalSlice(
    val segments: List<WearSegment>,
    val events: List<EventRecord>,
    val photos: List<SyncPackage.SyncPhotoRef>,
    val numbers: List<DailyRandomNumber>,
)

/** 发送前预检结果：窗口内的照片数量与体积。 */
data class SyncExportEstimate(
    val photoCount: Int,
    val photoBytes: Long,
)
