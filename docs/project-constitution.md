# Project Constitution

## 1. Mission

Build a company-level release quality governance platform for complete automotive Android system releases.

The system is not merely an automated test framework and not merely a dashboard.

It is a system of record for Release identity, runtime evidence, issue closure and release decisions.

## 2. Primary Value

The platform must improve four kinds of trust:

1. Version trust — what exactly was delivered?
2. Runtime trust — what happened on real hardware?
3. Issue trust — were important fixes actually included and verified?
4. Decision trust — why was the Release allowed or blocked?

## 3. Engineering Principles

### Principle A — Evidence over opinion

Every important quality claim should be backed by machine-readable evidence.

### Principle B — Reproducibility over convenience

The same inputs and rule version should produce the same Quality Result.

### Principle C — Explicit over implicit

Important relationships must be represented in data rather than inferred only from naming conventions.

### Principle D — Extension over mutation

New capabilities should be added through adapters/plugins/rules before changing the core.

### Principle E — Real hardware matters

For release-level runtime claims, real-device evidence is authoritative where applicable.

### Principle F — Auditability

A reviewer must be able to move from a Release decision back to the evidence that caused it.

## 4. Anti-Patterns

The following are prohibited unless approved by ADR:

- Treating an APK as the complete Release.
- Making Jira a dependency of the Core Domain.
- Making AI the final Release decision maker.
- Storing only aggregate metrics without raw evidence.
- Declaring an issue verified merely because a commit exists.
- Hard-coding Quality Rules into business logic.
- Introducing direct dependencies between external adapters and the Quality Engine.
- Changing Core Contract to solve a single project-specific problem.

## 5. Change Governance

Any architectural change must have an ADR.

Routine implementation changes do not require ADR if they preserve the frozen architecture.

## 6. Quality of the Platform

The platform itself must be:

- observable
- testable
- versioned
- backward compatible where practical
- documented
- deployable independently
