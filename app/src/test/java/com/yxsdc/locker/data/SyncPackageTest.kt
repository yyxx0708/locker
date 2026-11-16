package com.yxsdc.locker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class SyncPackageTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun manifest() = SyncPackage.Manifest(
        identityId = "id-1",
        identityName = "小A",
        codeSalt = "aa",
        codeHash = "bb",
        exportedAt = 1_700_000_000_000L,
        fromEpochDay = 20_000,
        toEpochDay = 20_002,
    )

    @Test
    fun manifestRoundTrip() {
        val decoded = SyncPackage.decodeManifest(SyncPackage.encodeManifest(manifest()))
        assertEquals(manifest(), decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun manifestRejectsForeignPayload() {
        SyncPackage.decodeManifest("""{"kind":"other","formatVersion":1}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun manifestRejectsUnknownVersion() {
        val text = SyncPackage.encodeManifest(manifest()).replace("\"formatVersion\": 1", "\"formatVersion\": 9")
        SyncPackage.decodeManifest(text)
    }

    @Test
    fun payloadJsonRoundTrip() {
        val payload = SyncPackage.Payload(
            personName = "小A",
            eventTypes = listOf(
                EventTypeEntity(typeKey = "custom_uuid", name = "自定义", colorArgb = 0xFF2A78D6, sortOrder = 3, builtIn = false),
            ),
            segments = listOf(WearSegment(personId = 0, startAt = 1000, endAt = 2000)),
            events = listOf(EventRecord(personId = 0, type = "custom_uuid", occurredAt = 1500, note = "备注")),
            randomNumbers = listOf(DailyRandomNumber(personId = 0, epochDay = 20_000, number = "12345", createdAt = 1)),
            photos = listOf(
                SyncPackage.SyncPhotoRef(
                    epochDay = 20_000,
                    fingerprint = DailyReport.photoFingerprint(byteArrayOf(1, 2, 3)),
                ),
            ),
        )
        val decoded = SyncPackage.decodePayloadJson(SyncPackage.encodePayloadJson(payload))
        assertEquals(payload, decoded)
    }

    @Test
    fun diffDetectsSegmentEventPhotoAndNumberChanges() {
        val localSegments = listOf(WearSegment(id = 1, personId = 7, startAt = 1_000, endAt = 2_000))
        val localEvents = listOf(EventRecord(id = 1, personId = 7, type = "t1", occurredAt = 1_500, note = "旧"))
        val localPhotos = listOf(
            SyncPackage.SyncPhotoRef(
                epochDay = 20_000,
                fingerprint = DailyReport.photoFingerprint(byteArrayOf(1)),
            ),
        )
        val localNumbers = listOf(DailyRandomNumber(personId = 7, epochDay = 20_000, number = "11111", createdAt = 0))

        val payload = SyncPackage.Payload(
            personName = "小A",
            eventTypes = emptyList(),
            segments = listOf(WearSegment(personId = 0, startAt = 1_000, endAt = 2_500)),
            events = listOf(
                EventRecord(personId = 0, type = "t1", occurredAt = 1_500, note = "新"),
                EventRecord(personId = 0, type = "t2", occurredAt = 3_000, note = null),
            ),
            randomNumbers = listOf(
                DailyRandomNumber(personId = 0, epochDay = 20_000, number = "22222", createdAt = 0),
                DailyRandomNumber(personId = 0, epochDay = 20_001, number = "33333", createdAt = 0),
            ),
            photos = listOf(
                SyncPackage.SyncPhotoRef(
                    epochDay = 20_000,
                    fingerprint = DailyReport.photoFingerprint(byteArrayOf(2)),
                ),
            ),
        )

        val changes = computeSyncChanges(
            localSegments = localSegments,
            localEvents = localEvents,
            localPhotos = localPhotos,
            localNumbers = localNumbers,
            payload = payload,
            typeNames = mapOf("t1" to "洗手", "t2" to "睡觉"),
            zone = zone,
        )

        val byKey = changes.associateBy { it.key }
        assertEquals(SyncAction.MODIFY, byKey.getValue("seg-1000").action)
        assertEquals(SyncAction.MODIFY, byKey.getValue("evt-t1#1500").action)
        assertEquals(SyncAction.ADD, byKey.getValue("evt-t2#3000").action)
        assertEquals(SyncAction.MODIFY, byKey.getValue("photo-20000").action)
        assertEquals(SyncAction.MODIFY, byKey.getValue("num-20000").action)
        assertEquals(SyncAction.ADD, byKey.getValue("num-20001").action)
        assertTrue(changes.none { it.action == SyncAction.REMOVE })
        assertEquals(6, changes.size)
    }

    @Test
    fun defaultSelectionExcludesRemovals() {
        val changes = listOf(
            SyncChange("seg-1", SyncCategory.SEGMENT, SyncAction.ADD, "", ""),
            SyncChange("evt-1", SyncCategory.EVENT, SyncAction.MODIFY, "", ""),
            SyncChange("photo-1", SyncCategory.PHOTO, SyncAction.REMOVE, "", ""),
            SyncChange("num-1", SyncCategory.RANDOM_NUMBER, SyncAction.REMOVE, "", ""),
        )
        assertEquals(setOf("seg-1", "evt-1"), defaultSelectedSyncKeys(changes))
    }

    @Test
    fun diffComparesPhotoFingerprintsWithoutLoadingBytes() {
        val ref = SyncPackage.SyncPhotoRef(epochDay = 20_000, fingerprint = "fingerprint")
        val changes = computeSyncChanges(
            localSegments = emptyList(),
            localEvents = emptyList(),
            localPhotos = listOf(ref),
            localNumbers = emptyList(),
            payload = SyncPackage.Payload(
                personName = "小A",
                eventTypes = emptyList(),
                segments = emptyList(),
                events = emptyList(),
                randomNumbers = emptyList(),
                photos = listOf(ref),
            ),
            typeNames = emptyMap(),
            zone = zone,
        )
        assertTrue(changes.isEmpty())
    }

    @Test
    fun identicalDataProducesNoChanges() {
        val segment = WearSegment(id = 1, personId = 7, startAt = 1_000, endAt = 2_000)
        val change = computeSyncChanges(
            localSegments = listOf(segment),
            localEvents = emptyList(),
            localPhotos = emptyList(),
            localNumbers = emptyList(),
            payload = SyncPackage.Payload(
                personName = "小A",
                eventTypes = emptyList(),
                segments = listOf(WearSegment(personId = 0, startAt = 1_000, endAt = 2_000)),
                events = emptyList(),
                randomNumbers = emptyList(),
                photos = emptyList(),
            ),
            typeNames = emptyMap(),
            zone = zone,
        )
        assertTrue(change.isEmpty())
    }
}
