package com.ricezhou.vsrqg.shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun <T> runConcurrently(count: Int, action: () -> T): List<T> {
    require(count > 0) { "count must be positive" }
    val pool = Executors.newFixedThreadPool(count)
    val start = CountDownLatch(1)
    return try {
        val futures = (1..count).map {
            pool.submit<T> {
                start.await()
                action()
            }
        }
        start.countDown()
        futures.map { it.get(10, TimeUnit.SECONDS) }
    } finally {
        pool.shutdownNow()
    }
}
