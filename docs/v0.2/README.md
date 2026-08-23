# V0.2 Implementation Architecture and Technology Decision Specification

- Design Version: `0.2.0-draft.1`
- Status: Architecture Review Draft
- Baseline: V0.1 Architecture `0.1.0` (FROZEN)
- Date: 2026-08-21

## 1. Positioning

V0.1 answers "What is VSRQG?" V0.2 answers "How is it engineered without changing V0.1?" This directory does not revise the Core Contract. It maps frozen concepts to an implementation that can be developed, tested, deployed, and accepted.

```text
V0.1 Concept (FROZEN)
        ↓ implementation mapping only
V0.2 Boundary / Contract / Technology Decision
        ↓ architecture review and design freeze
Implementation → Test → Acceptance
```

## 2. Responsibilities

The project Owner is responsible for WHY, WHAT, BOUNDARY, and ACCEPTANCE.

The implementation party/Codex is responsible for HOW, TECHNOLOGY, IMPLEMENTATION, TEST, and OPTIMIZATION.

Implementation freedom must not cross the frozen architecture or externally visible contracts in this specification. Acceptance focuses on business invariants, behavior, evidence, and recovery capability, not code details such as class names, ORM, or threading models.

## 3. Six-Month Product Engineering Boundary

The goal is for one primary developer, working in spare time for about six months, to deliver an MVP usable in a company of about 300 people. Company-grade trust comes from identity, permissions, Audit, consistency, replay, and recovery capability, not infrastructure count.

The MVP must close this loop:

```text
Real Release → Manifest Lock → Issue Snapshot → Traceability
→ Real-device Test Run → Evidence → Rule Set Evaluation
→ Explainable Quality Result → Replay with the same snapshot
```

The MVP does not introduce Kafka, Redis, Kubernetes, a general workflow engine, graph database, rule scripting platform, or microservice decomposition. Advanced device pools, trend analysis, AI assistance, and cross-project governance are deferred to V0.3.

## 4. Technology Decision Delegation

During V0.2, the implementation party independently selects the concrete technology stack based on requirements, frozen architecture, the six-month boundary, maintainability, testability, and deployment conditions. The project Owner does not need to prescribe a stack in advance.

Every critical technology choice requires a Technology Decision Record (TDR) covering the problem, decision, alternatives, V0.2/V0.3 impact, migration, testing, deployment, recovery, and re-evaluation triggers. A TDR may decide only implementation inside the frozen boundary. If it affects the Core Contract, ownership, authoritative source, or Quality semantics, implementation must stop and an ADR Proposal must be submitted.

## 5. Three Red Lines

1. **Do not change the goal**: VSRQG must not become an AI QA platform, test framework, Jira Dashboard, Crash Dashboard, or CI Dashboard.
2. **Do not change the architecture without authorization**: frozen entities must not be merged or removed, and Manifest, Evidence, Traceability, Quality Engine, Adapter, Plugin, or ADR must not be bypassed.
3. **Do not use technology for its own sake**: new infrastructure must be driven by a current measurable requirement; "it may be needed later" is not an MVP justification.

## 6. Document Navigation

| Document | Question Answered | Primary Acceptance Artifact |
|---|---|---|
| [01-domain-model.md](01-domain-model.md) | How core concepts map to implementation boundaries | Domain relationships and invariants |
| [02-database-design.md](02-database-design.md) | How data is persisted consistently | ER model, keys, constraints, lifecycle |
| [03-api-design.md](03-api-design.md) | How the system exposes stable capabilities | OpenAPI, errors, idempotency, permissions |
| [04-release-manifest-design.md](04-release-manifest-design.md) | How the authoritative Manifest completes its lifecycle | State machine, Lock, concurrency acceptance |
| [05-issue-adapter-design.md](05-issue-adapter-design.md) | How multiple issue sources are isolated and normalized | Port, mapping, synchronization, snapshot |
| [06-traceability-design.md](06-traceability-design.md) | How Fixed, Included, and Verified are proven | Strongly typed traceability Edges and Confidence |
| [07-test-architecture.md](07-test-architecture.md) | How tests are scheduled and recovered | Run/Attempt state machines |
| [08-test-agent-protocol.md](08-test-agent-protocol.md) | How Server and Agent communicate reliably | Registration, lease, ACK, reconnection |
| [09-evidence-design.md](09-evidence-design.md) | How Evidence is stored, validated, and retained | Metadata/Payload integrity |
| [10-quality-engine-design.md](10-quality-engine-design.md) | How a Release receives a deterministic result | Input snapshot, evaluation, replay |
| [11-quality-rule-specification.md](11-quality-rule-specification.md) | How Rules are defined, versioned, and tested | Restricted YAML specification |
| [12-authentication-design.md](12-authentication-design.md) | Who may do what | OIDC, RBAC, service identities, Audit |
| [13-deployment-design.md](13-deployment-design.md) | How the MVP is deployed, monitored, and recovered | Topology, backup, recovery, SLO |
| [14-mvp-implementation-plan.md](14-mvp-implementation-plan.md) | How six months progress by outcomes | Milestones, exit criteria, acceptance matrix |
| [tdr/README.md](tdr/README.md) | Why these technologies were selected | Reviewable Technology Decision Records |

## 7. Version and Change Governance

- Before V0.2 review, use `0.2.0-draft.N`; do not mark it frozen.
- Each Git commit contains one logical change that can be explained and reviewed independently.
- V0.1 baseline, V0.2 Draft, review revisions, and Design Freeze use distinct commits or tags.
- Design Freeze may be created only after this directory's acceptance matrix is complete, conflicting ADRs are closed, and the Owner approves.
- If an architecture issue is found after freeze: stop changes → ADR Proposal → Architecture Review → approval → revision → re-freeze.

## 8. Global Definition of Done

Every topic document must state responsibilities and non-responsibilities, data/interface contracts, exception semantics, version strategy, MVP scope, acceptance conditions, and acceptance evidence. No error, absence, inconsistency, or unavailable external system may be silently converted to success or PASS.

## 9. V0.1 Consistency and ADR Check

| Frozen V0.1 Item | V0.2 Implementation Mapping | Conclusion |
|---|---|---|
| Release-centric | Every Snapshot, Run, Evidence, and Evaluation explicitly references Release | Unchanged |
| Manifest authoritative | After Lock it is the only authoritative Release content definition; external changes do not write back | Unchanged |
| Evidence first-class | Independent Metadata/Payload, ID, lifecycle, and API | Unchanged |
| Traceability | Four strongly typed Edge types plus Test/Evidence verification chain | Unchanged |
| Deterministic Quality | Frozen inputs + versioned Rule Set + replayable evaluation | Unchanged |
| Adapter | Jira/internal systems expose only Normalized Issues through a unified Port | Unchanged |
| Plugin | Collectors extend through the Agent Plugin Contract | Unchanged |
| AI advisory | AI does not enter authoritative final-status computation | Unchanged |
| ADR governance | TDR must not modify the frozen boundary; conflicts require ADR | Unchanged |

This V0.2 design review found no conflict requiring a V0.1 change, so no ADR Proposal was created. If implementation evidence disproves this conclusion, stop the related change immediately and follow the ADR process.
