package com.miniichat.tasks

object OverlaySizing {
    fun fit(wanted: Int, minimum: Int, available: Int): Int = wanted.coerceIn(minOf(minimum, available.coerceAtLeast(1)), available.coerceAtLeast(1))
}
