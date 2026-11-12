package com.yxsdc.locker.domain

import java.util.Random

/**
 * 生成 10000–99999 的五位数字，不使用前导 0。
 */
fun generateFiveDigitNumber(random: Random): String =
    (random.nextInt(90_000) + 10_000).toString()
