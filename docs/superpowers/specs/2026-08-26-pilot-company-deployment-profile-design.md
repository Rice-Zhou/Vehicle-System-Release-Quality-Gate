# Pilot / Company 双模式配置设计

## 1. 目标

为 V0.2 定义可落地的部署成熟度配置：课题阶段无需提前获得公司 S3、OIDC 或运维资源也能继续开发和验证；进入公司环境后，同一套实现必须对缺失的安全、保留和不可变能力 fail closed。

本设计接受“目标能力开关默认启用”，但禁止把配置意图当作外部事实。布尔策略默认 `true` 只表示系统要求该控制，不证明归档已经完成、Object Lock 已生效或公司资源已经提供。

## 2. 架构与治理边界

- 不修改 V0.1 Core Contract、Release-centric architecture、Manifest authority、Evidence、Traceability、Quality Engine、Adapter、Plugin 或 ADR 治理。
- 保留 `TDR-004` 的 S3-compatible Evidence Payload Storage 决定和 `TDR-010` 的公司部署方向。
- `LOCAL_PILOT` 只表示本地 staging 和演示能力，不是长期 Evidence archive provider，也不能产生归档 `PASS`。
- Profile 影响部署就绪度、归档操作和验收证据解释，不改变确定性 Quality Result 的业务语义。
- Owner 决定、合并、Tag 和 release 仍需各自独立授权。

## 3. 方案选择

### 3.1 采用：双 Profile + 真实能力状态

使用 `PILOT` 和 `COMPANY` 两个部署 Profile。所有目标控制类布尔开关默认 `true`；Provider 和实际验证状态使用枚举，不能用布尔值代替。`PILOT` 允许缺少外部资源时运行，但不得伪造成功；`COMPANY` 对未满足的必需控制 fail closed。

该方案兼顾六个月课题落地和公司级边界，且不需要维护两套业务实现。

### 3.2 未采用：所有外部状态默认 true

如果把“已归档”“已加密”“已启用 Object Lock”等实际状态直接默认 `true`，系统将生成无法由 Evidence 复核的成功结论，违反 Evidence-first、可审计性和确定性要求。

### 3.3 未采用：课题阶段全部默认 false

全部关闭会让开发路径与未来公司路径偏离，并容易形成上线前才发现的集成缺口。目标控制应从第一天启用，只把尚未具备的外部能力显式标为未验证。

## 4. 配置契约

输入配置使用单一命名空间，配置文件不得包含凭据：

| 配置项 | 类型 | 默认值 | 规则 |
|---|---|---|---|
| `vsrqg.deployment.mode` | enum | `PILOT` | 仅允许 `PILOT`、`COMPANY` |
| `vsrqg.evidence.archive.enabled` | boolean | `true` | 启用归档工作流，不等于归档完成 |
| `vsrqg.evidence.archive.checksum-verification-enabled` | boolean | `true` | 上传前后均复算 SHA-256 |
| `vsrqg.evidence.archive.encryption-required` | boolean | `true` | 要求目标存储启用静态和传输加密 |
| `vsrqg.evidence.archive.private-access-required` | boolean | `true` | 禁止匿名和公开读取 |
| `vsrqg.evidence.archive.retention-policy-required` | boolean | `true` | 要求记录明确的 retention policy |
| `vsrqg.evidence.archive.immutability-required` | boolean | `true` | 要求 Object Lock/WORM 或经批准的等价控制 |
| `vsrqg.evidence.archive.provider` | enum | `NONE` | 仅允许 `NONE`、`FILESYSTEM_STAGING`、`S3_COMPATIBLE` |
| `vsrqg.evidence.archive.staging-root` | string | 空 | `FILESYSTEM_STAGING` 时必填，且必须是显式绝对路径 |
| `vsrqg.evidence.archive.endpoint` | string | 空 | `S3_COMPATIBLE` 时按 Profile 校验 |
| `vsrqg.evidence.archive.region` | string | 空 | Provider 要求时必填 |
| `vsrqg.evidence.archive.bucket` | string | 空 | `S3_COMPATIBLE` 时必填 |
| `vsrqg.evidence.archive.object-prefix` | string | `acceptance/` | 只允许规范化相对 object key prefix |
| `vsrqg.evidence.archive.access-owner` | string | 空 | 公司归档时必填责任角色 |
| `vsrqg.evidence.archive.retention-period` | duration | 空 | 公司归档时必填且必须为正值 |

凭据只允许由环境变量、Secret Manager、Workload Identity 或 credential profile 提供。配置响应、日志、Audit 和 Git 中不得出现 secret value。

## 5. 派生状态

系统根据配置和主动验证产生只读 Capability Report，不允许调用者直接填写结果：

| 状态 | 判定 |
|---|---|
| `UNCONFIGURED` | Provider 为 `NONE`，或必需连接参数不存在 |
| `LOCAL_PILOT` | Provider 为 `FILESYSTEM_STAGING`，且 staging 路径可写、SHA-256 可复算 |
| `EXTERNAL_UNVERIFIED` | 已配置 `S3_COMPATIBLE`，但连接、权限或控制验证未全部通过 |
| `EXTERNAL_VERIFIED` | Endpoint、Bucket、写入、回读、摘要、私有访问、保留和不可变控制均通过验证 |

Capability Report 至少包含 Profile、Provider、状态、检查时间、逐项检查结果和不含 secret 的失败原因。它是部署证据，不是 Core Evidence Entity 或 Quality Result。

## 6. 行为矩阵

