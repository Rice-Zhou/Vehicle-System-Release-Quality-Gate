package com.ricezhou.vsrqg.shared.problem

data class ApiProblem(
    val type: String,
    val title: String,
    val status: Int,
    val code: String,
    val detail: String,
    val instance: String,
    val requestId: String,
    val violations: List<Map<String, Any?>> = emptyList(),
)
