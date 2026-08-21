# Vehicle System Release Quality Gate

> Vehicle System Release Quality Gate (VSRQG) is a release-quality governance platform for Android-based automotive head units.

## Purpose

The platform establishes a deterministic, traceable and auditable quality gate for a complete vehicle-system release, including system images, internally developed APKs, third-party APKs, firmware and configuration.

Core goals:

1. Establish a trustworthy definition of what a Release contains.
2. Validate the Release on real devices.
3. Collect objective runtime evidence such as Crash, ANR, Memory and test results.
4. Trace Issue → Commit → Build → Artifact → Release → Test Run.
5. Convert evidence into deterministic PASS / WARNING / BLOCK decisions.
6. Keep future integrations and capabilities extensible without changing the core architecture.

## Architecture Principle

The core architecture is frozen by `docs/00-architecture-freeze.md`.

New capabilities must be implemented as adapters, plugins, rules or extensions. Changes to Core Contract require an Architecture Decision Record (ADR).

## Initial Scope

MVP focuses on:

- Release Manifest
- Artifact integrity
- Issue adapters
- Git/Build traceability
- One real-device test bench
- Smoke tests
- Crash and ANR collection
- Deterministic Quality Rules
- Release Quality Report

## Repository Structure

```text
docs/
  00-architecture-freeze.md
  project-constitution.md
  system-architecture.md
  core-contract.md
  roadmap.md
  ai-development-guide.md
  adr/
schemas/
```

## Status

Architecture Version: `0.1.0`
Status: **FROZEN FOR MVP DESIGN**
