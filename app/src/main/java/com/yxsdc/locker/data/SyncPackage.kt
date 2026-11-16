package com.yxsdc.locker.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 掌管者同步包的明文清单与加密载荷的编解码。
 * zip 结构：manifest.json（明文，含身份校验信息）+ payload.bin（加密的内层 zip）。
 */
object SyncPackage {
    const val FORMAT_VERSION = 1
    const val KIND = "locker-sync"

    data class Manifest(
        val identityId: String,
        val identityName: String,
        val codeSalt: String,
        val codeHash: String,
        val exportedAt: Long,
        val fromEpochDay: Long,
        val toEpochDay: Long,
    )

    /** 照片内容引用：只携带日期与指纹，真正字节在打包/应用时按需读写。 */
    data class SyncPhotoRef(
        val epochDay: Long,
        val fingerprint: String,
    )

    data class Payload(
        val personName: String,
        val eventTypes: List<EventTypeEntity>,
        val segments: List<WearSegment>,
        val events: List<EventRecord>,
        val randomNumbers: List<DailyRandomNumber>,
        /** 照片只记录日期与内容指纹；字节在打包/应用时逐张读写。 */
        val photos: List<SyncPhotoRef> = emptyList(),
    )

    fun encodeManifest(manifest: Manifest): String = JSONObject().apply {
        put("formatVersion", FORMAT_VERSION)
        put("kind", KIND)
        put(
            "identity",
            JSONObject().apply {
                put("id", manifest.identityId)
                put("name", manifest.identityName)
                put("salt", manifest.codeSalt)
                put("codeHash", manifest.codeHash)
            },
        )
        put("exportedAt", manifest.exportedAt)
        put(
            "window",
            JSONObject().apply {
                put("fromEpochDay", manifest.fromEpochDay)
                put("toEpochDay", manifest.toEpochDay)
            },
        )
    }.toString(2)

    fun decodeManifest(text: String): Manifest {
        try {
            val root = JSONObject(text.trim().removePrefix("﻿"))
            require(root.optString("kind") == KIND) { "不是 Locker 同步数据" }
            require(root.optInt("formatVersion", -1) == FORMAT_VERSION) {
                "同步数据版本不受支持，请升级 App"
            }
            val identity = root.getJSONObject("identity")
            val window = root.getJSONObject("window")
            return Manifest(
                identityId = identity.getString("id"),
                identityName = identity.getString("name"),
                codeSalt = identity.getString("salt"),
                codeHash = identity.getString("codeHash"),
                exportedAt = root.getLong("exportedAt"),
                fromEpochDay = window.getLong("fromEpochDay"),
                toEpochDay = window.getLong("toEpochDay"),
            )
        } catch (exception: JSONException) {
            throw IllegalArgumentException("同步数据清单不完整：${exception.message}", exception)
        }
    }

