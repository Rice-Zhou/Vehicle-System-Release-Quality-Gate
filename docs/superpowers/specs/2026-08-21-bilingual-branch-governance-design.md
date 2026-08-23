# VSRQG Bilingual Branch Governance Design

- Status: Approved Design Draft
- Date: 2026-08-21
- Repository: `Rice-Zhou/Vehicle-System-Release-Quality-Gate`
- Chinese Branch: `main`
- English Branch: `release`

## 1. Goal

Without changing the frozen V0.1 architecture or V0.2 technical semantics, establish two document sets that are independently readable, mutually verifiable, and provably synchronized:

- `main` uses Chinese for explanatory prose.
- `release` uses English only.
- Architectural concepts, constraints, fields, states, relationships, versions, and acceptance criteria remain semantically equivalent across both branches.

The bilingual branches reduce single-language interpretation risk; they do not create two independent architectures.

## 2. Current State and Migration Scope

Current remotes:

- `main` contains the English V0.1 architecture baseline and the `v0.1.0-architecture` tag.
- `docs/v0.2-implementation-architecture` contains the V0.2 review draft, primarily in Chinese.
- `release` does not yet exist.

This migration covers every Markdown document in the repository, including the root README, CHANGELOG, frozen V0.1 documents, ADRs, V0.2 documents, and TDRs. JSON Schema, scripts, configuration, code, and other non-document files are not translated and must remain byte-identical across the branches.

After bilingual migration, V0.2 remains `0.2.0-draft.1`; translation does not automatically create Design Freeze.

## 3. Branch Roles

### 3.1 `main`

`main` is the Chinese collaboration entry point. Descriptions, context, rationale, responsibilities, failure handling, and acceptance descriptions use Chinese. The following retain their original technical forms:

- Stable terms such as VSRQG, Release, Manifest, Evidence, Traceability, Quality Engine, Adapter, Plugin, ADR, and TDR;
- API paths, JSON/YAML fields, database tables/columns, enums, states, and Rule IDs;
- Code, commands, file paths, branch names, tags, checksums, and commit SHAs;
- Proprietary product or technology names such as PostgreSQL, Kotlin, Spring Boot, OpenAPI, OIDC, and S3.

A technical term may first appear as a Chinese explanation followed by its English Term, then use the stable term. Do not translate identifiers whose contracts would be broken merely to increase the Chinese ratio.

### 3.2 `release`

`release` is the English fallback and semantic-review branch. Natural language, headings, table descriptions, captions, comments, and example explanations in all Markdown must be English. Code, APIs, identifiers, and proprietary names remain the same as `main`.

`release` must not independently introduce a new architecture decision. If Chinese source wording is ambiguous, clarify it in `main` before synchronizing English.

### 3.3 Authority and Conflict

Chinese supports daily collaboration; English supports independent understanding and cross-checking. Together they form the acceptance document set for a published version.

When semantics conflict:

1. Mark `TRANSLATION_DISCREPANCY`.
2. Block merges, version tags, and Design Freeze.
3. Determine correct semantics from frozen V0.1 documents, approved ADRs/TDRs, and Owner intent.
4. Correct both branches.
5. Re-run structural, terminology, and human semantic review.

Do not prescribe that one language always overwrites the other during conflict; that would weaken the fallback's value.

## 4. Directory and Structural Equivalence

Except for local/platform-generated files, the branches must have identical repository path sets. Every Markdown file has a counterpart at the same path and may differ only by language. Non-Markdown files must be byte-identical.

```text
main:    docs/v0.2/02-database-design.md  (Chinese prose)
release: docs/v0.2/02-database-design.md  (English prose)
```

The following are structural anchors and must match:

- Heading-level count and section numbering;
- Table rows/columns and entity inventories;
- Mermaid node IDs, Edges, cardinalities, and state transitions;
- API method/path and Request/Response fields;
- PK/FK, table names, column names, constraints, and enums;
- Rule IDs, states, version numbers, and example data;
- Local relative-link targets;
- Acceptance-item count and stable IDs.

Natural-language paragraphs need not be sentence-for-sentence translations, but SHALL/MUST/MUST NOT, defaults, failures, permissions, boundaries, and acceptance meaning must be equivalent.

## 5. Synchronization Workflow

```text
1. Commit one clearly scoped Chinese change on main
2. Record that main commit SHA
3. Create the matching translation work branch from release
4. Synchronize all non-Markdown changes and translate Markdown
5. Run automated structural/language/non-document consistency checks
6. Manually review terminology, negative conditions, boundaries, and acceptance semantics
7. Merge into release and reference the main SHA in the commit message
8. Create paired version tags only when both branches pass
```

Recommended commit messages:

```text
docs(zh): clarify manifest lock acceptance
docs(en): mirror manifest lock acceptance from main@<sha>
```

Do not combine unrelated architecture changes into one translation commit. Do not correct only `release` without writing the correction back to `main`.

## 6. Version and Pairing Records

Published or frozen versions use paired annotated tags:

```text
v0.2.0-design-zh → main commit
v0.2.0-design-en → release commit
```

Each tag message records the other tag, document version, semantic-review status, and corresponding commit SHA. During Draft, use Pull Request/commit messages to record the source SHA and do not create a tag for every translation change.

Do not store a paired commit's "current commit SHA" inside that commit's files, because self-reference makes it unsolvable. Git tags, GitHub Release notes, and PR metadata are the sources of pairing Evidence.

`CHANGELOG.md` keeps the same entry structure and versions in both branches, using the corresponding language.

