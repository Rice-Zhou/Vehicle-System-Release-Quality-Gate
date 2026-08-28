# TDR-013 — Controlled Local File Identity and Windows Argument Bridge

- Status: Accepted
- Date: 2026-08-28
- Decision basis: Evidence Archive Windows host argument probe and local file identity failure analysis
- Scope: local input, staging, report publication, and JVM argument passing for the one-shot Evidence Archive operation
- Related decisions: [TDR-004](TDR-004-s3-compatible-evidence-storage.md), [TDR-009](TDR-009-oidc-and-service-identities.md), [TDR-012](TDR-012-evidence-archive-acceptance-operations.md)

## 1. Why this technology was selected

V0.2 uses a local identity strategy layered by file-system capability. Linux/POSIX directories still require no shared write access, and every controlled object must have a non-empty `fileKey`. Windows and other non-POSIX Providers must actually read `AclFileAttributeView`, Owner, and the ACL. Only Owner, and SYSTEM and `BUILTIN\Administrators` resolved through the host `UserPrincipalLookupService` and confirmed equal as objects, may have mutating `ALLOW`. Write, create, attribute/ACL/Owner modification, or delete permission for an unknown principal, an Everyone/Users-class principal, or a principal whose lookup fails causes fail-closed behavior; `DENY` does not offset an unknown `ALLOW`. Only after ACL verification passes and `fileKey` is unavailable may a metadata identity composed of canonical real path, creation time, last-modified time, size, and object type be used. Directories and files use their own applicable fields that remain stable during the operation. After each trusted-channel write, staging-file expected metadata is refreshed and checked again before publication or cleanup.

Parent directories and file objects must provide separate access proof. Every file identity reads that file's own ACL or POSIX permissions, covering input, work package, archive/recovery report, partial file, publication target, and completion marker. A verified parent directory cannot replace file verification. File proof fails closed after open and whenever a read/write/publication boundary changes.

The Source Verifier retains bounded `SeekableByteChannel` reads for the manifest and ZIP files, along with the existing ZIP defenses. It rechecks the source root and exact-file proof/identity/size/timestamps before open, before the first read, after reading, and after close. The completion marker is not created directly at the final path. A random same-directory partial is created first, its empty content is forced, its own ACL, identity, and zero-byte state are verified, and its channel is closed. Only then is the final marker published through a create-only hard link. Successful linking is the irreversible commit point. A failure before it must not create the final marker. A directory-force or partial-cleanup failure after it emits only a fixed redacted warning code and does not delete the final marker or change `PASS` or the canonical report. Concurrent `FileAlreadyExists` must first validate the existing final marker. An exact trusted zero-byte marker counts as already committed, and current partial cleanup becomes non-fatal housekeeping. An invalid final marker remains a conflict failure; cleanup failure is only suppressed or warned and must not replace the root cause or delete the existing object.

Windows JVM invocation uses dedicated non-secret `VSRQG_EVIDENCE_OPERATION_*` environment variables. Gradle accepts only the complete exact set for `archive` or `verify`, and uses `args(listOf(...))` to pass every value as a separate argv token. Unknown, blank, or partial combinations fail with a fixed error. When this bridge is not enabled, the existing `--args` compatibility entry point remains available.

## 2. What problem it solves

In the supported project environment, Windows NIO returns `null` from `BasicFileAttributes.fileKey()`. This caused the canonical invalid `{}` work package to be incorrectly classified as `WORK_PACKAGE_READ_FAILED` before the JVM could read it. In addition, `gradlew.bat` reinterprets nested quoting in paths containing spaces, so a path can be split into Gradle Tasks. This decision lets a stable read in a controlled Windows directory reach schema validation and return exactly `ARCHIVE_INPUT_FAILURE`, without printing local paths, Provider environment, or credentials to stdout/stderr.

This decision only addresses the local file-system and process-launch boundary. It does not change the V0.1 Core Contract, Release-centric architecture, Manifest authority, Evidence, Traceability, Deterministic Quality Engine, Adapter, Plugin, or ADR governance.

## 3. Why alternatives were not selected

- Require Windows to provide `fileKey`: the target NIO Provider cannot satisfy it, which would prevent a controlled Company operation on the approved platform.
- Compare only paths: this cannot detect common replacement or metadata changes and does not meet fail-closed revalidation requirements.
- Disable identity validation or use ordinary `Files.readAllBytes`: this would remove NOFOLLOW, bounded reading, and pre/open/post revalidation and is not acceptable.
- Keep concatenating `--args` quotes: the host probe with spaces has proven this unreliable; shell quoting must not become a security contract.
- Add a resident service, database, or container wrapper: the current one-shot controlled operation does not need another deployment and failure surface.

## 4. Impact on V0.2

