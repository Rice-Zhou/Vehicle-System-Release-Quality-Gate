package com.ricezhou.vsrqg.release.application

import com.ricezhou.vsrqg.release.domain.Release
import com.ricezhou.vsrqg.release.domain.ReleaseStateHistory
import com.ricezhou.vsrqg.shared.application.ResourceConflict

interface ReleaseRepository {
    fun insert(release: Release)

    fun appendStateHistory(history: ReleaseStateHistory)

    fun find(id: String): Release?
}

class ReleaseAlreadyExists(cause: Throwable) :
    ResourceConflict(
        "RELEASE_ALREADY_EXISTS",
        "Release already exists",
        "A release with the same stable build identity already exists",
        cause,
    )