## 7. Automated Verification

The repository provides the same `scripts/verify-language-branches.ps1`, which verifies at least:

1. Controlled path sets match between `main` and `release`.
2. Non-Markdown SHA-256 values match, with an allowlist only for branch-governance metadata.
3. `release` Markdown prose contains no CJK characters.
4. Explanatory Markdown on `main` contains Chinese, except pure schema/code files.
5. Local Markdown links resolve within each branch.
6. Code fences are balanced.
7. Structural anchors such as API paths, enums, Rule IDs, and versions are not lost.
8. The frozen V0.1 concept inventory exists in both languages.

Language scanning is only a mechanical gate and does not claim to prove semantic equivalence. Human review is the final acceptance step for semantic equivalence.

## 8. Human Semantic Review Checklist

Compare each paired file, focusing on:

- Consistent negation and normative strength;
- Consistent authority relationships among Release, Manifest, and Evidence;
- Continued separation of Fixed/Included/Verified;
- Errors, missing values, and UNKNOWN not mistranslated as PASS/false/0;
- Consistent primary keys, foreign keys, cardinalities, and state machines;
- Consistent permission allow/prohibit items;
- Consistent Timeout, Retry, disconnect, power-loss, and recovery semantics;
- Consistent Quality Rule conditions, thresholds, units, and priorities;
- Consistent MVP and V0.3 deferred boundaries;
- Consistent ADR/TDR choices and rejected alternatives.

Review status is PASSED, FAILED, or BLOCKED. Only PASSED permits paired publication tags.

## 9. Initial Migration Order

1. Use remote `main` and the V0.2 Draft as inputs to form a complete Chinese candidate.
2. Translate explanatory V0.1 documents to Chinese while preserving every frozen concept and technical identifier.
3. Merge the V0.2 Draft into the Chinese candidate and run the V0.1 frozen-diff check.
4. After review, fast-forward/PR merge into `main`.
5. Create `release` from the corresponding Chinese content and translate every Markdown file to English.
6. Run bilingual automated verification and human semantic review.
7. Push `release`. V0.2 remains Draft pending its original Architecture Review.

Do not declare the old English V0.1 `main` to be the completed new `release`, because it lacks V0.2 documents and has not passed bilingual structural validation.

## 10. Failure and Recovery

- Missing translation: verification fails and the corresponding publication tag is not pushed.
- Branch drift: use the latest passing tag pair as a common baseline and audit subsequent commits separately; do not force reset.
- Incorrect translation already merged: use a new correction commit and do not rewrite public history.
- Non-document mismatch: stop translation and determine whether synchronization was missed or an unauthorized implementation difference exists.
- Partial GitHub push: retain the pushed commit and retry the other branch; create no tag before both complete.
- V0.1 semantics changed by translation: stop immediately and handle as an architecture conflict. Submit an ADR Proposal only when a frozen concept is genuinely changed.

## 11. Acceptance Criteria

Initial bilingual migration is complete only when:

1. Remote `main` and `release` both exist.
2. Both branches contain V0.1 and the complete V0.2 Draft document set.
3. Explanatory prose on `main` is Chinese and technical identifiers remain stable.
4. `release` Markdown CJK count is 0.
5. Non-Markdown file contents match.
6. Every local Markdown link resolves and every code fence is balanced.
7. Frozen V0.1 concepts and V0.2 status are unchanged.
8. Automated structural checks pass.
9. Every human semantic review item is PASSED.
10. GitHub history retains existing `main`, the V0.1 tag, and V0.2 Draft provenance without force push.
11. CHANGELOG on both branches clearly records bilingual migration.
12. V0.2 remains Draft until a separate Architecture Review approves it.

## 12. Next Execution Plan After a Change

After any project-related modification, whether or not it is committed or pushed to GitHub, the completion report must contain a "Next Execution Plan." This applies to documents, Schema, configuration, scripts, code, tests, branches, tags, and other project governance content.

The completion report includes at least:

```text
Current result: what this change completed
Git status: uncommitted / committed but not pushed / pushed (include branch and commit)
Next action: one explicit primary action
Prerequisites: required review, permission, input, or dependency; write "None" when absent
Acceptance target: evidence used to determine that the next action succeeded
```

Rules:

1. The next-step plan is an information and sequencing commitment; it does not automatically expand current authorization. Explicitly wait when Owner decision or external permission is required.
2. If multiple follow-up actions exist, order them by dependency; the first must be concrete and executable.
3. If the current change is uncommitted, first state whether a commit is recommended and its proposed boundary.
4. If committed but not pushed, make remote synchronization and SHA verification the next step or explicitly state why not.
5. If the current phase is complete and the next phase is not approved, write "wait for Owner review/approval" and do not enter implementation.
6. If blocked, the next step is the minimum action needed to remove the block and includes blocking Evidence.
7. Unverifiable wording such as "continue optimizing" or "improve later" is prohibited.

During the initial bilingual migration, this rule must be written into repository-level `AGENTS.md` in the corresponding language on both branches so every future AI or human collaborator can see it. Automated checks may verify that `AGENTS.md` exists with a fixed heading. Reviewers verify that the plan content is truthful.

## 13. Non-Goals

- Translating code identifiers or protocol fields;
- Building a pipeline that directly publishes raw machine translation;
- Rewriting the V0.1 Core Contract through translation;
- Performing V0.2 Design Freeze in this task;
- Introducing a separate documentation platform, database, or complex CI infrastructure for bilingual maintenance.
