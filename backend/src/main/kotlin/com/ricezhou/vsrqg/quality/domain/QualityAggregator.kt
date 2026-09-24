package com.ricezhou.vsrqg.quality.domain

object QualityAggregator {
    fun aggregate(statuses: List<RuleStatus>): String = when {
        statuses.isEmpty() || RuleStatus.ERROR in statuses || statuses.all { it == RuleStatus.NOT_APPLICABLE } -> "ERROR"
        RuleStatus.BLOCK in statuses -> "BLOCK"
        RuleStatus.WARNING in statuses -> "WARNING"
        else -> "PASS"
    }
}
