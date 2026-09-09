package com.ricezhou.vsrqg.testmanagement.domain

enum class RunState {
    CREATED, WAITING_FOR_AGENT, RUNNING, COMPLETED, ERROR, TIMEOUT, CANCELLED;
    val terminal: Boolean get() = this in setOf(COMPLETED, ERROR, TIMEOUT, CANCELLED)
}
enum class AttemptState {
    QUEUED, DISPATCHED, ACKED, RUNNING, RECOVERY_PENDING, UPLOADING, COMPLETED, ERROR, TIMEOUT, CANCELLED;
    val terminal: Boolean get() = this in setOf(COMPLETED, ERROR, TIMEOUT, CANCELLED)
}
object SmokePolicy {
    const val LEASE_SECONDS = 90L
    const val ALLOCATION_SECONDS = 60L
    const val CASE_SECONDS = 300L
    const val RUN_SECONDS = 600L
    const val RECOVERY_SECONDS = 120L
    val capabilities = setOf("ADB", "APK_INSTALL", "LOG", "SCREENSHOT")
}
