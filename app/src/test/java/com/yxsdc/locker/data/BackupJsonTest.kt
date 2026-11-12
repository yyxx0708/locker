package com.yxsdc.locker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupJsonTest {
    private val builtInTypes = DefaultEventTypes.builtIns()
    private val customType = EventTypeEntity(
        typeKey = "custom_a",
        name = "自定义",
        colorArgb = EventColors.GREEN,
        sortOrder = 2,
        builtIn = false,
    )

    @Test
    fun `round trip preserves persons event types segments events and random numbers`() {
        val persons = listOf(
            Person(id = 1, name = "我", createdAt = 500L),
            Person(id = 2, name = "她", createdAt = 600L),
        )
        val eventTypes = builtInTypes + customType
        val segments = listOf(
            WearSegment(id = 5, personId = 1, startAt = 1000L, endAt = 2000L),
            WearSegment(id = 9, personId = 2, startAt = 3000L, endAt = null),
        )
        val dailyRandomNumbers = listOf(
            DailyRandomNumber(personId = 1, epochDay = 20_000, number = "12345", createdAt = 700L),
            DailyRandomNumber(personId = 2, epochDay = 20_000, number = "67890", createdAt = 701L),
            DailyRandomNumber(personId = 1, epochDay = 20_001, number = "12345", createdAt = 702L),
        )
        val events = listOf(
            EventRecord(
                id = 2,
                personId = 1,
                type = DefaultEventTypes.LOCKED_KEY,
                occurredAt = 1500L,
                note = "a",
            ),
            EventRecord(
                id = 3,
                personId = 2,
                type = customType.typeKey,
                occurredAt = 2500L,
                note = null,
            ),
        )

        val decoded = BackupJson.decode(
            BackupJson.encode(
                BackupJson.BackupData(
                    persons = persons,
                    eventTypes = eventTypes,
                    segments = segments,
                    events = events,
                    dailyRandomNumbers = dailyRandomNumbers,
                )
            )
        )

        assertEquals(2, decoded.persons.size)
        assertEquals("我", decoded.persons[0].name)
        assertEquals("她", decoded.persons[1].name)

        assertEquals(3, decoded.eventTypes.size)
        val decodedCustom = decoded.eventTypes.first { it.typeKey == customType.typeKey }
        assertEquals(customType.name, decodedCustom.name)
        assertEquals(EventColors.GREEN, decodedCustom.colorArgb)
        assertTrue(!decodedCustom.builtIn)

        assertEquals(2, decoded.segments.size)
        assertEquals(1L, decoded.segments[0].personId)
        assertEquals(1000L, decoded.segments[0].startAt)
        assertEquals(2000L, decoded.segments[0].endAt ?: -1L)
        assertEquals(2L, decoded.segments[1].personId)
        assertNull(decoded.segments[1].endAt)

        assertEquals(2, decoded.events.size)
        assertEquals(1L, decoded.events[0].personId)
        assertEquals(DefaultEventTypes.LOCKED_KEY, decoded.events[0].type)
        assertEquals(1500L, decoded.events[0].occurredAt)
        assertEquals("a", decoded.events[0].note)
        assertEquals(customType.typeKey, decoded.events[1].type)
        assertNull(decoded.events[1].note)

        assertEquals(3, decoded.dailyRandomNumbers.size)
        assertEquals(20_000L, decoded.dailyRandomNumbers[0].epochDay)
        assertEquals(1L, decoded.dailyRandomNumbers[0].personId)
        assertEquals("12345", decoded.dailyRandomNumbers[0].number)
        assertEquals("67890", decoded.dailyRandomNumbers[1].number)
        assertEquals(20_001L, decoded.dailyRandomNumbers[2].epochDay)
    }

    @Test
    fun `legacy v1 backup is assigned to the default person and types`() {
        val text = """
            {"schemaVersion":1,"segments":[
              {"startAt":1000,"endAt":2000},
              {"startAt":3000,"endAt":null}
            ],"events":[
              {"type":"locked","occurredAt":1500,"note":"a"}
            ]}
        """.trimIndent()

        val decoded = BackupJson.decode(text)

        assertEquals(1, decoded.persons.size)
        assertEquals(DEFAULT_PERSON_NAME, decoded.persons.single().name)
        assertEquals(DefaultEventTypes.builtIns(), decoded.eventTypes)
        assertEquals(2, decoded.segments.size)
        assertEquals(decoded.persons.single().id, decoded.segments[0].personId)
        assertEquals(decoded.persons.single().id, decoded.events.single().personId)
        assertTrue(decoded.dailyRandomNumbers.isEmpty())
    }

    @Test
    fun `legacy v2 backup uses built-in event types`() {
        val text = """
            {"schemaVersion":2,"persons":[
              {"id":1,"name":"我","createdAt":1}
            ],"segments":[],"events":[
              {"personId":1,"type":"unlocked","occurredAt":1000,"note":null}
            ]}
        """.trimIndent()

        val decoded = BackupJson.decode(text)

        assertEquals(DefaultEventTypes.builtIns(), decoded.eventTypes)
        assertEquals(DefaultEventTypes.UNLOCKED_KEY, decoded.events.single().type)
        assertTrue(decoded.dailyRandomNumbers.isEmpty())
    }

    @Test
    fun `overlapping segments of different persons are allowed`() {
        val text = """
            {"schemaVersion":3,"persons":[
              {"id":1,"name":"我","createdAt":1},
              {"id":2,"name":"她","createdAt":2}
            ],"eventTypes":$builtInTypesJson,"segments":[
              {"personId":1,"startAt":1000,"endAt":3000},
              {"personId":2,"startAt":2000,"endAt":4000}
            ],"events":[]}
        """.trimIndent()

        val decoded = BackupJson.decode(text)

        assertEquals(2, decoded.segments.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `overlapping segments of the same person are rejected`() {
        val text = """
            {"schemaVersion":3,"persons":[
              {"id":1,"name":"我","createdAt":1}
            ],"eventTypes":$builtInTypesJson,"segments":[
              {"personId":1,"startAt":1000,"endAt":3000},
              {"personId":1,"startAt":2000,"endAt":4000}
            ],"events":[]}
        """.trimIndent()

        BackupJson.decode(text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `segments referencing an unknown person are rejected`() {
        val text = """
            {"schemaVersion":3,"persons":[
              {"id":1,"name":"我","createdAt":1}
            ],"eventTypes":$builtInTypesJson,"segments":[
              {"personId":9,"startAt":1000,"endAt":3000}
            ],"events":[]}
        """.trimIndent()

        BackupJson.decode(text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `events referencing an unknown type are rejected`() {
        val text = """
            {"schemaVersion":3,"persons":[
              {"id":1,"name":"我","createdAt":1}
            ],"eventTypes":$builtInTypesJson,"segments":[],"events":[
              {"personId":1,"type":"nope","occurredAt":1,"note":null}
            ]}
        """.trimIndent()

        BackupJson.decode(text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `backup missing a built-in type is rejected`() {
        val text = """
            {"schemaVersion":3,"persons":[
              {"id":1,"name":"我","createdAt":1}
            ],"eventTypes":[
              {"typeKey":"locked","name":"锁射","colorArgb":${EventColors.BLUE},"sortOrder":0,"builtIn":true}
            ],"segments":[],"events":[]}
        """.trimIndent()

        BackupJson.decode(text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid event type in legacy backup is rejected`() {
        val text = """
            {"schemaVersion":1,"segments":[],"events":[
              {"type":"other","occurredAt":1,"note":null}
            ]}
        """.trimIndent()

        BackupJson.decode(text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate random numbers on the same day are rejected`() {
        val text = """
            {"schemaVersion":4,"persons":[
              {"id":1,"name":"我","createdAt":1},
              {"id":2,"name":"她","createdAt":2}
            ],"eventTypes":$builtInTypesJson,"segments":[],"events":[],
            "dailyRandomNumbers":[
              {"personId":1,"epochDay":20000,"number":"12345","createdAt":1},
              {"personId":2,"epochDay":20000,"number":"12345","createdAt":2}
            ]}
        """.trimIndent()

        BackupJson.decode(text)
    }

    private val builtInTypesJson = """
        [
          {"typeKey":"locked","name":"锁射","colorArgb":${EventColors.BLUE},"sortOrder":0,"builtIn":true},
          {"typeKey":"unlocked","name":"开锁射精","colorArgb":${EventColors.ORANGE},"sortOrder":1,"builtIn":true}
        ]
    """.trimIndent()
}
