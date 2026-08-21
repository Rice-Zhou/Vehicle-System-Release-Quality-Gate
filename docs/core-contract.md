# Core Contract

## 1. Release

Represents a specific system delivery candidate.

Required conceptual fields:

- releaseId
- project
- vehicle/platform
- systemVersion
- buildId
- manifestId
- status
- createdAt

## 2. Release Manifest

Authoritatively describes Release contents.

It contains:

- manifest version
- Release identity
- system artifacts
- APK artifacts
- firmware/configuration artifacts
- integrity metadata

## 3. Artifact

Represents a concrete deliverable component.

Examples:

- APK
- AAB where applicable
- system image
- vendor image
- firmware
- configuration package

Important fields:

- artifactId
- type
- name
- version
- buildId
- source
- checksum

## 4. Issue

Represents a quality or requirement item originating from an external source.

Important fields:

- source
- sourceIssueId
- title
- severity
- status
- fixVersion
- component
- snapshot metadata

## 5. Commit

Represents a source-code change.

Important fields:

- repository
- commitId
- branch
- author
- timestamp

## 6. Build

Represents a reproducible build output.

Important fields:

- buildId
- pipeline
- source revision
- branch
- build timestamp
- artifacts

## 7. Test Plan

A named collection of Test Cases.

## 8. Test Case

A repeatable verification procedure.

It must have a stable caseId and version.

## 9. Test Run

A concrete execution of a Test Plan against a Release and environment.

## 10. Test Result

The outcome of one Test Case in one Test Run.

Possible high-level states:

- PASS
- FAIL
- BLOCKED
- SKIPPED
- ERROR

## 11. Evidence

An immutable or content-addressed piece of proof associated with a Test Run or Result.

Examples:

- log
- screenshot
- ANR trace
- tombstone
- Perfetto trace
- memory dump
- metric sample

## 12. Traceability

Traceability records relationships such as:

```text
Issue → Commit
Commit → Build
Build → Artifact
Artifact → Release
Release → Test Run
Test Run → Test Result
Test Result → Evidence
```

## 13. Fixed / Included / Verified

An issue is:

### Fixed

A valid source change exists.

### Included

The fix is demonstrably present in the target Release.

### Verified

The fix is tested against the target Release and passes its verification criteria.

Only Verified issues satisfy a release verification requirement.

## 14. Quality Rule

A versioned deterministic expression that evaluates Release facts.

A rule must define:

- ruleId
- version
- scope
- condition
- severity/action
- explanation

## 15. Quality Result

The immutable outcome of evaluating a Release against a set of Quality Rules.

It should preserve:

- Release ID
- rule-set version
- evaluation timestamp
- evaluated facts
- rule outcomes
- final status
- failure explanations
