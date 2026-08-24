# Bilingual Documentation and Branch Policy

## Branch Responsibilities

- `main` is the Chinese collaboration entry point; explanatory prose is Chinese.
- `release` is the English-only fallback and semantic review branch.
- Both branches express the same architecture and must not evolve independently.

## Protected Technical Content

VSRQG, Release, Manifest, Evidence, Traceability, Quality Engine, Adapter, Plugin, ADR, TDR, API paths, fields, table columns, enums, states, Rule IDs, code, commands, filenames, versions, and product names retain their original technical forms.

## Synchronization Rules

1. Complete one clearly scoped Chinese change on `main`.
2. Create a semantically equivalent English change on `release` and reference the Chinese source SHA in the commit message.
3. Automatically check paths, non-Markdown blobs, language, links, code fences, heading structure, and inline technical tokens.
4. Manually check normative strength, negative conditions, authority relationships, exception semantics, permissions, state machines, and acceptance criteria.
5. Publication or freeze is allowed only after both checks pass.

## Discrepancy Handling

Mark a semantic conflict as `TRANSLATION_DISCREPANCY`; it blocks merges, tags, and Design Freeze. Determine the correct semantics from frozen V0.1 documents, approved ADRs/TDRs, and Owner intent. Correct both branches and re-run verification.

## Version Governance

Drafts record provenance through commits and Pull Requests. Frozen versions use paired annotated tags such as `v0.2.0-design-zh` and `v0.2.0-design-en`. V0.2 currently remains `0.2.0-draft.2`; these Tags must not be created before final Owner approval.

Do not record a paired commit's own SHA inside that commit's files because that creates a self-reference. Record commit pairing in the tag message, Pull Request, or GitHub Release notes.

## Prohibitions

- Do not publish raw machine translation.
- Do not modify the V0.1 Core Contract through translation.
- Do not update only one language branch.
- Do not erase discrepancies with force push.
- Do not equate translation completion with V0.2 Design Freeze.