Local reads continue to require absolute canonical paths, NOFOLLOW, bounded reads, zero-progress failure, parent pre/open/post revalidation, and before/after checks of file size/time/type. Missing `fileKey` on POSIX continues to fail closed. The non-POSIX metadata fallback depends on Operator-controlled ACLs and a single writer; it does not claim to resist an A-B-A replacement performed by a malicious process that possesses trusted write access. Shared directories, multi-writer directories, and uncontrolled temporary directories are therefore unsupported.

The Gradle bridge variables carry only non-secret paths and mode and must be used with `--no-daemon`. On completion, only task-specific variables are cleared. Provider configuration and credentials still come from the existing repository-external identity chain and cannot be passed through the bridge.

## 5. Impact on future V0.3

If V0.3 introduces a controlled job platform, it can pass argv directly or inject environment variables using the same exact schema without changing the operation CLI. If Company policy requires resistance to a malicious writer with trusted local write access, migration should use a platform primitive with stable handle identity, an isolated execution sandbox, or signed immutable input. That is a new threat model requiring a new TDR; the current metadata fallback must not be described as equivalent protection.

Company S3 Object Lock, exact `versionId`, receipt digest, and Provider protection are independent guarantees for remote long-term Evidence and do not depend on the local file-identity implementation. Future migration must not weaken these controls.

## 6. How to migrate

Existing POSIX execution requires no identity-strategy change. The Windows runbook replaces nested `--args` with a complete `VSRQG_EVIDENCE_OPERATION_*` set, invokes `evidenceArchiveOperation --no-daemon`, and clears the variables in `finally`. Existing automation that safely uses legacy `--args` can be retained temporarily; after migration, the exact environment bridge should be preferred consistently.

Do not migrate or overwrite existing archive/recovery reports, completion markers, or remote object versions. A failed retry uses a new trusted output directory and new execution ID, and preserves the source files and committed exact versions for reconciliation.

## 7. How to test

Unit tests cover stable reads with null `fileKey` under Operator-controlled ACLs; file metadata/ACL changes; parent-directory identity changes; POSIX null `fileKey` and shared-write fail-closed behavior; and existing symlink, size-bound, EOF, ZIP-defense, and zero-progress behavior. Archive and recovery tests cover staged revalidation of source root/manifest/ZIP; identity refresh after partial writes; publication ownership; marker pre-commit close/verification; create-only commit/conflict/idempotency; and post-commit housekeeping failure that preserves the final marker and `PASS` while emitting a redacted warning.

The cross-platform host probe automatically selects `gradlew.bat` or `gradlew`. In a controlled temporary directory whose path contains spaces, it creates a canonical invalid `{}` work package, isolates `VSRQG_*`, `AWS_*`, profile, web identity, and EC2 metadata, and runs archive and verify separately. Each invocation must natively exit `1`, emit exactly `ARCHIVE_INPUT_FAILURE`, and must not emit `READ_FAILED`, `USAGE_ERROR`, a misparsed Gradle Task, a path, or Provider environment, or create a report, recovered file, or marker. Incomplete, unknown, and blank bridge combinations must fail with fixed `EVIDENCE_OPERATION_ENV_INVALID` without printing values. When the bridge is disabled, legacy `--args` must still reach strict work-package validation. Windows path-with-spaces argv behavior can only be proven by a Windows result.

## 8. How to deploy

No service, port, database, messaging system, or image is added. The bridge ships with the existing Gradle operation task. A runner must use Java 21, a controlled repository checkout, a single-writer directory managed by Owner, and a repository-external Provider identity. Every M1 Runner executes the native wrapper host probe. A Unix Runner verifies `gradlew`; a Windows Runner verifies `gradlew.bat`; neither can substitute for proof from the other.

Logs and acceptance records may store only canonical safe JSON, fingerprints, Git locators, digests, and exact object refs. They must not store local absolute paths, raw principals, environment-variable values, or credentials.

## 9. How to recover from failure

If local identity, parent revalidation, bridge combination, or argv parsing fails, stop the operation, preserve the sources and committed remote versions, do not create a completion marker, and do not rewrite failure as success. A marker failure before the hard-link commit can be retried in a new trusted output directory. A housekeeping warning after commit does not revoke completion. The Operator may clean up a remaining random partial only after confirming ownership, and must not delete the final marker. If partial ownership cannot be confirmed, preserve and isolate it rather than risk deleting another writer's file. After correcting ACLs, directory ownership, or launch configuration, retry in a new trusted output directory with a new execution ID.

If the single-writer constraint may have been violated, isolate the local work directory immediately and reacquire the authoritative sources. If credential exposure is suspected, use the external security process to revoke and replace it. No local recovery action may delete a Company S3 Object Lock version or reduce retention.

## Re-evaluation triggers

Re-evaluate when the Windows Provider can supply stable handle/file identity, the Company threat model requires resistance to a trusted writer, the runtime platform prohibits an environment-variable bridge, or V0.3 introduces a controlled job platform. Re-evaluation must not silently modify the frozen V0.1 architecture.
