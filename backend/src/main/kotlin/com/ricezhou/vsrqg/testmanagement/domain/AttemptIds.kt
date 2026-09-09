package com.ricezhou.vsrqg.testmanagement.domain

object AttemptIds {
    fun fromGenerated(value: String): String {
        require(Regex("att_[0-9a-f]{32}").matches(value)) { "Invalid generated Attempt identity" }
        val h = value.removePrefix("att_")
        return "${h.take(8)}-${h.substring(8,12)}-${h.substring(12,16)}-${h.substring(16,20)}-${h.substring(20)}"
    }
}