| Profile / 状态 | 系统运行 | 归档行为 | 验收解释 |
|---|---|---|---|
| `PILOT` / `UNCONFIGURED` | 允许 | 明确返回不可用 | Evidence retention 为 `UNKNOWN` |
| `PILOT` / `LOCAL_PILOT` | 允许 | 可 staging、生成 manifest、复算摘要 | 不得作为长期归档 `PASS` |
| `PILOT` / `EXTERNAL_UNVERIFIED` | 允许并报告 degraded | 拒绝声明归档完成 | Evidence retention 为 `UNKNOWN` |
| `PILOT` / `EXTERNAL_VERIFIED` | 允许 | 可生成可复核 archive receipt | 有完整 Evidence 时才可 `PASS` |
| `COMPANY` / 非 `EXTERNAL_VERIFIED` | 管理接口可启动，readiness 为 NOT_READY | 归档和依赖归档的批准路径 fail closed | 不得进入公司发布 Gate |
| `COMPANY` / `EXTERNAL_VERIFIED` | READY | 可归档并回读验证 | 按真实 receipt 判定 |

`COMPANY` 不通过静默 fallback 降级到 `LOCAL_PILOT`。Profile 切换后必须重新验证 Capability，不复用旧状态。

## 7. 数据流与归档收据

```text
Configuration
    -> Configuration Validator
    -> Provider Probe
    -> Capability Report
    -> Archive Command
    -> Upload
    -> Read-back SHA-256 Verification
    -> Archive Receipt
    -> Acceptance Record Reference
```

Archive Receipt 必须记录 acceptance ID、source Artifact ID/Run/commit、source digest、destination locator、destination digest、size、access owner、retention policy、immutability control、archivedAt 和 verifier。失败流程不产生成功 receipt。

## 8. 错误处理

- 配置格式错误：启动配置校验失败，输出具体 property 和原因。
- Provider 不可达或无权限：状态为 `EXTERNAL_UNVERIFIED`，保留真实错误，不切换 Provider。
- 上传失败：不生成 receipt，不删除 staging 源文件。
- 回读摘要不一致：归档失败并保留 expected/actual digest；禁止改写 expected value。
- 保留或不可变控制无法证明：即使文件可上传，也不得进入 `EXTERNAL_VERIFIED`。
- Capability Report 过期或 Profile 改变：执行归档前重新验证。

## 9. 安全约束

- 配置对象和诊断输出对 credential、token、presigned URL 做拒绝输出或脱敏。
- Object prefix 禁止绝对路径、`..` 和未规范化分隔符，防止越界写入。
- `FILESYSTEM_STAGING` 只能使用显式 staging 根目录，不能声明 WORM 或公司 retention。
- S3 Bucket 禁止 public access，权限限制到指定 prefix；生产优先使用短期身份。
- Archive Receipt 只记录稳定 locator 和 digest，不记录 secret 或临时 Bearer URL。

## 10. 测试策略

至少覆盖：

1. 默认配置加载为 `PILOT`，所有目标控制布尔值为 `true`，Provider 为 `NONE`。
2. `PILOT` + `NONE` 可启动但 Capability 为 `UNCONFIGURED`，归档不产生 receipt。
3. `FILESYSTEM_STAGING` 可写入和复算摘要，但不能产生长期归档 `PASS`。
4. `COMPANY` 缺 Endpoint、Bucket、owner、retention 或 immutability 时 readiness 为 NOT_READY。
5. S3 上传后必须回读并复算 SHA-256；摘要不一致时 fail closed。
6. 只有全部控制验证成功才产生 `EXTERNAL_VERIFIED`。
7. Profile 切换会使旧 Capability 失效。
8. 日志、错误和 receipt 不包含 credential、token 或 presigned URL。
9. 验收记录只能依据成功 Archive Receipt 将 Evidence retention 写为 `PASS`。

## 11. 迁移与回滚

初始默认使用 `PILOT` + `NONE`。课题阶段可选择 `FILESYSTEM_STAGING` 演示传输和摘要流程，但不改变验收事实。取得公司资源后配置 `S3_COMPATIBLE`，验证通过再切换 `COMPANY`。

回滚时只把 Profile 从 `COMPANY` 切回 `PILOT` 以恢复非生产研发，不删除外部对象、不覆盖 receipt，也不把已失败的公司检查改写为成功。Provider 迁移继续遵循 `TDR-004` 的 inventory、digest verification 和 source-preserving 规则。

## 12. 当前 M1 决定

本设计不自动修改 `M1-OWNER-GATE-001`。当前本地 ZIP 与 transfer manifest 仅属于 staging，现有 `CONDITIONAL` 决定保持有效。若 Owner 决定把公司不可变归档改为生产落地前置风险，必须通过新的明确 Owner 指令和验收记录状态转换完成，不能由 Profile 配置推导。

## 13. 实施与技术决策

后续实施计划必须先新增 `TDR-011`，记录 Profile、派生 enforcement、Capability Report、失败恢复、测试、部署和向公司环境迁移的技术决策。实现复用同一 Archive Port：`FILESYSTEM_STAGING` 和 `S3_COMPATIBLE` 只能是 Adapter，不得形成第二套验收或 Quality Engine。

## 14. 设计验收标准

- 课题默认配置不依赖公司资源即可运行。
- 所有目标控制布尔值默认 `true`。
- 实际外部能力永远来自验证结果，不能由布尔配置伪造。
- Pilot staging 与公司长期归档在状态和验收语义上明确分离。
- Company 模式缺少必需能力时 fail closed。
- 不改变 V0.1 冻结架构和 `TDR-004` 的长期存储方向。
- 中英文规范语义配对，所有非 Markdown 文件保持字节一致。
