package com.yxsdc.locker.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yxsdc.locker.LockerApp
import com.yxsdc.locker.data.BackupJson
import com.yxsdc.locker.data.DailyPhoto
import com.yxsdc.locker.data.DailyRandomNumber
import com.yxsdc.locker.data.DailyReport
import com.yxsdc.locker.data.DefaultEventTypes
import com.yxsdc.locker.data.EventRecord
import com.yxsdc.locker.data.EventTypeEntity
import com.yxsdc.locker.data.Person
import com.yxsdc.locker.data.SyncAction
import com.yxsdc.locker.data.SyncArchive
import com.yxsdc.locker.data.SyncChange
import com.yxsdc.locker.data.SyncCrypto
import com.yxsdc.locker.data.SyncExportEstimate
import com.yxsdc.locker.data.SyncIdentity
import com.yxsdc.locker.data.SyncPackage
import com.yxsdc.locker.data.WearSegment
import com.yxsdc.locker.data.computeSyncChanges
import com.yxsdc.locker.domain.StatsMode
import com.yxsdc.locker.domain.SyncRange
import com.yxsdc.locker.domain.bucketBoundaries
import com.yxsdc.locker.ui.theme.ThemeMode
import com.yxsdc.locker.widget.LockerWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LockerViewModel(app: Application) : AndroidViewModel(app) {
    private val lockerApp = app as LockerApp
    private val repository = lockerApp.repository
    private val themePreferences = lockerApp.themePreferences
    private val personPreferences = lockerApp.personPreferences

    val persons: StateFlow<List<Person>> = repository.persons
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val eventTypes: StateFlow<List<EventTypeEntity>> = repository.eventTypes
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DefaultEventTypes.builtIns(),
        )

    private val selectedPersonId = MutableStateFlow(personPreferences.selectedPersonId)

    val currentPerson: StateFlow<Person?> = combine(persons, selectedPersonId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeSegment: StateFlow<WearSegment?> = currentPerson
        .flatMapLatest { person ->
            if (person == null) flowOf(null) else repository.observeActiveSegment(person.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val segmentCount: StateFlow<Int> = currentPerson
        .flatMapLatest { person ->
            if (person == null) flowOf(0) else repository.observeSegmentCount(person.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val eventCount: StateFlow<Int> = currentPerson
        .flatMapLatest { person ->
            if (person == null) flowOf(0) else repository.observeEventCount(person.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 统计页只需要当前周期内的数据，避免加载该人物的全部历史。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeStatsSegments(anchor: YearMonth, mode: StatsMode): Flow<List<WearSegment>> =
        currentPerson.flatMapLatest { person ->
            if (person == null) {
                flowOf(emptyList())
            } else {
                val boundaries = bucketBoundaries(mode, anchor, ZoneId.systemDefault())
                repository.observeSegmentsOverlapping(
                    person.id,
                    boundaries.first(),
                    boundaries.last(),
                )
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeStatsEvents(anchor: YearMonth, mode: StatsMode): Flow<List<EventRecord>> =
        currentPerson.flatMapLatest { person ->
            if (person == null) {
                flowOf(emptyList())
            } else {
                val boundaries = bucketBoundaries(mode, anchor, ZoneId.systemDefault())
                repository.observeEventsInRange(
                    person.id,
                    boundaries.first(),
                    boundaries.last(),
                )
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun dailyPhotosForMonth(yearMonth: YearMonth): Flow<List<DailyPhoto>> {
        val start = yearMonth.atDay(1).toEpochDay()
        val end = yearMonth.atEndOfMonth().toEpochDay()
        return currentPerson.flatMapLatest { person ->
            if (person == null) {
                flowOf(emptyList<DailyPhoto>())
            } else {
                repository.observeDailyPhotos(person.id, start, end)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDailyPhoto(epochDay: Long): Flow<DailyPhoto?> =
        currentPerson.flatMapLatest { person ->
            if (person == null) {
                flowOf(emptyList<DailyPhoto>())
            } else {
                repository.observeDailyPhoto(person.id, epochDay)
            }
        }.map { it.firstOrNull() }

    fun setDailyPhoto(epochDay: Long, jpeg: ByteArray) {
        val person = currentPerson.value ?: return
        launchAction {
            repository.setDailyPhoto(person.id, epochDay, jpeg)
            // 今日照片决定小组件的打卡状态
            refreshWidget()
        }
        // 开关开启时，上传完成后自动识别照片中的号码并与当日随机数比对
        if (PhotoPreferences.checkNumberAfterUpload(getApplication())) {
            viewModelScope.launch {
                try {
                    val number = repository.getDailyRandomNumber(person.id, epochDay)?.number
                        ?: return@launch
                    val check = verifyPhotoNumber(jpeg, number)
                    _messages.tryEmit(
                        when (check) {
                            DailyReport.PhotoCheck.PASS ->
                                "✓ 照片号码检测通过（$number）"
                            DailyReport.PhotoCheck.MISMATCH ->
                                "✗ 照片号码与当日随机数 $number 不一致"
                            DailyReport.PhotoCheck.UNKNOWN ->
                                "未能识别照片中的号码"
                            DailyReport.PhotoCheck.NONE -> return@launch
                        }
                    )
                } catch (throwable: Throwable) {
                    // 检测失败不打扰上传流程
                }
            }
        }
    }

    fun deleteDailyPhoto(epochDay: Long) {
        val person = currentPerson.value ?: return
        launchAction {
            repository.deleteDailyPhoto(person.id, epochDay)
            refreshWidget()
        }
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    val themeMode: StateFlow<ThemeMode> = themePreferences.mode

    private val _statsMode = MutableStateFlow(StatsMode.MONTH)
    val statsMode: StateFlow<StatsMode> = _statsMode.asStateFlow()

    private val _statsAnchor = MutableStateFlow(YearMonth.now())
    val statsAnchor: StateFlow<YearMonth> = _statsAnchor.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDailyRandomNumber(epochDay: Long): Flow<String?> =
        currentPerson.flatMapLatest { person ->
            if (person == null) {
                flowOf(null)
            } else {
                repository.observeDailyRandomNumber(person.id, epochDay)
                    .map { it.firstOrNull()?.number }
            }
        }

    fun ensureDailyRandomNumber(epochDay: Long) {
        val person = currentPerson.value ?: return
        viewModelScope.launch {
            try {
                repository.ensureDailyRandomNumber(person.id, epochDay)
                refreshWidget()
            } catch (throwable: Throwable) {
                _messages.tryEmit(throwable.message ?: "生成随机数失败")
            }
        }
    }

    init {
        viewModelScope.launch {
            var lastEnsured: Pair<Long, Long>? = null
            currentPerson.collect { person ->
                if (person != null && selectedPersonId.value != person.id) {
                    selectedPersonId.value = person.id
                    personPreferences.selectedPersonId = person.id
                }
                if (person != null) {
                    val today = LocalDate.now().toEpochDay()
                    val key = person.id to today
                    if (lastEnsured != key) {
                        lastEnsured = key
                        ensureDailyRandomNumber(today)
                    }
                }
            }
        }
    }

    fun setStatsMode(mode: StatsMode) {
        _statsMode.value = mode
    }

    fun setThemeMode(mode: ThemeMode) {
        themePreferences.setMode(mode)
    }

    fun movePeriod(delta: Long) {
        _statsAnchor.value = when (_statsMode.value) {
            StatsMode.MONTH -> _statsAnchor.value.plusMonths(delta)
            StatsMode.YEAR -> _statsAnchor.value.plusYears(delta)
        }
    }

    fun goToCurrentPeriod() {
        _statsAnchor.value = YearMonth.now()
    }

    fun selectPerson(person: Person) {
        selectedPersonId.value = person.id
        personPreferences.selectedPersonId = person.id
        refreshWidget()
    }

    /** 按 ID 切换当前人物；人物不存在时忽略（例如刚从小组件进来但人物已被删）。 */
    fun selectPersonById(personId: Long) {
        selectedPersonId.value = personId
        personPreferences.selectedPersonId = personId
        refreshWidget()
    }

    fun addPerson(name: String) {
        viewModelScope.launch {
            try {
                val id = repository.addPerson(name)
                selectedPersonId.value = id
                personPreferences.selectedPersonId = id
                refreshWidget()
            } catch (throwable: Throwable) {
                _messages.tryEmit(throwable.message ?: "新建人物失败")
            }
        }
    }

    fun renamePerson(person: Person, name: String) =
        launchAction {
            repository.renamePerson(person.id, name)
            refreshWidget()
        }

    fun deletePerson(person: Person) {
        viewModelScope.launch {
            try {
                repository.deletePerson(person)
                if (selectedPersonId.value == person.id) {
                    val next = persons.value.firstOrNull { it.id != person.id }
                    selectedPersonId.value = next?.id
                    personPreferences.selectedPersonId = next?.id
                }
                // 先清掉指向该人物的绑定，再刷新（失效实例会显示「人物已删除」）
                LockerWidgetProvider.onPersonDeleted(getApplication(), person.id)
            } catch (throwable: Throwable) {
                _messages.tryEmit(throwable.message ?: "删除人物失败")
            }
        }
    }

    fun startWearing() {
        val person = currentPerson.value ?: return
        launchAction {
            repository.startWearing(person.id, System.currentTimeMillis())
            refreshWidget()
        }
    }

    fun stopWearing() {
        val person = currentPerson.value ?: return
        launchAction {
            repository.stopWearing(person.id, System.currentTimeMillis())
            refreshWidget()
        }
    }

    private fun refreshWidget() {
        LockerWidgetProvider.updateAll(getApplication())
    }

    fun addManualSegment(startAt: Long, endAt: Long?) {
        val person = currentPerson.value ?: return
        launchAction {
            repository.addManualSegment(person.id, startAt, endAt)
            refreshWidget()
        }
    }

    fun updateSegment(id: Long, personId: Long, startAt: Long, endAt: Long?) =
        launchAction {
            repository.updateSegment(id, personId, startAt, endAt)
            refreshWidget()
        }

    fun deleteSegment(segment: WearSegment) =
        launchAction {
            repository.deleteSegment(segment)
            refreshWidget()
        }

    fun addEventType(name: String, colorArgb: Long) =
        launchAction { repository.addEventType(name, colorArgb) }

    fun updateEventType(type: EventTypeEntity, name: String, colorArgb: Long) =
        launchAction { repository.updateEventType(type.typeKey, name, colorArgb) }

    fun deleteEventType(type: EventTypeEntity) =
        launchAction { repository.deleteEventType(type) }

    suspend fun countEventsOfType(typeKey: String): Int =
        repository.countEventsOfType(typeKey)

    fun addEvent(
        typeKey: String,
        occurredAt: Long,
        note: String?,
        onCreated: (Long) -> Unit = {},
    ) {
        val person = currentPerson.value ?: return
        viewModelScope.launch {
            try {
                onCreated(repository.addEvent(person.id, typeKey, occurredAt, note))
            } catch (throwable: Throwable) {
                _messages.tryEmit(throwable.message ?: "操作失败")
            }
        }
    }

    fun updateEvent(
        id: Long,
        personId: Long,
        typeKey: String,
        occurredAt: Long,
        note: String?,
    ) = launchAction { repository.updateEvent(id, personId, typeKey, occurredAt, note) }

    fun deleteEvent(event: EventRecord) =
        launchAction { repository.deleteEvent(event) }

    fun deleteEventById(id: Long) =
        launchAction { repository.deleteEventById(id) }

    suspend fun buildBackupText(): String =
        BackupJson.encode(repository.exportAll())

    suspend fun importBackup(data: BackupJson.BackupData) {
        repository.importAll(data)
        selectedPersonId.value = null
        personPreferences.selectedPersonId = null
        // sendBroadcast 可在任意线程调用
        refreshWidget()
    }

    // ---- 掌管者同步 ----

    private val _pendingSharedZip = MutableStateFlow<Uri?>(null)
    val pendingSharedZip: StateFlow<Uri?> = _pendingSharedZip.asStateFlow()

    fun onSharedZip(uri: Uri) {
        _pendingSharedZip.value = uri
    }

    fun consumeSharedZip() {
        _pendingSharedZip.value = null
    }

    private val _syncNavigation = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val syncNavigation = _syncNavigation.asSharedFlow()

    /** 桌面快捷方式「发送今日数据」触发的一次性请求。 */
    private val _sendTodayRequested = MutableStateFlow(false)
    val sendTodayRequested: StateFlow<Boolean> = _sendTodayRequested.asStateFlow()

    fun requestSendToday() {
        _sendTodayRequested.value = true
    }

    fun consumeSendTodayRequest() {
        _sendTodayRequested.value = false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMonthlyRandomNumbers(yearMonth: YearMonth): Flow<List<DailyRandomNumber>> {
        val start = yearMonth.atDay(1).toEpochDay()
        val end = yearMonth.atEndOfMonth().toEpochDay()
        return currentPerson.flatMapLatest { person ->
            if (person == null) {
                flowOf(emptyList())
            } else {
                repository.observeDailyRandomNumbers(person.id, start, end)
            }
        }
    }

    private val syncWorkDir: File
        get() = File(getApplication<Application>().cacheDir, "sync").apply { mkdirs() }

    /** 打包/校验/应用的进度（0-100），null 表示没有进行中的任务。 */
    private val _syncProgress = MutableStateFlow<Int?>(null)
    val syncProgress: StateFlow<Int?> = _syncProgress.asStateFlow()

    suspend fun prepareSyncIdentity(): SyncIdentity = withContext(Dispatchers.IO) {
        val person = currentPerson.value ?: error("请先选择人物")
        repository.ensureSyncIdentity(person)
    }

    suspend fun regenerateSyncCode(): SyncIdentity = withContext(Dispatchers.IO) {
        val person = currentPerson.value ?: error("请先选择人物")
        repository.regenerateSyncCode(repository.ensureSyncIdentity(person))
    }

    suspend fun exportSyncPackage(range: SyncRange): SyncExportResult {
        val today = LocalDate.now().toEpochDay()
        val (fromDay, toDay) = range.windowFor(today)
        return exportSyncPackageWindow(fromDay, toDay)
    }

    /**
     * 增量发送：从上次发送当天到今天。从未发送过时等同于「当天」。
     * 窗口之外的旧数据不重发，接收端只比对窗口内的记录。
     */
    suspend fun exportSyncPackageSinceLast(): SyncExportResult {
        val person = currentPerson.value ?: error("请先选择人物")
        val last = repository.getSyncIdentityForPerson(person.id)?.lastExportedAt
        val today = LocalDate.now().toEpochDay()
        val (fromDay, toDay) = SyncRange.TODAY.incrementalWindow(today, last)
        return exportSyncPackageWindow(fromDay, toDay)
    }

    /**
     * 按显式窗口导出。窗口起点早于今天时即为增量发送：
     * 接收端只会比对窗口内的数据，窗口之外的记录不受影响。
     */
    suspend fun exportSyncPackageWindow(
        fromDay: Long,
        toDay: Long,
    ): SyncExportResult = withContext(Dispatchers.IO) {
        val person = currentPerson.value ?: error("请先选择人物")
        val estimate = repository.syncExportEstimate(person.id, fromDay, toDay)
        require(estimate.photoCount <= SyncArchive.MAX_PHOTOS) {
            "照片数量 ${estimate.photoCount} 张，超过单次上限 ${SyncArchive.MAX_PHOTOS} 张，请缩短数据范围"
        }
        require(estimate.photoBytes <= MAX_EXPORT_PHOTO_BYTES) {
            "照片约 ${estimate.photoBytes / (1024 * 1024)} MB，超过单次上限 " +
                "${MAX_EXPORT_PHOTO_BYTES / (1024 * 1024)} MB，请缩短数据范围"
        }
        val data = repository.collectSyncExport(person, fromDay, toDay)
        val identity = data.identity
        val manifest = SyncPackage.Manifest(
            identityId = identity.identityId,
            identityName = identity.displayName,
            codeSalt = identity.codeSalt,
            codeHash = SyncCrypto.hashCode(identity.codeSalt, identity.code),
            exportedAt = System.currentTimeMillis(),
            fromEpochDay = fromDay,
            toEpochDay = toDay,
        )
        // 只携带元信息；照片字节在写包时按天从数据库逐张读取
        val photos = data.photos.map { SyncPackage.SyncPhotoRef(it.epochDay, it.fingerprint) }
        val payload = SyncPackage.Payload(
            personName = person.name,
            eventTypes = data.eventTypes,
            segments = data.segments.map { it.copy(id = 0, personId = 0) },
            events = data.events.map { it.copy(id = 0, personId = 0) },
            randomNumbers = data.randomNumbers.map { it.copy(personId = 0) },
            photos = photos,
        )
        val dir = syncWorkDir
        val payloadJson = SyncPackage.encodePayloadJson(payload)
        // 同一份数据重复发送时直接复用上次的包，省掉一次完整打包
        val signature = exportSignature(
            identityId = identity.identityId,
            codeHash = manifest.codeHash,
            fromDay = fromDay,
            toDay = toDay,
            payloadJson = payloadJson,
        )
        val cached = lastExport
        if (cached != null && cached.first == signature && cached.second.exists()) {
            repository.markIdentityExported(identity.identityId)
            return@withContext SyncExportResult(
                file = cached.second,
                identity = identity,
                photoCount = photos.size,
                dayCount = toDay - fromDay + 1,
                fromEpochDay = fromDay,
                toEpochDay = toDay,
            )
        }
        // 只保留最近几个包：分享出去的 URI 可能被对方稍后才读取，不能立刻删掉
        dir.listFiles()
            ?.filter { it.name.startsWith("locker-") && it.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_EXPORT_FILES - 1)
            ?.forEach { it.delete() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val target = File(dir, "locker-$stamp.zip")
        val key = SyncCrypto.deriveKey(identity.codeSalt, identity.code)
        _syncProgress.value = 0
        try {
            SyncArchive.writePackage(
                target = target,
                manifest = manifest,
                payloadJson = payloadJson,
                photos = photos,
                key = key,
                readPhoto = { epochDay -> repository.getDailyPhotoBytes(person.id, epochDay) },
                expectedPhotoBytes = estimate.photoBytes,
                onProgress = { done, total ->
                    _syncProgress.value = if (total <= 0) 100 else (done.toLong() * 100 / total).toInt()
                },
            )
        } finally {
            _syncProgress.value = null
        }
        lastExport = signature to target
        repository.markIdentityExported(identity.identityId)
        SyncExportResult(
            file = target,
            identity = identity,
            photoCount = photos.size,
            dayCount = toDay - fromDay + 1,
            fromEpochDay = fromDay,
            toEpochDay = toDay,
        )
    }

    /** 发送前预检：当前人物在窗口内的照片数量与体积。 */
    suspend fun estimateExport(fromDay: Long, toDay: Long): SyncExportEstimate =
        withContext(Dispatchers.IO) {
            val person = currentPerson.value ?: return@withContext SyncExportEstimate(0, 0L)
            repository.syncExportEstimate(person.id, fromDay, toDay)
        }

    /** 上次成功发送的时间，用于增量发送。 */
    suspend fun lastExportedAt(): Long? = withContext(Dispatchers.IO) {
        val person = currentPerson.value ?: return@withContext null
        repository.getSyncIdentityForPerson(person.id)?.lastExportedAt
    }

    /** 上次导出的包：签名 -> 文件，用于内容未变时跳过重复打包。 */
    private var lastExport: Pair<String, File>? = null

    private companion object {
        /** 单次打包的照片总体积上限，留出加密与 zip 包装的余量。 */
        const val MAX_EXPORT_PHOTO_BYTES = 260L * 1024 * 1024
        const val KEEP_EXPORT_FILES = 3
    }

    private fun exportSignature(
        identityId: String,
        codeHash: String,
        fromDay: Long,
        toDay: Long,
        payloadJson: ByteArray,
    ): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(identityId.toByteArray())
        digest.update(codeHash.toByteArray())
        digest.update("$fromDay-$toDay".toByteArray())
        digest.update(payloadJson)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private var pendingManifest: SyncPackage.Manifest? = null
    private var pendingPayload: SyncPackage.Payload? = null
    private var pendingInnerZip: File? = null
    private var pendingChanges: List<SyncChange>? = null
    private var pendingTargetPersonId: Long? = null
    private var pendingCode: String? = null
    private var pendingPackageHash: String? = null

    suspend fun startImport(input: InputStream): SyncImportPreview = withContext(Dispatchers.IO) {
        cancelImportInternal()
        val dir = syncWorkDir
        val zipFile = File(dir, "incoming.tmp.zip")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        // 文件来源大小已知时用字节进度，未知时显示不确定进度
        val expectedBytes = runCatching { input.available().toLong() }.getOrDefault(0L)
        _syncProgress.value = 0
        try {
            input.use { source ->
                zipFile.outputStream().buffered().use { out ->
                    val chunk = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(chunk)
                        if (count < 0) break
                        total += count
                        require(total <= SyncArchive.MAX_PACKAGE_BYTES) { "同步包超出体积上限" }
                        out.write(chunk, 0, count)
                        digest.update(chunk, 0, count)
                        if (expectedBytes > 0) {
                            _syncProgress.value =
                                (total * 100 / expectedBytes).toInt().coerceIn(0, 99)
                        }
                    }
                }
            }
        } finally {
            _syncProgress.value = null
        }
        val packageHash = digest.digest().joinToString("") { "%02x".format(it) }
        pendingPackageHash = packageHash
        val manifest = SyncArchive.readManifest(zipFile)
        pendingManifest = manifest
        val binding = repository.getSyncIdentity(manifest.identityId)
        val boundPerson = binding?.let { repository.getPersonById(it.personId) }
        val codeChanged = binding != null &&
            SyncCrypto.hashCode(binding.codeSalt, binding.code) != manifest.codeHash
        val sameName = if (binding == null) {
            val boundPersonIds = repository.getAllSyncIdentities().map { it.personId }.toSet()
            repository.getPersonsList().filter {
                it.name == manifest.identityName && it.id !in boundPersonIds
            }
        } else {
            emptyList()
        }
        SyncImportPreview(
            manifest = manifest,
            boundPerson = boundPerson,
            sameNamePersons = sameName,
            needsVerification = binding == null || codeChanged,
            codeChanged = codeChanged,
            alreadyImported = SyncImportPreferences.lastHash(
                getApplication(),
                manifest.identityId,
            ) == packageHash,
        )
    }

    suspend fun verifyAndDiff(code: String?, targetPersonId: Long?): SyncImportDiffResult =
        withContext(Dispatchers.IO) {
            val manifest = pendingManifest ?: error("没有待接收的同步数据")
            val binding = repository.getSyncIdentity(manifest.identityId)
            val effectiveCode = when {
                !code.isNullOrBlank() -> {
                    require(SyncCrypto.matches(manifest.codeSalt, manifest.codeHash, code)) {
                        "验证码不正确"
                    }
                    SyncCrypto.normalizeCode(code)
                }

                binding != null -> {
                    require(
                        SyncCrypto.hashCode(binding.codeSalt, binding.code) == manifest.codeHash
                    ) { "验证码已更换，需要重新验证" }
                    binding.code
                }

                else -> error("请输入验证码")
            }
            val key = SyncCrypto.deriveKey(manifest.codeSalt, effectiveCode)
            val innerZip = File(syncWorkDir, "inner.tmp.zip")
            _syncProgress.value = 0
            val parsed = try {
                SyncArchive.parseEncryptedPayload(
                    zipFile = File(syncWorkDir, "incoming.tmp.zip"),
                    destInnerZip = innerZip,
                    key = key,
                    onProgress = { done, total ->
                        _syncProgress.value = if (total <= 0) 100 else done * 100 / total
                    },
                    onBytes = { done, total ->
                        if (total > 0) {
                            _syncProgress.value = (done * 100 / total).toInt().coerceIn(0, 99)
                        }
                    },
                )
            } finally {
                _syncProgress.value = null
            }
            pendingInnerZip = innerZip
            val payload = parsed.data
            require(SyncArchive.verifyPhotoEntries(innerZip, payload)) {
                "同步包内的照片与清单不一致，请让对方重新打包"
            }

            val personId = targetPersonId ?: binding?.personId
            val local = repository.collectLocalRange(
                personId, manifest.fromEpochDay, manifest.toEpochDay
            )
            val localTypes = repository.getEventTypesList()
            val typeNames = (
                localTypes.map { it.typeKey to it.name } +
                    payload.eventTypes.map { it.typeKey to it.name }
                ).toMap()
            val changes = computeSyncChanges(
                localSegments = local.segments,
                localEvents = local.events,
                localPhotos = local.photos,
                localNumbers = local.numbers,
                payload = payload,
                typeNames = typeNames,
            )
            pendingPayload = payload
            pendingChanges = changes
            pendingTargetPersonId = personId
            pendingCode = effectiveCode
            SyncImportDiffResult(
                personName = payload.personName,
                changes = changes,
                photoCount = parsed.photos.size,
            )
        }

    suspend fun applyPendingImport(selectedKeys: Set<String>): SyncImportApplyResult =
        withContext(Dispatchers.IO) {
            val manifest = pendingManifest ?: error("没有待应用的同步")
            val payload = pendingPayload ?: error("没有待应用的同步")
            val changes = pendingChanges ?: error("没有待应用的同步")
            val code = pendingCode ?: error("没有待应用的同步")
            val personId = pendingTargetPersonId
                ?: repository.createPersonForImport(payload.personName)
            repository.bindSyncIdentity(manifest, code, personId)
            pendingPackageHash?.let {
                SyncImportPreferences.setLastHash(getApplication(), manifest.identityId, it)
            }
            val selected = changes.filter { it.key in selectedKeys }
            val reader = pendingInnerZip?.let {
                runCatching { SyncArchive.PhotoReader(it) }.getOrNull()
            }
            _syncProgress.value = 0
            val skipped = try {
                repository.applySyncImport(
                    personId = personId,
                    payloadTypes = payload.eventTypes,
                    selected = selected,
                    loadPhoto = { epochDay -> reader?.read(epochDay) },
                    onProgress = { done, total ->
                        _syncProgress.value = if (total <= 0) 100 else done * 100 / total
                    },
                )
            } finally {
                reader?.close()
                _syncProgress.value = null
            }
            repository.getPersonById(personId)?.let { selectPerson(it) }
            val result = SyncImportApplyResult(
                added = selected.count { it.action == SyncAction.ADD },
                removed = selected.count { it.action == SyncAction.REMOVE },
                modified = selected.count { it.action == SyncAction.MODIFY },
                skippedNumberDays = skipped,
            )
            cancelImportInternal()
            _syncNavigation.tryEmit(Unit)
            refreshWidget()
            result
        }

    fun cancelPendingImport() {
        cancelImportInternal()
    }

    // ---- 日报二维码 ----

    suspend fun buildDailyReport(): DailyReportUi = withContext(Dispatchers.IO) {
        val person = currentPerson.value ?: error("请先选择人物")
        val identity = repository.ensureSyncIdentity(person)
        val today = LocalDate.now()
        val epochDay = today.toEpochDay()
        val number = repository.ensureDailyRandomNumber(person.id, epochDay)
        val zone = java.time.ZoneId.systemDefault()
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val wearMillis = repository.getSegmentsOverlapping(person.id, dayStart, dayEnd)
            .sumOf { segment ->
                val start = maxOf(segment.startAt, dayStart)
                val end = minOf(segment.endAt ?: now, dayEnd)
                if (end > start) end - start else 0L
            }
        val eventCount = repository.getEventsInRange(person.id, dayStart, dayEnd - 1).size

        // 生成日报时顺带对当天照片做号码识别，把结果与照片指纹写进二维码
        val photo = repository.getDailyPhotosInRange(person.id, epochDay, epochDay)
            .firstOrNull()
        var photoCheck = DailyReport.PhotoCheck.NONE
        var fingerprint = ""
        if (photo != null) {
            fingerprint = DailyReport.photoFingerprint(photo.jpeg)
            photoCheck = verifyPhotoNumber(photo.jpeg, number)
        }

        val report = DailyReport.Report(
            identityId = identity.identityId,
            epochDay = epochDay,
            number = number,
            wearMinutes = wearMillis / 60_000,
            eventCount = eventCount,
            photoCheck = photoCheck,
            photoFingerprint = fingerprint,
            issuedAt = now,
        )
        DailyReportUi(
            report = report,
            payload = DailyReport.encode(report, identity.codeSalt, identity.code),
            identityName = identity.displayName,
        )
    }

    private suspend fun verifyPhotoNumber(
        jpeg: ByteArray,
        number: String,
    ): DailyReport.PhotoCheck = try {
        val candidates = com.yxsdc.locker.ui.components.recognizeNumberCandidates(jpeg)
        when {
            number in candidates -> DailyReport.PhotoCheck.PASS
            candidates.isEmpty() -> DailyReport.PhotoCheck.UNKNOWN
            else -> DailyReport.PhotoCheck.MISMATCH
        }
    } catch (throwable: Throwable) {
        DailyReport.PhotoCheck.UNKNOWN
    }

    suspend fun verifyDailyReport(payload: String, codeInput: String?): ReportVerification =
        withContext(Dispatchers.IO) {
            val parsed = DailyReport.parse(payload)
            val binding = repository.getSyncIdentity(parsed.report.identityId)
            val code = codeInput?.takeIf { it.isNotBlank() }?.let {
                SyncCrypto.normalizeCode(it)
            } ?: binding?.code
            ReportVerification(
                identityName = binding?.displayName,
                report = parsed.report,
                signatureValid = code != null && DailyReport.verify(parsed, code),
                needsCode = code == null,
            )
        }

    private fun cancelImportInternal() {
        pendingManifest = null
        pendingPayload = null
        pendingInnerZip = null
        pendingChanges = null
        pendingTargetPersonId = null
        pendingCode = null
        pendingPackageHash = null
        runCatching {
            syncWorkDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".tmp.zip") || file.name.endsWith(".tmp.bin")) file.delete()
            }
        }
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (throwable: Throwable) {
                _messages.tryEmit(throwable.message ?: "操作失败")
            }
        }
    }
}

data class SyncExportResult(
    val file: File,
    val identity: SyncIdentity,
    val photoCount: Int,
    val dayCount: Long,
    val fromEpochDay: Long,
    val toEpochDay: Long,
)

data class SyncImportPreview(
    val manifest: SyncPackage.Manifest,
    val boundPerson: Person?,
    val sameNamePersons: List<Person>,
    val needsVerification: Boolean,
    val codeChanged: Boolean,
    /** 这份包是否与上次接收的完全相同。 */
    val alreadyImported: Boolean = false,
)

data class SyncImportDiffResult(
    val personName: String,
    val changes: List<SyncChange>,
    val photoCount: Int,
)

data class SyncImportApplyResult(
    val added: Int,
    val removed: Int,
    val modified: Int,
    val skippedNumberDays: List<String>,
)

data class DailyReportUi(
    val report: DailyReport.Report,
    val payload: String,
    val identityName: String,
)

data class ReportVerification(
    val identityName: String?,
    val report: DailyReport.Report,
    val signatureValid: Boolean,
    val needsCode: Boolean,
)
