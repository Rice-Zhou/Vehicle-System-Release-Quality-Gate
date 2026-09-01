---
acceptanceId: M2-2-MAPPING-AUTHORITY-001
subject: M2.2 Mapping Profile 与 Adapter Version Authority 实施候选
subjectCommit: 72d85267573d845945070de898c5dc865caa7b98
pairedSubjectCommit: 25f1bc0a08b3170782bff3ab4a3154ff5463cc27
branch: docs/m2-issue-traceability-design
status: APPROVE
submittedAt: 2026-09-01T08:24:06Z
owner: Project Owner
decisionAt: 2026-09-01T09:05:11Z
---

# M2.2 Mapping Profile 与 Adapter Version Authority 验收记录

## Scope

**Included**

- 中文 Subject Commit `72d85267573d845945070de898c5dc865caa7b98` 与英文配对 Subject Commit `25f1bc0a08b3170782bff3ab4a3154ff5463cc27` 固定的 M2.2 实施候选。
- 版本化、Project/Issue Source scoped、INSERT-only 的 Mapping Profile Authority，V5 forward-only Migration，以及 RFC 8785 JCS/SHA-256 Mapping Version。
- 受鉴权 Profile 激活事务、Idempotency、Audit、Outbox、Adapter Descriptor 单一版本权威，以及 Sync Run 的 Adapter/Mapping Version 固定。
- Runtime fail-closed 五类固定诊断、Process Runner 零调用、Profile A/B 激活与 Sync 竞态、Fixture synthetic-only、安全输出和通用 500 日志脱敏门禁。
- Contract、Acceptance、Governance、Pair Gate 与中英文 GitHub Actions/PostgreSQL Artifact 证据。

**Excluded**

- 真实 Jira 调用、真实工作流 Token 入库、扩大查询范围或任何 Jira 写操作；后续真实复测仍需独立授权，限单项目、最多 20 条、只读并使用受控 Profile。
- Company 环境、Company Evidence Archive、生产部署、合并 `main`/`release`、Tag 或 release。
- 对 V0.1 Core Contract、Release-centric architecture、Manifest authority、Evidence、Traceability、Deterministic Quality Engine、Adapter、Plugin 或 ADR 治理的修改。

## Evidence

### Immutable implementation chain

