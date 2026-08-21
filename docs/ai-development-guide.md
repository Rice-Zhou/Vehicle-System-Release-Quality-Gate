# AI Development Guide

This repository is designed to be worked on by multiple AI coding agents.

## Required Reading Order

Before modifying code:

1. `docs/00-architecture-freeze.md`
2. `docs/project-constitution.md`
3. `docs/core-contract.md`
4. `docs/system-architecture.md`
5. `docs/roadmap.md`
6. relevant ADRs
7. relevant implementation code

## Rules

1. Do not change Core Contract without an ADR.
2. Do not make AI the deterministic Quality Gate authority.
3. Do not couple Core Domain to Jira or another external system.
4. New external integrations must use an Adapter.
5. New runtime collectors must use the Test Agent plugin model.
6. Quality Rules must be versioned and data/config driven.
7. Preserve historical interpretation of existing Release Results.
8. Do not silently change schemas; provide migrations.
9. Add tests for new domain behavior.
10. Prefer small, reversible changes.
11. Do not refactor unrelated code during feature work.
12. If the requested change conflicts with the frozen architecture, stop and propose an ADR instead.

## Commit Guidance

Commits should explain one logical change.

Examples:

- `docs: freeze release quality gate architecture`
- `feat(manifest): add artifact integrity validation`
- `feat(traceability): link issue to build`
- `feat(agent): collect ANR evidence`
- `test(quality): add critical ANR blocking rule`

## Agent Completion Checklist

Before declaring a task complete:

- architecture checked
- Core Contract preserved
- tests added/updated
- migration added if needed
- documentation updated
- ADR added if architectural
- no unrelated changes
