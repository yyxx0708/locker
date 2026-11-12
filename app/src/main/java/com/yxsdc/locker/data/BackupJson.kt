package com.yxsdc.locker.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object BackupJson {
    private const val SCHEMA_VERSION = 4
    private const val LEGACY_SCHEMA_VERSION_V1 = 1
    private const val LEGACY_SCHEMA_VERSION_V2 = 2
    private const val LEGACY_SCHEMA_VERSION_V3 = 3

    data class BackupData(
        val persons: List<Person>,
        val eventTypes: List<EventTypeEntity>,
        val segments: List<WearSegment>,
        val events: List<EventRecord>,
        val dailyRandomNumbers: List<DailyRandomNumber>,
    )

    fun encode(data: BackupData): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("exportedAt", System.currentTimeMillis())

        val personArray = JSONArray()
        data.persons.sortedBy { it.id }.forEach { person ->
            personArray.put(
                JSONObject().apply {
                    put("id", person.id)
                    put("name", person.name)
                    put("createdAt", person.createdAt)
                }
            )
        }

        val eventTypeArray = JSONArray()
        data.eventTypes
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .forEach { type ->
                eventTypeArray.put(
                    JSONObject().apply {
                        put("typeKey", type.typeKey)
                        put("name", type.name)
                        put("colorArgb", type.colorArgb)
                        put("sortOrder", type.sortOrder)
                        put("builtIn", type.builtIn)
                    }
                )
            }

        val segmentArray = JSONArray()
        data.segments.sortedBy { it.startAt }.forEach { segment ->
            segmentArray.put(
                JSONObject().apply {
                    put("personId", segment.personId)
                    put("startAt", segment.startAt)
                    if (segment.endAt == null) {
                        put("endAt", JSONObject.NULL)
                    } else {
                        put("endAt", segment.endAt)
                    }
                }
            )
        }

        val eventArray = JSONArray()
        data.events.sortedBy { it.occurredAt }.forEach { event ->
            eventArray.put(
                JSONObject().apply {
                    put("personId", event.personId)
                    put("type", event.type)
                    put("occurredAt", event.occurredAt)
                    if (event.note.isNullOrBlank()) {
                        put("note", JSONObject.NULL)
                    } else {
                        put("note", event.note)
                    }
                }
            )
        }

        val randomNumberArray = JSONArray()
        data.dailyRandomNumbers
            .sortedWith(compareBy({ it.epochDay }, { it.personId }))
            .forEach { entry ->
                randomNumberArray.put(
                    JSONObject().apply {
                        put("personId", entry.personId)
                        put("epochDay", entry.epochDay)
                        put("number", entry.number)
                        put("createdAt", entry.createdAt)
                    }
                )
            }

        root.put("persons", personArray)
        root.put("eventTypes", eventTypeArray)
        root.put("segments", segmentArray)
        root.put("events", eventArray)
        root.put("dailyRandomNumbers", randomNumberArray)
        return root.toString(2)
    }

    fun decode(text: String): BackupData {
        try {
            val root = JSONObject(text.trim().removePrefix("﻿"))
            return when (val version = root.optInt("schemaVersion", -1)) {
                SCHEMA_VERSION -> decodeV4(root)
                LEGACY_SCHEMA_VERSION_V3 -> decodeV3(root)
                LEGACY_SCHEMA_VERSION_V2 -> decodeV2(root)
                LEGACY_SCHEMA_VERSION_V1 -> decodeV1(root)
                else -> throw IllegalArgumentException("不支持的备份版本：$version")
            }
        } catch (exception: JSONException) {
            throw IllegalArgumentException("备份格式不完整：${exception.message}", exception)
        }
    }

    private fun decodeV4(root: JSONObject): BackupData {
        val exportedAt = root.optLong("exportedAt", System.currentTimeMillis())
        val persons = parsePersons(root, exportedAt)
        val eventTypes = parseEventTypes(root)
        val segments = parseSegments(root)
        val events = parseEvents(root)
        val dailyRandomNumbers = parseDailyRandomNumbers(root)
        validate(persons, eventTypes, segments, events, dailyRandomNumbers)
        return BackupData(
            persons = persons,
            eventTypes = eventTypes,
            segments = segments,
            events = events,
            dailyRandomNumbers = dailyRandomNumbers,
        )
    }

    private fun decodeV3(root: JSONObject): BackupData {
        val exportedAt = root.optLong("exportedAt", System.currentTimeMillis())
        val persons = parsePersons(root, exportedAt)
        val eventTypes = parseEventTypes(root)
        val segments = parseSegments(root)
        val events = parseEvents(root)
        validate(persons, eventTypes, segments, events, emptyList())
        return BackupData(
            persons = persons,
            eventTypes = eventTypes,
            segments = segments,
            events = events,
            dailyRandomNumbers = emptyList(),
        )
    }

    private fun decodeV2(root: JSONObject): BackupData {
        val exportedAt = root.optLong("exportedAt", System.currentTimeMillis())
        val persons = parsePersons(root, exportedAt)
        val eventTypes = DefaultEventTypes.builtIns()
        val segments = parseSegments(root)
        val events = parseEvents(root)
        validate(persons, eventTypes, segments, events, emptyList())
        return BackupData(
            persons = persons,
            eventTypes = eventTypes,
            segments = segments,
            events = events,
            dailyRandomNumbers = emptyList(),
        )
    }

    private fun decodeV1(root: JSONObject): BackupData {
        val exportedAt = root.optLong("exportedAt", System.currentTimeMillis())
        val segments = parseSegments(root, defaultPersonId = 1L)
        val events = parseEvents(root, defaultPersonId = 1L)

        val createdAt = segments.minOfOrNull { it.startAt }
            ?: events.minOfOrNull { it.occurredAt }
            ?: exportedAt
        val persons = listOf(
            Person(id = 1L, name = DEFAULT_PERSON_NAME, createdAt = createdAt)
        )
        val eventTypes = DefaultEventTypes.builtIns()
        validate(persons, eventTypes, segments, events, emptyList())
        return BackupData(
            persons = persons,
            eventTypes = eventTypes,
            segments = segments,
            events = events,
            dailyRandomNumbers = emptyList(),
        )
    }

    private fun parsePersons(root: JSONObject, exportedAt: Long): List<Person> {
        val personArray = root.getJSONArray("persons")
        require(personArray.length() > 0) { "备份中没有人物" }

        val persons = mutableListOf<Person>()
        for (index in 0 until personArray.length()) {
            val item = personArray.getJSONObject(index)
            val id = item.getLong("id")
            val name = item.getString("name").trim()
            require(id > 0L) { "备份中的人物 id 无效" }
            require(name.isNotEmpty()) { "备份中存在空名称的人物" }
            require(name.length <= 20) { "备份中的人物名称过长" }
            persons.add(
                Person(
                    id = id,
                    name = name,
                    createdAt = item.optLong("createdAt", exportedAt),
                )
            )
        }

        val ids = persons.map { it.id }
        require(ids.toSet().size == ids.size) { "备份中存在重复的人物 id" }
        require(persons.map { it.name }.toSet().size == persons.size) {
            "备份中存在同名人物"
        }
        return persons
    }

    private fun parseEventTypes(root: JSONObject): List<EventTypeEntity> {
        val typeArray = root.getJSONArray("eventTypes")
        require(typeArray.length() > 0) { "备份中没有事件类型" }

        val types = mutableListOf<EventTypeEntity>()
        for (index in 0 until typeArray.length()) {
            val item = typeArray.getJSONObject(index)
            val typeKey = item.getString("typeKey").trim()
            val name = item.getString("name").trim()
            val builtIn = item.getBoolean("builtIn")
            require(typeKey.isNotEmpty()) { "备份中存在空标识的事件类型" }
            require(name.isNotEmpty()) { "备份中存在空名称的事件类型" }
            require(name.length <= 20) { "备份中的事件名称过长" }
            require(builtIn == (typeKey in DefaultEventTypes.builtInKeys)) {
                "备份中的事件类型标记无效：$typeKey"
            }
            types.add(
                EventTypeEntity(
                    typeKey = typeKey,
                    name = name,
                    colorArgb = item.getLong("colorArgb") or 0xFF000000L,
                    sortOrder = item.optInt("sortOrder", index),
                    builtIn = builtIn,
                )
            )
        }

        val keys = types.map { it.typeKey }
        require(keys.toSet().size == keys.size) { "备份中存在重复的事件类型标识" }
        require(types.map { it.name }.toSet().size == types.size) {
            "备份中存在同名事件类型"
        }
        require(types.map { it.typeKey }.toSet().containsAll(DefaultEventTypes.builtInKeys)) {
            "备份缺少内置事件类型"
        }
        return types
    }

    private fun parseSegments(
        root: JSONObject,
        defaultPersonId: Long? = null,
    ): List<WearSegment> {
        val segmentArray = root.getJSONArray("segments")
        val segments = mutableListOf<WearSegment>()
        for (index in 0 until segmentArray.length()) {
            val item = segmentArray.getJSONObject(index)
            segments.add(
                WearSegment(
                    personId = defaultPersonId ?: item.getLong("personId"),
                    startAt = item.getLong("startAt"),
                    endAt = if (item.isNull("endAt")) null else item.getLong("endAt"),
                )
            )
        }
        return segments
    }

    private fun parseEvents(
        root: JSONObject,
        defaultPersonId: Long? = null,
    ): List<EventRecord> {
        val eventArray = root.getJSONArray("events")
        val events = mutableListOf<EventRecord>()
        for (index in 0 until eventArray.length()) {
            val item = eventArray.getJSONObject(index)
            events.add(
                EventRecord(
                    personId = defaultPersonId ?: item.getLong("personId"),
                    type = item.getString("type"),
                    occurredAt = item.getLong("occurredAt"),
                    note = if (item.isNull("note")) {
                        null
                    } else {
                        item.optString("note").takeIf { it.isNotBlank() }
                    },
                )
            )
        }
        return events
    }

    private fun parseDailyRandomNumbers(root: JSONObject): List<DailyRandomNumber> {
        val array = root.getJSONArray("dailyRandomNumbers")
        val numbers = mutableListOf<DailyRandomNumber>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val number = item.getString("number")
            require(number.length == 5 && number.all { it in '0'..'9' }) {
                "备份中存在无效的每日随机数"
            }
            require(number.toInt() in 10_000..99_999) {
                "备份中的每日随机数超出范围"
            }
            numbers.add(
                DailyRandomNumber(
                    personId = item.getLong("personId"),
                    epochDay = item.getLong("epochDay"),
                    number = number,
                    createdAt = item.optLong("createdAt", 0L),
                )
            )
        }

        val keys = numbers.map { it.personId to it.epochDay }
        require(keys.toSet().size == keys.size) { "备份中存在重复的每日随机数" }
        numbers.groupBy { it.epochDay }.forEach { (_, entries) ->
            require(entries.map { it.number }.toSet().size == entries.size) {
                "备份中同一天不同人物的随机数重复"
            }
        }
        return numbers
    }

    private fun validate(
        persons: List<Person>,
        eventTypes: List<EventTypeEntity>,
        segments: List<WearSegment>,
        events: List<EventRecord>,
        dailyRandomNumbers: List<DailyRandomNumber>,
    ) {
        val personIds = persons.map { it.id }.toSet()
        val typeKeys = eventTypes.map { it.typeKey }.toSet()

        require(segments.all { it.personId in personIds }) { "备份中有分段引用了不存在的人物" }
        require(events.all { it.personId in personIds }) { "备份中有事件引用了不存在的人物" }
        require(events.all { it.type in typeKeys }) { "备份中有事件引用了不存在的事件类型" }
        require(dailyRandomNumbers.all { it.personId in personIds }) {
            "备份中有每日随机数引用了不存在的人物"
        }

        segments.groupBy { it.personId }.forEach { (_, personSegments) ->
            validateSegments(personSegments)
        }
    }

    private fun validateSegments(segments: List<WearSegment>) {
        val sorted = segments.sortedBy { it.startAt }
        require(sorted.count { it.endAt == null } <= 1) {
            "备份中同一人物同时存在多个佩戴中的分段"
        }

        var previousEnd = Long.MIN_VALUE
        for (segment in sorted) {
            require(segment.endAt == null || segment.endAt >= segment.startAt) {
                "备份中存在结束时间早于开始时间的分段"
            }
            require(segment.startAt >= previousEnd) { "备份中同一人物的分段存在重叠" }
            previousEnd = segment.endAt ?: Long.MAX_VALUE
        }
    }
}