- **Type**：Git commit chain；**Locator**：中文分支八个 Task 主提交：[`04266673c52e650ba75a0de6780f3097a28edb8f`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/04266673c52e650ba75a0de6780f3097a28edb8f)、[`ec8a98ed601f6c60a93ed6fc166f4764151d3aec`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/ec8a98ed601f6c60a93ed6fc166f4764151d3aec)、[`375a3f5a7ae9e71864734acce521482be79683ad`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/375a3f5a7ae9e71864734acce521482be79683ad)、[`1bb7dcce675a237c10f4a30beda4759e87494b61`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/1bb7dcce675a237c10f4a30beda4759e87494b61)、[`bc0658d01f64b3d05d50cf141036ce00454108d0`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/bc0658d01f64b3d05d50cf141036ce00454108d0)、[`4aa2a7b57924a4ea3111de351da39e1bfd2eed6c`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/4aa2a7b57924a4ea3111de351da39e1bfd2eed6c)、[`2b85d87096ac2b697ede5bb45c3de454ca35c90f`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/2b85d87096ac2b697ede5bb45c3de454ca35c90f)、[`a5b40407f70cdfb548900fcb1a5a740417421c18`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/a5b40407f70cdfb548900fcb1a5a740417421c18)；**Generated At**：最终 Subject Commit `2026-09-01T08:10:00Z`；**Subject Commit**：`72d85267573d845945070de898c5dc865caa7b98`；**Digest / Summary**：八个顺序 Task 的不可变主实现链；**Availability**：GitHub commit locator；**Owner Authorization**：Project Owner（receipt locators：`ca48f06ea7afc42811bc0730a0f3365cf00dbfb1` / `0ed4067d89f2977335f66e8daa6c533b72b1c38b`）。
- **Type**：Git hardening chain；**Locator**：中文 follow-up commits：`8bf60a6abb4833a3343216655f5c9d7da275ba79`、`04677cc215e043797fe45e4d3f29132c400418ed`、`0c2a9d949dcaaae5d24aa165ccd8b2d15971c04b`、`62fac0b61c28cd5b262eb92f3fcb5bc56adfa270`、`a41c3e99f42bf6a10de2190f4805ee012c5d2732`、`2bb9ac7674512ffb3743131c9ec1d400ec6b0bd2`、`40a065953c79d71c33ec630e2c5b6fdcce90d2dd`、`163bfebd58e9a2e077117a8a0f64dced57665081`、`edaa4c9c40c3d99128d62d26b6a07d3a91513216`、`89b01718be6a355c7116700632fa339140405976`、`64223129ba9dc43bd27a450644d76ea756fabda9`、`923e463337b99e50aa662ae3facf52a3b2f70566`、`5a6bcf2afdf8183e2ea10e3a1cbf0917ea2483be`、`2e97ae691de1f36182e24d10c0543ce407dbbc4f`、`5efedac2b84f074ad622ac9e9f9c6819886b52f5`、`72d85267573d845945070de898c5dc865caa7b98`；**Generated At**：`2026-09-01T08:10:00Z`；**Subject Commit**：`72d85267573d845945070de898c5dc865caa7b98`；**Digest / Summary**：Migration、immutability、validation boundary、runtime、race 与日志安全 hardening 不可变链；**Availability**：Git history；**Owner Authorization**：Project Owner（receipt locators：`ca48f06ea7afc42811bc0730a0f3365cf00dbfb1` / `0ed4067d89f2977335f66e8daa6c533b72b1c38b`）。
- **Type**：Paired Git commit chain；**Locator**：英文八个 Task 主提交 `1916e4267ecb2189c33bcd80fa25f0267cc16015`、`3cf8f8d07c21cbaa02a69d4601adf27e13fd8d2a`、`c9024d4c6003de4388696cb73ed47195be43c70c`、`43fa70776e80abd6f5446e3d24ade12317e98645`、`418170b463a4258411decc48b8c2d6c6101a1371`、`8e81e46c0ade1910a08dd9b0084a6600ef3a4f4f`、`39b8b4491d8cc9a4c761d31143627d2a434b97eb`、`12e79fcefd2e4b4df319132f7e5b63dba5fc355f`；英文 follow-up commits `3f1d15cd6509f4a92418537fb9065fb432d76cbb`、`085697ede97838e10fa8ed6e7c81aed12deccda3`、`4714896787935f221c2f5eba44cf125736b5e250`、`a55a41bc293fa2658590dd4a36fa992cc578d1b3`、`2ee37d90ebf716937e31e5d78b809a7f31224591`、`3196d600e320a61288db3f4caba1460323041f5c`、`535e4fd95944063d1fdf2fd502d3ffb56e4ac14b`、`95ff78496913e8962010ec96634e066d63503eeb`、`dc258c7c5bd752614d7a413844c57123ef6468ea`、`875dd599752d85afc1e365171f0df6cbc4f139ac`、`83a105bebe8573ec4b6ffa4c88d15ca294aabc24`、`0e828daf65f8db55250d8d24c69b224198ec15db`、`46ee49bffa3e8dbbdfdbb34a4031aa1770adbead`、`1bd01d74b9bdef7b92e2d7fa67abe156359970c4`、`76d8acd422d1ba991a7149eca442c4441c59e768`、`25f1bc0a08b3170782bff3ab4a3154ff5463cc27`；**Generated At**：最终 Paired Subject Commit `2026-09-01T08:14:50Z`；**Subject Commit**：`25f1bc0a08b3170782bff3ab4a3154ff5463cc27`；**Digest / Summary**：与中文链语义配对；**Availability**：Git history；**Owner Authorization**：Project Owner（receipt locators：`ca48f06ea7afc42811bc0730a0f3365cf00dbfb1` / `0ed4067d89f2977335f66e8daa6c533b72b1c38b`）。
- **Type**：Paired head evidence cross-check；**Locator**：英文 Contract head `25f1bc0a08b3170782bff3ab4a3154ff5463cc27`、Runtime head `25f1bc0a08b3170782bff3ab4a3154ff5463cc27`、Security head `25f1bc0a08b3170782bff3ab4a3154ff5463cc27`；**Generated At**：`2026-09-01T08:14:50Z`；**Subject Commit**：双语固定 implementation heads；**Digest / Summary**：三类本地摘要均交叉绑定同一英文配对 head；**Availability**：Git history；**Owner Authorization**：Project Owner（receipt locators：`ca48f06ea7afc42811bc0730a0f3365cf00dbfb1` / `0ed4067d89f2977335f66e8daa6c533b72b1c38b`）。

