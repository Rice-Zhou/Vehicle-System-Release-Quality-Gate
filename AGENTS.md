# VSRQG Repository Collaboration Rules

## Required Reading Order

Before modifying the project, read in order:

1. `docs/00-architecture-freeze.md`
2. `docs/project-constitution.md`
3. `docs/core-contract.md`
4. `docs/system-architecture.md`
5. `docs/roadmap.md`
6. `docs/ai-development-guide.md`
7. Relevant ADRs, TDRs, and implementation designs

## Frozen Architecture

The V0.1 Core Contract, Release-centric architecture, Manifest authority, Evidence, Traceability, Deterministic Quality Engine, Adapter, Plugin, and ADR governance are frozen. Any proposal that changes these concepts, responsibilities, authoritative sources, or decision semantics must stop direct implementation and submit an ADR Proposal.

## Branch Languages

- `main`: explanatory prose is Chinese; technical terms, code, APIs, fields, enums, states, filenames, and product names retain their original forms.
- `release`: all Markdown explanatory content is English.
- Both branches must preserve paths, structural anchors, technical semantics, and non-Markdown files.
- A semantic discrepancy must be marked `TRANSLATION_DISCREPANCY` and blocks publication or freeze until resolved.

## Changes and Verification

- Each commit contains one meaningful logical change that can be reviewed independently.
- Force push must not overwrite published history.
- Verifiable content must be verified; failures, missing data, and UNKNOWN must never be silently converted to success.
- V0.2 remains Draft until Architecture Review approval.

## Next Execution Plan After a Change

After any project modification, the final report must contain:

```text
Current result: what this change completed
Git status: uncommitted / committed but not pushed / pushed (include branch and commit)
Next action: one explicit primary action
Prerequisites: required review, permission, input, or dependency; write "None" when absent
Acceptance target: evidence used to determine that the next action succeeded
```

The next-step plan does not expand current authorization. When Owner approval or external permission is required, the report must explicitly wait for it. Unverifiable wording such as "continue optimizing" or "improve later" is prohibited.
