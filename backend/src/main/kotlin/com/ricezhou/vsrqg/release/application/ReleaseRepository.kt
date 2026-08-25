package com.ricezhou.vsrqg.release.application

import com.ricezhou.vsrqg.release.domain.Release
import com.ricezhou.vsrqg.release.domain.ReleaseStateHistory

interface ReleaseRepository {
    fun insert(release: Release)

    fun appendStateHistory(history: ReleaseStateHistory)

    fun find(id: String): Release?
}