    /** data.json 内容（载荷内层 zip 中的业务数据）。 */
    fun encodePayloadJson(payload: Payload): ByteArray {
        val root = JSONObject()
        root.put("personName", payload.personName)

        val typeArray = JSONArray()
        payload.eventTypes.forEach { type ->
            typeArray.put(
                JSONObject().apply {
                    put("typeKey", type.typeKey)
                    put("name", type.name)
                    put("colorArgb", type.colorArgb)
                    put("sortOrder", type.sortOrder)
                    put("builtIn", type.builtIn)
                },
            )
        }
        root.put("eventTypes", typeArray)

        val segmentArray = JSONArray()
        payload.segments.sortedBy { it.startAt }.forEach { segment ->
            segmentArray.put(
                JSONObject().apply {
                    put("startAt", segment.startAt)
                    if (segment.endAt == null) put("endAt", JSONObject.NULL) else put("endAt", segment.endAt)
                },
            )
        }
        root.put("segments", segmentArray)

        val eventArray = JSONArray()
        payload.events.sortedBy { it.occurredAt }.forEach { event ->
            eventArray.put(
                JSONObject().apply {
                    put("type", event.type)
                    put("occurredAt", event.occurredAt)
                    if (event.note.isNullOrBlank()) put("note", JSONObject.NULL) else put("note", event.note)
                },
            )
        }
        root.put("events", eventArray)

        val numberArray = JSONArray()
        payload.randomNumbers.sortedBy { it.epochDay }.forEach { entry ->
            numberArray.put(
                JSONObject().apply {
                    put("epochDay", entry.epochDay)
                    put("number", entry.number)
                    put("createdAt", entry.createdAt)
                },
            )
        }
        root.put("randomNumbers", numberArray)

        // photoDays 为旧版本保留的纯日期数组；photoRefs 额外携带指纹，
        // 新版本导入时无需再逐张读取照片即可完成比对。
        val photoDays = JSONArray()
        val photoRefs = JSONArray()
        payload.photos.sortedBy { it.epochDay }.forEach { photo ->
            photoDays.put(photo.epochDay)
            photoRefs.put(
                JSONObject().apply {
                    put("epochDay", photo.epochDay)
                    put("fingerprint", photo.fingerprint)
                },
            )
        }
        root.put("photoDays", photoDays)
        root.put("photoRefs", photoRefs)

        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodePayloadJson(data: ByteArray): Payload {
        try {
            val root = JSONObject(String(data, Charsets.UTF_8))
            val types = root.getJSONArray("eventTypes")
            val segments = root.getJSONArray("segments")
            val events = root.getJSONArray("events")
            val numbers = root.getJSONArray("randomNumbers")
            val photoDays = root.optJSONArray("photoDays")
            val photoRefs = root.optJSONArray("photoRefs")

            return Payload(
                personName = root.getString("personName"),
                eventTypes = (0 until types.length()).map { index ->
                    val type = types.getJSONObject(index)
                    EventTypeEntity(
                        typeKey = type.getString("typeKey"),
                        name = type.getString("name"),
                        colorArgb = type.getLong("colorArgb"),
                        sortOrder = type.getInt("sortOrder"),
                        builtIn = type.getBoolean("builtIn"),
                    )
                },
                segments = (0 until segments.length()).map { index ->
                    val segment = segments.getJSONObject(index)
                    WearSegment(
                        personId = 0,
                        startAt = segment.getLong("startAt"),
                        endAt = if (segment.isNull("endAt")) null else segment.getLong("endAt"),
                    )
                },
                events = (0 until events.length()).map { index ->
                    val event = events.getJSONObject(index)
                    EventRecord(
                        personId = 0,
                        type = event.getString("type"),
                        occurredAt = event.getLong("occurredAt"),
                        note = if (event.isNull("note")) null else event.getString("note"),
                    )
                },
                randomNumbers = (0 until numbers.length()).map { index ->
                    val entry = numbers.getJSONObject(index)
                    DailyRandomNumber(
                        personId = 0,
                        epochDay = entry.getLong("epochDay"),
                        number = entry.getString("number"),
                        createdAt = entry.getLong("createdAt"),
                    )
                },
                photos = when {
                    photoRefs != null -> (0 until photoRefs.length()).map { index ->
                        val ref = photoRefs.getJSONObject(index)
                        SyncPhotoRef(
                            epochDay = ref.getLong("epochDay"),
                            fingerprint = ref.getString("fingerprint"),
                        )
                    }

                    photoDays != null -> (0 until photoDays.length()).map { index ->
                        // 旧版本包只有日期，指纹留空，由读取方补算
                        SyncPhotoRef(epochDay = photoDays.getLong(index), fingerprint = "")
                    }

                    else -> emptyList()
                },
            )
        } catch (exception: JSONException) {
            throw IllegalArgumentException("同步数据内容不完整：${exception.message}", exception)
        }
    }
}

enum class SyncCategory { SEGMENT, EVENT, PHOTO, RANDOM_NUMBER }

enum class SyncAction { ADD, REMOVE, MODIFY }

/** 一条待确认的差异；携带应用所需的本地/远端实体。 */
data class SyncChange(
    val key: String,
    val category: SyncCategory,
    val action: SyncAction,
    val title: String,
    val detail: String,
    val localSegment: WearSegment? = null,
    val localEvent: EventRecord? = null,
    val localPhotoDay: Long? = null,
    val localNumber: DailyRandomNumber? = null,
    val incomingSegment: WearSegment? = null,
    val incomingEvent: EventRecord? = null,
    val incomingPhoto: SyncPackage.SyncPhotoRef? = null,
    val incomingNumber: DailyRandomNumber? = null,
)

private val dayFormatter = DateTimeFormatter.ofPattern("MM-dd", Locale.CHINA)
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
private val dateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA)

