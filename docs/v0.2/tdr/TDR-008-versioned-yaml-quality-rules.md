# TDR-008 — Versioned YAML Rules with Restricted AST

- Status: Proposed for V0.2 Review
- Scope: Quality Rule authoring format and execution model

## Problem and Requirements

Rules must be readable, version-controlled, auditable, testable, reversible, and deterministically executable. Quality owners need to review rules, but arbitrary code execution would break security and determinism. MVP rule types are limited.

## Decision and Rationale

Author rules in strict YAML stored in Git and parse them into a restricted, versioned AST. At publication, store source text, AST, digest, and Git commit in the database. Whitelist operators and Fact paths; prohibit scripts, I/O, randomness, and current time. YAML supports review, while the restricted AST provides validation, determinism, and safety.

## Alternatives Not Selected

- JSON: machine-explicit but more verbose for human maintenance; the internal canonical AST may use JSON.
- General DSL/scripts: expressive, but require a compiler, security sandbox, resource limits, and long-term compatibility.
- Database tables only: weaker change review, diff, and rollback experience.
- Hard-coded rules: violate frozen principles and version requirements.

## V0.2 / V0.3 Impact

V0.2 intentionally limits expressiveness to gain trustworthiness. V0.3 can add versioned operators or a UI that generates YAML, but old AST/engine versions must remain replayable.

## Migration and Rollback

A Rule Set references exact Rule versions. Rollback selects the previous published Set. Schema/AST upgrades provide a validator and old interpreter and never rewrite historical Rules.

## Testing, Deployment, and Recovery

Every Rule has match/no-match/missing/null/empty/type-error golden tests. Every operator has a complete Matrix Test, including proof that ERROR propagation is independent of operand order. Parsing-ambiguity, resource-limit, and determinism tests also apply. Deploy Rules through Git/release flow. Retire an erroneous version and publish a new one while preserving historical Results.

## Re-evaluation Triggers

Real Rules repeatedly cannot be expressed by the restricted model, and operator extensions make the engine more complex than adopting a mature rule technology.
