package com.yxsdc.locker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class RandomNumberTest {
    @Test
    fun `always generates five digits without leading zero`() {
        val random = Random(42)
        repeat(10_000) {
            val value = generateFiveDigitNumber(random)
            assertEquals(5, value.length)
            assertTrue(value.toInt() in 10_000..99_999)
        }
    }
}
