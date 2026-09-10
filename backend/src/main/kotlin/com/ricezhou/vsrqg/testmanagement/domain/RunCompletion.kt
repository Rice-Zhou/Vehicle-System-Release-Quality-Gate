package com.ricezhou.vsrqg.testmanagement.domain

data class CompletionAttempt(val state:AttemptState,val hasResult:Boolean,val evidenceResolved:Boolean)
data class CompletionCase(val attempts:List<CompletionAttempt>)
object RunCompletion {
    fun ready(cases:List<CompletionCase>):Boolean = cases.isNotEmpty() && cases.all { case ->
        case.attempts.isNotEmpty() && case.attempts.all { it.state.terminal && it.hasResult && it.evidenceResolved }
    }
}
