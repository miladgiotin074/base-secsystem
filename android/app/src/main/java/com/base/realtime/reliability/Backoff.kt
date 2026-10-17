package com.base.realtime.reliability

import kotlin.random.Random

fun reconnectDelayMillis(failures: Int): Long {
    val shift = (failures - 1).coerceIn(0, 5)
    return minOf(30_000L, 1_000L shl shift) + Random.nextLong(250)
}

fun ackDelayMillis(attemptsAfterSend: Int): Long {
    val shift = (attemptsAfterSend - 1).coerceIn(0, 4)
    return minOf(60_000L, 5_000L shl shift)
}
