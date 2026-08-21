# System Architecture

## 1. Logical Architecture

```text
                        Release Manager
                              |
                       Release Manifest
                              |
          +-------------------+-------------------+
          |                   |                   |
       Artifact            Issue              Environment
       Sources             Sources             / Devices
          |                   |                   |
          +-------------------+-------------------+
                              |
                     Test Orchestrator
                              |
                         Test Agent
                              |
                  +-----------+-----------+
                  |           |           |
               Results      Metrics     Evidence
                  |           |           |
                  +-----------+-----------+
                              |
                     Traceability Engine
                              |
                       Quality Engine
                              |
                    PASS / WARNING / BLOCK
                              |
                       Quality Report
```

## 2. Module Boundaries

### Release Manager

Responsibilities:

- create Release
- assign Release ID
- manage lifecycle
- reference Manifest

Does not:

- execute tests
- parse Jira-specific models
- decide individual test implementation details

### Manifest Manager

Responsibilities:

- store Manifest
- validate artifact completeness
- validate artifact identity/integrity

### Source Adapter

Responsibilities:

- connect external systems
- normalize data
- preserve source references

### Test Orchestrator

Responsibilities:

- select device
- deploy Release
- execute Test Plan
- collect Test Run state

### Test Agent

Responsibilities:

- execute device-side actions
- collect runtime data
- package evidence

### Traceability Engine

Responsibilities:

- construct and validate relationships among Issues, Commits, Builds, Artifacts, Releases and Tests

### Quality Engine

Responsibilities:

- load rule versions
- evaluate facts/evidence
- produce deterministic result
- explain failures

## 3. Data Flow

A normal Release verification flow is:

```text
1. Create Release
2. Build/collect Manifest
3. Validate artifacts
4. Snapshot relevant issues
5. Resolve build/fix traceability
6. Allocate real device
7. Deploy Release
8. Execute Test Plan
9. Collect Evidence
10. Evaluate Traceability
11. Evaluate Quality Rules
12. Generate Quality Result
13. Generate Release Report
```

## 4. Deployment Principle

The initial implementation should favor a modular monolith or small number of services over premature microservice decomposition.

The architecture must permit later extraction of components without changing Core Contract semantics.

## 5. Storage

Recommended initial pattern:

- PostgreSQL for structured domain data
- Object storage for logs, traces, screenshots, dumps and large evidence
- Git/CI/build systems as external source systems

## 6. Communication

The system should expose stable APIs around Core Contract objects.

Internal implementation may use synchronous APIs initially and event-driven processing later.

## 7. Security Boundary

External systems, devices and users are untrusted boundaries.

Credentials and tokens must be stored outside source code and outside Release Manifest data.

## 8. Failure Isolation

Failure of one adapter or one test plugin must not corrupt Release identity or existing historical evidence.

## 9. Versioning

The following require explicit versions:

- Manifest schema
- Test Plan
- Test Case
- Quality Rule
- API contract
- Agent protocol

Historical Release Results must remain interpretable using the versions that produced them.