### Verification and CI evidence

- **Type**：Contract/Acceptance/Governance/local test summary；**Locator**：`scripts/contract-validator.mjs`、acceptance validators、`scripts/verify-design-governance.ps1` 与 Backend test reports；**Generated At**：`2026-09-01T08:24:06Z`；**Subject Commit**：`72d85267573d845945070de898c5dc865caa7b98`；**Digest / Summary**：Contract `operations=33`，Acceptance `37/37`，records PASS，Governance `tdr=15`，11 个相关类共 `89` tests、0 failures/errors/skips；**Availability**：本地输出与 CI Artifact；**Owner Authorization**：Project Owner（receipt locators：`ca48f06ea7afc42811bc0730a0f3365cf00dbfb1` / `0ed4067d89f2977335f66e8daa6c533b72b1c38b`）。
- **Type**：Database/runtime/security report；**Locator**：`backend/src/main/resources/db/migration/V5__issue_mapping_profile.sql`、`IssueSourceRuntimeRegistryTest`、`IssueMappingProfileActivationIntegrationTest`、`IssueMappingSecurityTest`；**Generated At**：`2026-09-01T08:24:06Z`；**Subject Commit**：`72d85267573d845945070de898c5dc865caa7b98`；**Digest / Summary**：V5 Migration PASS；五类 `MAPPING_PROFILE_NOT_CONFIGURED`、`MAPPING_PROFILE_INTEGRITY_FAILED`、`MAPPING_SCHEMA_UNSUPPORTED`、`ADAPTER_VERSION_MISMATCH`、`MAPPING_VERSION_MISMATCH` 均在 Process Runner 调用前 fail-closed 且 calls=0；Profile A/B race、sync version pin、rollback/wait tests PASS；敏感生产扫描 `matches=0`；**Availability**：Git/CI；**Owner Authorization**：Project Owner（receipt locators：`ca48f06ea7afc42811bc0730a0f3365cf00dbfb1` / `0ed4067d89f2977335f66e8daa6c533b72b1c38b`）。
- **Type**：Pair Gate；**Locator**：`scripts/verify-language-branches.ps1 -Mode Pair`，中文 `72d85267573d845945070de898c5dc865caa7b98`，英文 `25f1bc0a08b3170782bff3ab4a3154ff5463cc27`；**Generated At**：started `2026-09-01T08:22:27Z`，completed `2026-09-01T08:23:15Z`；**Subject Commit**：双语固定 implementation heads；**Digest / Summary**：PASS，所有非 Markdown 文件字节一致；**Availability**：本地 Pair Gate 输出；**Owner Authorization**：Project Owner（receipt locators：`ca48f06ea7afc42811bc0730a0f3365cf00dbfb1` / `0ed4067d89f2977335f66e8daa6c533b72b1c38b`）。
- **Type**：中文 GitHub Actions CI Run；**Locator**：[Run `33486146835`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33486146835)；**Generated At**：`2026-09-01T08:15:56Z`；**Subject Commit**：`72d85267573d845945070de898c5dc865caa7b98`；**Digest / Summary**：conclusion `success`，包含 PostgreSQL/Testcontainers Gate；**Availability**：GitHub Run，retention `UNKNOWN`；**Owner Authorization**：Project Owner（receipt locators：`ca48f06ea7afc42811bc0730a0f3365cf00dbfb1` / `0ed4067d89f2977335f66e8daa6c533b72b1c38b`）。
- **Type**：中文 CI Artifact；**Locator**：`m1-evidence-72d85267573d845945070de898c5dc865caa7b98`，[Artifact ID `9791943247`](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/9791943247)；**Generated At**：`2026-09-01T08:19:40Z`；**Subject Commit**：`72d85267573d845945070de898c5dc865caa7b98`；**Digest / Summary**：`106076 bytes`（约 `104 KB`），`sha256:aa532452022df8fce088e5bbb55ef7add28698025f21f633ce129fadf2cf20f8`；**Availability**：workflow `retention-days=30`，`expired=false`，`expires_at=2026-10-01T08:19:39Z`；**Owner Authorization**：Project Owner（receipt locators：`ca48f06ea7afc42811bc0730a0f3365cf00dbfb1` / `0ed4067d89f2977335f66e8daa6c533b72b1c38b`）。
- **Type**：英文 GitHub Actions CI Run；**Locator**：[Run `33486146293`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/33486146293)；**Generated At**：`2026-09-01T08:15:55Z`；**Subject Commit**：`25f1bc0a08b3170782bff3ab4a3154ff5463cc27`；**Digest / Summary**：conclusion `success`，包含 PostgreSQL/Testcontainers Gate；**Availability**：GitHub Run，retention `UNKNOWN`；**Owner Authorization**：Project Owner（receipt locators：`ca48f06ea7afc42811bc0730a0f3365cf00dbfb1` / `0ed4067d89f2977335f66e8daa6c533b72b1c38b`）。
- **Type**：英文 CI Artifact；**Locator**：`m1-evidence-25f1bc0a08b3170782bff3ab4a3154ff5463cc27`，[Artifact ID `9791978227`](https://api.github.com/repos/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/artifacts/9791978227)；**Generated At**：`2026-09-01T08:20:45Z`；**Subject Commit**：`25f1bc0a08b3170782bff3ab4a3154ff5463cc27`；**Digest / Summary**：`106291 bytes`（约 `104 KB`），`sha256:0daa774df42e3cdcf0e390ab15afb5b2dc41e815ba0161b1a46358856814e1ad`；**Availability**：workflow `retention-days=30`，`expired=false`，`expires_at=2026-10-01T08:20:44Z`；**Owner Authorization**：Project Owner（receipt locators：`ca48f06ea7afc42811bc0730a0f3365cf00dbfb1` / `0ed4067d89f2977335f66e8daa6c533b72b1c38b`）。
- **Type**：Owner approval instruction receipt；**Locator**：中文 receipt commit [`ca48f06ea7afc42811bc0730a0f3365cf00dbfb1`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/ca48f06ea7afc42811bc0730a0f3365cf00dbfb1) 与英文配对 receipt commit [`0ed4067d89f2977335f66e8daa6c533b72b1c38b`](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/0ed4067d89f2977335f66e8daa6c533b72b1c38b)；**Generated At**：`2026-09-01T09:03:25Z`；**Subject Commit**：72d85267573d845945070de898c5dc865caa7b98；**Digest / Summary**：当前任务收到 Project Owner 精确原始指令 `APPROVE M2-2-MAPPING-AUTHORITY-001`，并由双语独立 receipt commits 不可变保存；**Availability**：Git commits；**Owner Authorization**：Project Owner。

## Acceptance Checks

| Check | Result | Evidence | Notes |
|---|---|---|---|
| 八个 Task 与全部 hardening 提交固定 | `PASS` | 双语不可变 Git chain | Subject Commit 与 Paired Subject Commit 固定最终实现 heads |
| Contract 与 V5 Authority Migration | `PASS` | operations=33、双 CI PostgreSQL Gate | 未改变 V0.1 Core Contract；V5 为 forward-only |
| 五类 Runtime fail-closed 与 Process 零调用 | `PASS` | `IssueSourceRuntimeRegistryTest` | Authority 失败不会启动 Jira Process |
| Profile A/B 与 Sync 事务竞态 | `PASS` | activation/sync race、rollback、wait tests | Sync Run 固定已提交版本，不混读新 Profile |
| Fixture 与安全边界 | `PASS` | synthetic-only、安全聚合测试、scan matches=0 | Definition、Issue 内容、URL、路径、stdout/stderr、credential 不进入治理输出或日志 |
| 本地可执行 Gate | `PASS` | 89 tests、Acceptance 37/37、Governance tdr=15 | 本机无 Docker；数据库完整 Gate 由双 CI 固定 |
| 双语候选一致性 | `PASS` | Pair Gate `2026-09-01T08:22:27Z`–`08:23:15Z` | 非 Markdown 文件字节一致 |
| 双分支 CI 与 Artifact | `PASS` | Runs `33486146835`/`33486146293` 与 Artifact digests | 两条 PostgreSQL Gate success；Artifact retention 为 30 天，精确到期时间为 `2026-10-01T08:19:39Z` 与 `2026-10-01T08:20:44Z`；仅 CI Run/log retention 为 `UNKNOWN` |
| 真实 Jira 受控复测 | `UNKNOWN` | Scope exclusion | 本候选未调用真实 Jira，仍需独立 Owner 授权 |
| Owner 决定 | `PASS` | 双语 receipt commits `ca48f06ea7afc42811bc0730a0f3365cf00dbfb1` / `0ed4067d89f2977335f66e8daa6c533b72b1c38b` | Owner 已批准固定实施候选并接受现有残余风险 |

## Residual Risks

| Risk | Impact | Owner | Mitigation / Review Condition |
|---|---|---|---|
| 本候选未调用真实 Jira | Fixture、事务和 CI 不证明当前 Jira 身份/网络/受控 Profile 的实际可用性 | Project Owner / Implementation Owner | 后续另行授权单项目、最多 20 条、只读复测，并使用受控 Profile；不得沿用本记录替代真实证据 |
| Company、部署与发布范围未执行 | 不能宣称 Company Ready 或 Production Ready | Project Owner / Operator | 保持 Company、merge、Tag、release、deploy 阻断，条件具备后独立验收 |
| 通用 500 日志不保存 stack | 避免敏感 Throwable 泄漏，但仅凭日志不能定位具体代码行 | Implementation Owner | 使用 requestId、固定 code 与 exception type 关联；未来仅通过独立设计的安全错误指纹或受控遥测增强 |
| Artifact 将在已知日期过期 | 中文 Artifact 于 `2026-10-01T08:19:39Z`、英文 Artifact 于 `2026-10-01T08:20:44Z` 过期，届时在线证据不可访问 | Project Owner / Release Engineer | 在到期前按 Evidence Archive 流程固定，并保留不可变 digest 与 locator |
| 本机无 Docker | 本地无法复跑 PostgreSQL/Testcontainers 完整集 | Implementation Owner | 本地 89 个非容器测试通过；两个固定 Subject CI 的 PostgreSQL Gate 已成功 |

## Decision Reason

Project Owner 通过指令 `APPROVE M2-2-MAPPING-AUTHORITY-001` 批准由 Subject Commit `72d85267573d845945070de898c5dc865caa7b98` 与 Paired Subject Commit `25f1bc0a08b3170782bff3ab4a3154ff5463cc27` 固定的 Mapping Profile 与 Adapter Version Authority 实施候选，并接受本记录所列残余风险。

本决定接受真实 Jira 尚未调用、Company/部署/发布范围未执行、通用 500 日志不保留 stack、Artifact 在已知日期到期，以及本机无 Docker 但双侧 CI PostgreSQL Gate 已通过等现状。本批准不扩展至真实 Jira 调用、Company 环境、`main`/`release` 合并、Tag、release 或 deploy；这些行为仍须各自独立授权和验收。

Correction（`2026-09-01T09:19:14Z`，Project Owner）：将全部 Evidence 的 Owner Authorization 绑定到双语 receipt commits `ca48f06ea7afc42811bc0730a0f3365cf00dbfb1` / `0ed4067d89f2977335f66e8daa6c533b72b1c38b`，并同步澄清英文记录中的非事实性措辞；本 correction 不改变任何证据事实、Subject Commits、批准范围或残余风险。

Correction（`2026-09-01T12:52:16Z`，Project Owner）：登记稳定授权标识 `M2-2-MAPPING-AUTHORITY-PILOT-PRESERVATION-001`。Project Owner 在当前任务要求执行已批准的下一步，授权范围仅限两个固定 M2.2 Artifact 的 Pilot temporary preservation、local recovery rehearsal 与治理登记；不授权 Company Provider 外部写入、真实 Jira、merge、tag、release 或 deploy。本提交形成 follow-up authorization receipt，下一独立提交将绑定其双语精确 SHA；本 correction 不改变 metadata `APPROVE` 状态、Owner、decisionAt、Subject Commits、原批准范围或残余风险解释。

## Follow-up Actions

| Action | Owner | Due / Trigger | Closure Condition | Completion Evidence |
|---|---|---|---|---|
| 在两个 Artifact 到期前完成 Evidence Archive | Release Engineer / Project Owner | 分别在 `2026-10-01T08:19:39Z` 与 `2026-10-01T08:20:44Z` 前 | Artifact `9791943247` 与 `9791978227` 均按 Evidence Archive 流程固定，且 archive/recovery 校验通过 | Evidence Archive 验收记录，包含 immutable digest、exact locator、archive/recovery report 与完成 marker |
| 如需真实 Jira 复测则取得独立授权 | Project Owner | Owner 决定后且复测前 | 明确单项目、最多 20 条、只读、脱敏输出和受控 Profile | 独立 Owner 指令与新的 Smoke Evidence/验收记录 |
| 保持 Company、合并与发布阻断 | Project Owner / Release Engineer | 相应独立授权前 | 不启用 Company，不 merge、Tag、release 或 production deploy | Git、部署与发布审计记录 |

## Decision History

| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| 2026-09-01T08:24:06Z | PENDING | PENDING | 固定双语 Mapping Profile 与 Adapter Version Authority 实施候选、Pair Gate、双 CI 和安全证据已提交 Owner 复核 | PENDING |
| 2026-09-01T09:03:25Z | PENDING | PENDING | 收到 Project Owner 原始 APPROVE 指令并形成不可变 receipt，等待下一独立提交应用 | d79ac11e402894d8ca818427effbfcfab8565f58 |
| 2026-09-01T09:05:11Z | APPROVE | Project Owner | 批准固定双语 Mapping Profile 与 Adapter Version Authority 实施候选并接受现有残余风险，不扩展被排除范围 | ca48f06ea7afc42811bc0730a0f3365cf00dbfb1 |
| 2026-09-01T09:19:14Z | APPROVE | Project Owner | Correction：将全部 Evidence Owner Authorization 绑定到双语 receipt commits，并澄清英文非事实性措辞；不改变证据事实或批准范围 | 68a18988ff1be6eae08ee95abf15d1b22b1adc9a |
| 2026-09-01T12:52:16Z | APPROVE | Project Owner | Correction：以 `M2-2-MAPPING-AUTHORITY-PILOT-PRESERVATION-001` 登记仅限 Pilot temporary preservation、local recovery rehearsal 与治理登记的 follow-up 授权；不授权 Company Provider 外部写入、真实 Jira、merge、tag、release 或 deploy | 8cee1b52a3bc0f0c6aa95e68c3b43983d250b1ff |
