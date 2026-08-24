package com.ricezhou.vsrqg.shared.application

interface IdempotentExecutor {
    fun <T : Any> execute(
        scope: String,
        principalId: String,
        key: String,
        requestDigest: String,
        responseType: Class<T>,
        action: () -> T,
    ): T
}

class IdempotencyConflict(scope: String) :
    RuntimeException("Idempotency key was reused with a different request in scope '$scope'")
