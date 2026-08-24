package com.ricezhou.vsrqg.access.domain

data class Principal(
    val issuer: String,
    val subject: String,
    val service: Boolean,
)
