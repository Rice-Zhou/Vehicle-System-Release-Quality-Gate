# Roadmap

## Phase 0 — Architecture Freeze

Goal: establish the foundation.

Deliverables:

- Core Contract
- Architecture Freeze
- Project Constitution
- Manifest Schema
- ADR mechanism
- Repository structure

## Phase 1 — MVP

Goal: prove the complete end-to-end chain with one real device.

Scope:

1. Release creation
2. Manifest registration
3. Artifact checksum verification
4. Jira adapter
5. Basic internal issue adapter
6. Git/build traceability
7. One test bench
8. Test Agent
9. Smoke tests
10. Crash collection
11. ANR collection
12. Evidence storage
13. Basic Quality Rules
14. Release report

Success condition:

A real Release can be processed from creation to deterministic PASS/BLOCK with evidence.

## Phase 2 — Operationalization

Add:

- device pool
- parallel execution
- retry policy
- test scheduling
- richer dashboards
- Memory/CPU/FPS metrics
- regression comparison
- notifications
- CI integration

## Phase 3 — Enterprise Governance

Add:

- multiple vehicle programs
- role-based access
- approval workflow
- quality trend analysis
- cross-release analytics
- supplier/third-party artifact governance

## Phase 4 — Intelligent Assistance

Add AI as an advisory layer:

- issue clustering
- root-cause suggestions
- failure summarization
- similar-failure retrieval
- release risk explanation
- test selection recommendations

AI must remain outside the deterministic final Quality Gate decision path unless a future ADR explicitly changes this policy.

## Non-Goals for MVP

- full autonomous testing of every vehicle feature
- AI-driven release decisions
- microservice decomposition for its own sake
- replacing Jira/internal issue systems
- replacing existing CI/build systems
