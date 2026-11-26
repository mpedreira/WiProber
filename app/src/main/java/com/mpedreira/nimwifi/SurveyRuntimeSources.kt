package com.mpedreira.nimwifi

import java.util.UUID

fun interface MillisClock {
    fun now(): Long
}

fun interface IdSource {
    fun newId(): String
}

internal object SystemMillisClock : MillisClock {
    override fun now(): Long = System.currentTimeMillis()
}

/** Monotonic milliseconds (never jumps with wall-clock changes); same base for every in-app timer. */
internal object MonotonicMillisClock : MillisClock {
    override fun now(): Long = System.nanoTime() / 1_000_000L
}

internal object UuidIdSource : IdSource {
    override fun newId(): String = UUID.randomUUID().toString()
}
