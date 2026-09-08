# Company Archive Resource Path Comparison

- Status: DRAFT; selection research only, with no resource assigned or technical decision made.
- Checked on: 2026-09-08.
- Code baseline: Chinese b9936d070ee93a0194b3a3ba0cdaa48f09091b97; English 39471d90719ca61788f30f07eeaa4f96782d4849.
- Owner authorization: prepare a selection comparison first, without assigning resources.
- Scope: the existing M2.5 Evidence Archive work package; no redefinition of frozen architecture or expansion of runtime capabilities.

## Conclusion and Conditions

Evaluate the managed native AWS S3 path first, provided the company permits the service, proposed region, budget, and access path. This recommendation follows from existing native identity verification versus the missing production attestor for custom endpoints; it is not a price quote, resource selection, or Company acceptance.

If the company requires an internal deployment or can supply an existing Platform-operated S3-compatible service, evaluate that service's specific product and version. Resolve identity attestation adaptation before proving the full archive/independent recovery chain. With no resources and no assigned operations responsibility, building a storage cluster from scratch solely for these two Evidence files is not recommended. This engineering judgment uses the six-month part-time implementation scale and is subject to Owner review.

## Existing Implementation Constraints

Under [TDR-004](../../../docs/v0.2/tdr/TDR-004-s3-compatible-evidence-storage.md), [TDR-012](../../../docs/v0.2/tdr/TDR-012-evidence-archive-acceptance-operations.md), and the [runbook](../../../docs/m1/evidence-archive-runbook.md), both paths retain private access, encryption, versioning, actual COMPLIANCE protection and retain-until, repository-external identities, independent recovery, exact-version reads, and fail-closed behavior.

In [ArchiveConfiguration](../../../backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/ArchiveConfiguration.kt), a null endpoint uses AwsStsIdentityAttestor from the same credential chain; a custom endpoint requires exactly one approved ProviderIdentityAttestor, otherwise selecting MissingProviderIdentityAttestor. [S3Gateway](../../../backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/S3Gateway.kt) explicitly returns IDENTITY_UNAVAILABLE when the attestor is missing. No concrete custom-endpoint attestor implementation was found in current production code; S3 compatibility does not imply readiness after configuration.

## Comparison of the Two Paths

| Dimension | Managed native AWS S3 | Company-operated S3-compatible storage |
|---|---|---|
| Current resources | None; company account/region/network and owner unspecified | None; product/version/cluster and owner unspecified |
| Code adaptation | AWS S3 client and STS identity path exist; actual configuration still needs verification | S3 endpoint interface exists; requires an approved identity attestor and associated tests/technical decision |
| Immutability | Official Object Lock capabilities support candidacy; existing actual probes remain required | Specific product/version capabilities, policies, and actual probes need verification; a bucket switch is insufficient |
| Independent identities | Company establishes two controlled identities using existing Provider attestation | Platform supplies verifiable identity origin and binding to S3 operations; configuration self-claims are rejected |
| Operations responsibility | Cloud operates storage; company still owns permissions, configuration, costs, audit, and recovery verification | Company owns capacity, incidents, patching/upgrades, monitoring, backup/recovery, and identity service maintenance |
| Costs | Storage, requests, transfer/recovery, and selected management services, based on region and usage | Equipment/hosting, redundancy capacity, network, power, support, and operational labor, plus attestor adaptation |
| Initial work | Establish company boundaries and ownership, configure target/two identities, run the existing chain after authorization | Fix product/version/operator, design adaptation and verify compatibility, then run the existing chain |
| Main uncertainties | Company service permission, region/data residency, budget, and access path unspecified | All resources and the product unspecified; compatibility and sustainable operations unproven |
| Suitable situation | Company permits managed services and wants less infrastructure construction | Company requires internal hosting and has a responsible platform team or qualified existing service |

## Official Sources and Evidence Boundaries

AWS documents version-specific Object Lock with versioning and COMPLIANCE retention that cannot be shortened. New versions or delete markers do not mean a protected version was deleted, so the project's exact versionId recovery remains necessary. These are documented product facts, not this project's measured PASS. [AWS Object Lock](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html)

AWS GetCallerIdentity returns identity information for the calling credentials, matching the existing native path; it does not replace application authorization or independent-identity requirements. [AWS STS API](https://docs.aws.amazon.com/STS/latest/APIReference/API_GetCallerIdentity.html)

As protocol references for self-managed storage, Ceph documents GetCallerIdentity and object retention APIs. The checked latest pages explicitly describe a development version; they do not prove any production version meets all project requirements or that this project already has a Ceph attestor. This document selects neither Ceph, MinIO, nor another specific product. [Ceph STS](https://docs.ceph.com/en/latest/radosgw/STS/), [Ceph Object Operations](https://docs.ceph.com/en/latest/radosgw/s3/objectops/)

## Cost Comparison Method

Region, daily growth, retention, object counts, recovery frequency, and company quotes are missing, so neither a credible monthly total nor a cheaper-path claim is possible. Original ZIPs are only 1756 / 1753 bytes of summary Evidence; these sizes cannot predict future log, trace, or dump capacity.

- Managed path: actual retained-version storage usage multiplied by regional/class rates, plus writes, reads, HEAD/control checks, recovery traffic, selected encryption key/audit/monitoring services, and support. Quote only selected items; do not assume every add-on is enabled.
- Self-managed path: equipment or hosting amortization, redundancy and reserve capacity, network, power, support, and labor, plus initial deployment and identity adaptation. Existing clusters also incur incremental operational costs.
- Daily growth and retention estimate steady storage only; additionally account for receipts, daily control objects, retained historical versions, and necessary recovery copies. Content addressing does not imply elimination of all version growth.
- The current chain requires exact versions to be readable; no cold-archive restore/wait flow was found in production code. Do not estimate only the cheapest cold-storage rate and assume the chain can run.

AWS's official price page separates storage, request/retrieval, transfer, and optional management charges. A real quote must fix region, currency, date, and configuration; no amount lacking those conditions is quoted here. [AWS S3 Pricing](https://aws.amazon.com/s3/pricing/)

## Review Inputs and Exit Conditions

| Input | Responsible role | Purpose |
|---|---|---|
| Whether managed AWS is allowed, approved regions/data residency, and network boundaries | Project Owner / Security | Exclude disallowed paths without implementation-owner assumptions |
| Daily growth, object counts, retention, recovery frequency, and budget ceiling | Project Owner / Release Engineer | Compare costs under equivalent retention and recovery requirements |
| Team able to operate the service; any reusable object storage | Platform | Determine feasibility of the self-managed path |
| Specific service or product version, identity source, and controlled configuration locator | Platform / Security | Establish a verifiable technical candidate |

After Owner direction review, the implementer creates a TDR against the actual candidate covering HOW, adaptation scope, tests, and rollback. Key technical choices do not enter implementation before recording. Actual probes, resource provisioning, and Company archiving still require their corresponding authorization. Actual prerequisites remain governed by the existing table in the [preparation package](README.md); this document creates no second admission authority.

## Next Execution Plan

Current result: two resource paths compared against documentation and code, with a conditional recommendation and no assigned resource. Git state: this paired research-document commit leaves original implementation and acceptance Subjects unchanged. Next action: Owner reviews whether managed AWS S3 may be the preferred technical candidate or internal self-management is required. Prerequisite: usage boundaries and the responsible team; missing budget/capacity inputs remain explicit. Acceptance target: establish resource direction and constraints, then prepare a TDR against the actual candidate; this does not approve procurement, provisioning, Company enablement, merge, Tag, release, deployment, or a new milestone.
