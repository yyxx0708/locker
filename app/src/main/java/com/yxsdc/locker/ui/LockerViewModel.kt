package com.yxsdc.locker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yxsdc.locker.LockerApp
import com.yxsdc.locker.data.BackupJson
import com.yxsdc.locker.data.DailyPhoto
import com.yxsdc.locker.data.DefaultEventTypes
import com.yxsdc.locker.data.EventRecord
import com.yxsdc.locker.data.EventTypeEntity
import com.yxsdc.locker.data.Person
import com.yxsdc.locker.data.WearSegment
import com.yxsdc.locker.domain.StatsMode
import com.yxsdc.locker.ui.theme.ThemeMode
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
import java.time.LocalDate
import java.time.YearMonth

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
    val segments: StateFlow<List<WearSegment>> = currentPerson
        .flatMapLatest { person ->
            if (person == null) flowOf(emptyList()) else repository.segments(person.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val events: StateFlow<List<EventRecord>> = currentPerson
        .flatMapLatest { person ->
            if (person == null) flowOf(emptyList()) else repository.events(person.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        launchAction { repository.setDailyPhoto(person.id, epochDay, jpeg) }
    }

    fun deleteDailyPhoto(epochDay: Long) {
        val person = currentPerson.value ?: return
        launchAction { repository.deleteDailyPhoto(person.id, epochDay) }
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
    }

    fun addPerson(name: String) {
        viewModelScope.launch {
            try {
                val id = repository.addPerson(name)
                selectedPersonId.value = id
                personPreferences.selectedPersonId = id
            } catch (throwable: Throwable) {
                _messages.tryEmit(throwable.message ?: "新建人物失败")
            }
        }
    }

    fun renamePerson(person: Person, name: String) =
        launchAction { repository.renamePerson(person.id, name) }

    fun deletePerson(person: Person) {
        viewModelScope.launch {
            try {
                repository.deletePerson(person)
                if (selectedPersonId.value == person.id) {
                    val next = persons.value.firstOrNull { it.id != person.id }
                    selectedPersonId.value = next?.id
                    personPreferences.selectedPersonId = next?.id
                }
            } catch (throwable: Throwable) {
                _messages.tryEmit(throwable.message ?: "删除人物失败")
            }
        }
    }

    fun startWearing() {
        val person = currentPerson.value ?: return
        launchAction { repository.startWearing(person.id, System.currentTimeMillis()) }
    }

    fun stopWearing() {
        val person = currentPerson.value ?: return
        launchAction { repository.stopWearing(person.id, System.currentTimeMillis()) }
    }

    fun addManualSegment(startAt: Long, endAt: Long?) {
        val person = currentPerson.value ?: return
        launchAction { repository.addManualSegment(person.id, startAt, endAt) }
    }

    fun updateSegment(id: Long, personId: Long, startAt: Long, endAt: Long?) =
        launchAction { repository.updateSegment(id, personId, startAt, endAt) }

    fun deleteSegment(segment: WearSegment) =
        launchAction { repository.deleteSegment(segment) }

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
