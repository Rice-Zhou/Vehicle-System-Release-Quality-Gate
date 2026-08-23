# ADR-001 — Release-Centric Quality Gate Architecture

- Status: Accepted
- Date: 2026-08-21

## Context

The delivered product is a complete automotive Android system containing multiple internal and third-party artifacts. Traditional APK-centric testing does not provide sufficient release identity, traceability or auditable quality decisions.

## Decision

Adopt a Release-centric architecture with the following mandatory chain:

Release → Manifest → Artifact/Issue/Environment → Test Run → Evidence → Traceability → Quality Engine → Quality Result.

External systems are adapters. Runtime collectors are plugins. Quality decisions are deterministic and rule-driven.

## Consequences

Positive:

- complete-system release identity
- auditable evidence
- extensibility
- reproducible decisions
- independent external system integrations

Negative:

- requires initial data modeling
- requires integration with existing build/issue systems
- requires real-device infrastructure

## Reversal

Reversal requires an ADR because this decision defines the Core Contract.