private fun formatDay(epochMillis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).format(dayFormatter)

private fun formatTime(epochMillis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).format(timeFormatter)

private fun formatDateTime(epochMillis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).format(dateTimeFormatter)

private fun formatEnd(endAt: Long?, zone: ZoneId): String =
    endAt?.let { formatDateTime(it, zone) } ?: "佩戴中"

/**
 * 按自然键配对计算本地与远端数据的差异：
 * 分段按戴上时间、事件按（类型+发生时间）、照片按日期+内容、随机数按日期+号码。
 */
fun computeSyncChanges(
    localSegments: List<WearSegment>,
    localEvents: List<EventRecord>,
    localPhotos: List<SyncPackage.SyncPhotoRef>,
    localNumbers: List<DailyRandomNumber>,
    payload: SyncPackage.Payload,
    typeNames: Map<String, String>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<SyncChange> {
    val incomingPhotos = payload.photos
    val changes = mutableListOf<SyncChange>()

    val localSegByStart = localSegments.associateBy { it.startAt }
    val incomingSegByStart = payload.segments.associateBy { it.startAt }
    (localSegByStart.keys + incomingSegByStart.keys).sorted().forEach { start ->
        val local = localSegByStart[start]
        val incoming = incomingSegByStart[start]
        val title = "分段 ${formatDay(start, zone)} ${formatTime(start, zone)}–" +
            formatEnd(incoming?.endAt ?: local?.endAt, zone)
        when {
            local == null && incoming != null -> changes += SyncChange(
                key = "seg-$start",
                category = SyncCategory.SEGMENT,
                action = SyncAction.ADD,
                title = title,
                detail = "新增分段",
                incomingSegment = incoming,
            )

            local != null && incoming == null -> changes += SyncChange(
                key = "seg-$start",
                category = SyncCategory.SEGMENT,
                action = SyncAction.REMOVE,
                title = title,
                detail = "删除分段",
                localSegment = local,
            )

            local != null && incoming != null && local.endAt != incoming.endAt -> changes += SyncChange(
                key = "seg-$start",
                category = SyncCategory.SEGMENT,
                action = SyncAction.MODIFY,
                title = title,
                detail = "结束时间 ${formatEnd(local.endAt, zone)} → ${formatEnd(incoming.endAt, zone)}",
                localSegment = local,
                incomingSegment = incoming,
            )
        }
    }

    val localEvByKey = localEvents.associateBy { "${it.type}#${it.occurredAt}" }
    val incomingEvByKey = payload.events.associateBy { "${it.type}#${it.occurredAt}" }
    (localEvByKey.keys + incomingEvByKey.keys).sorted().forEach { key ->
        val local = localEvByKey[key]
        val incoming = incomingEvByKey[key]
        val sample = incoming ?: local ?: return@forEach
        val typeName = typeNames[sample.type] ?: "未知事件"
        val title = "事件「$typeName」${formatDateTime(sample.occurredAt, zone)}"
        val localNote = local?.note?.takeUnless { it.isBlank() }
        val incomingNote = incoming?.note?.takeUnless { it.isBlank() }
        when {
            local == null && incoming != null -> changes += SyncChange(
                key = "evt-$key",
                category = SyncCategory.EVENT,
                action = SyncAction.ADD,
                title = title,
                detail = "新增事件" + (incomingNote?.let { "（$it）" } ?: ""),
                incomingEvent = incoming,
            )

            local != null && incoming == null -> changes += SyncChange(
                key = "evt-$key",
                category = SyncCategory.EVENT,
                action = SyncAction.REMOVE,
                title = title,
                detail = "删除事件",
                localEvent = local,
            )

            local != null && incoming != null && localNote != incomingNote -> changes += SyncChange(
                key = "evt-$key",
                category = SyncCategory.EVENT,
                action = SyncAction.MODIFY,
                title = title,
                detail = "备注 ${localNote ?: "（空）"} → ${incomingNote ?: "（空）"}",
                localEvent = local,
                incomingEvent = incoming,
            )
        }
    }

    val localPhotoByDay = localPhotos.associateBy { it.epochDay }
    val incomingPhotoByDay = incomingPhotos.associateBy { it.epochDay }
    (localPhotoByDay.keys + incomingPhotoByDay.keys).sorted().forEach { epochDay ->
        val local = localPhotoByDay[epochDay]
        val incoming = incomingPhotoByDay[epochDay]
        val title = "照片 ${formatDay(epochDay * 86_400_000L, zone)}"
        when {
            local == null && incoming != null -> changes += SyncChange(
                key = "photo-$epochDay",
                category = SyncCategory.PHOTO,
                action = SyncAction.ADD,
                title = title,
                detail = "新增照片",
                incomingPhoto = incoming,
            )

            local != null && incoming == null -> changes += SyncChange(
                key = "photo-$epochDay",
                category = SyncCategory.PHOTO,
                action = SyncAction.REMOVE,
                title = title,
                detail = "删除照片",
                localPhotoDay = epochDay,
            )

            local != null && incoming != null && local.fingerprint != incoming.fingerprint ->
                changes += SyncChange(
                    key = "photo-$epochDay",
                    category = SyncCategory.PHOTO,
                    action = SyncAction.MODIFY,
                    title = title,
                    detail = "照片内容被替换",
                    localPhotoDay = epochDay,
                    incomingPhoto = incoming,
                )
        }
    }

    val localNumByDay = localNumbers.associateBy { it.epochDay }
    val incomingNumByDay = payload.randomNumbers.associateBy { it.epochDay }
    (localNumByDay.keys + incomingNumByDay.keys).sorted().forEach { epochDay ->
        val local = localNumByDay[epochDay]
        val incoming = incomingNumByDay[epochDay]
        val title = "随机数 ${formatDay(epochDay * 86_400_000L, zone)}"
        when {
            local == null && incoming != null -> changes += SyncChange(
                key = "num-$epochDay",
                category = SyncCategory.RANDOM_NUMBER,
                action = SyncAction.ADD,
                title = title,
                detail = "新增号码 ${incoming.number}",
                incomingNumber = incoming,
            )

            local != null && incoming == null -> changes += SyncChange(
                key = "num-$epochDay",
                category = SyncCategory.RANDOM_NUMBER,
                action = SyncAction.REMOVE,
                title = title,
                detail = "删除号码 ${local.number}",
                localNumber = local,
            )

            local != null && incoming != null && local.number != incoming.number -> changes += SyncChange(
                key = "num-$epochDay",
                category = SyncCategory.RANDOM_NUMBER,
                action = SyncAction.MODIFY,
                title = title,
                detail = "号码 ${local.number} → ${incoming.number}",
                localNumber = local,
                incomingNumber = incoming,
            )
        }
    }

    return changes
}

/** 导入预览的默认勾选：只勾新增与修改；删除本机数据需用户主动勾选。 */
fun defaultSelectedSyncKeys(changes: List<SyncChange>): Set<String> =
    changes.filter { it.action != SyncAction.REMOVE }.map { it.key }.toSet()
