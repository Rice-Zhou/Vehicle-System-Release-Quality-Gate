package com.ricezhou.vsrqg.issue.application

fun interface IssueSnapshotCanonicalizer {
    fun canonicalize(candidate: IssueSnapshotCandidate): CanonicalIssueSnapshot
}
