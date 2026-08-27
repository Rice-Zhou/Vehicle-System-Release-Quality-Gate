# Evidence Archive 验收工作包运行手册

## 1. 目的与当前边界

本手册用于执行 `V0-2-EVIDENCE-ARCHIVE-001` 的 Company 长期归档、独立精确版本恢复和离线交叉校验。它复用冻结的 Evidence 一级实体和既有 `ArchiveEvidence.archive(ArchiveCommand)` facade，不改变 Release、Manifest、Traceability 或确定性 Quality Engine。

仓库内 `ops/evidence-archive/fixtures/offline-test/` 仅是标有 `TEST_FIXTURE` 的机械门禁数据：不访问 S3、不使用真实身份，不能证明 Company Provider、Object Lock、retention 或恢复已验收，也不能创建验收记录、关闭 `V0-2-PILOT-COMPANY-002` 或改变 `M1-OWNER-GATE-001`。真实 Company 操作必须在 Owner 明确授权外部写入后单独执行。

本流程不会执行 merge、Tag、release、production deployment 或对象删除。上述行为均需独立授权。

## 2. 角色与信任边界

| 阶段 | 角色 | 身份要求 | 输出 |
|---|---|---|---|
| 1 | Release Engineer | repository-external Company archive identity | `archive-report.json` |
| 2 | Independent Verifier | 与阶段 1 不同的 repository-external identity | `recovery-report.json` 和零字节 completion marker |
| 3 | Reviewer / CI | 不需要 Provider credential | 离线 `PASS`/失败码 |

repository-external identity 由 Provider attestation 获得，不能由 Git 配置或工作包自报。阶段 1 与阶段 2 的 `principalFingerprint` 必须不同；验收记录只保存 fingerprint，不保存 ARN、account、subject、user ID、session name、access key、secret 或 token。二次 identity 见证由 Independent Verifier 确认“使用了独立会话/工作负载身份、两份 fingerprint 不同”，并以受控审批 locator、见证责任人和见证时间留痕；见证不能替代 Provider attestation。

所有源目录、报告输出目录和恢复目录均由单一受信 Owner 预创建并限制为单写者。不得在共享、不受控或允许其他进程写入的目录执行。报告与 marker 是 create-only：目标存在即停止，禁止覆盖后把结果解释为同一次验收。

## 3. 公共准备

从仓库根目录启动 PowerShell。下列变量只存在当前受控会话；不要把本地路径或 credential 写入 Git、Manifest、命令日志附件或验收记录。

```powershell
$repoRoot = (Resolve-Path '.').Path
$workPackage = (Resolve-Path 'ops/evidence-archive/v0-2-evidence-archive-001.json').Path
$sourceRoot = (Resolve-Path $env:VSRQG_EVIDENCE_SOURCE_ROOT).Path
$reportRoot = (Resolve-Path $env:VSRQG_EVIDENCE_REPORT_ROOT).Path
$archiveReport = Join-Path $reportRoot 'archive-report.json'

if (Test-Path -LiteralPath $archiveReport) {
    throw 'archive-report.json already exists; use a new trusted output directory'
}
```

真实运行前按 [M1 运行与恢复手册](runbook.md) 的 Company Profile 配置注入 `VSRQG_EVIDENCE_ARCHIVE_*`，并确认 `COMPANY`、`S3_COMPATIBLE`、HTTPS/AWS native transport、versioning、private access、实际 `COMPLIANCE` Object Lock、正 retention 和 Provider-attested identity 全部可验证。credential 只能来自 Secret Manager、工作负载身份或等价的外部 identity chain。

Windows 的 `gradlew.bat` 会重新解释 `--args`。下列命令特意用 `\"` 保留 JavaExec 的路径分组边界；该形式已经通过 archive/verify 含空格绝对路径探针验证。不要改回普通内层双引号，否则 `archive`/`verify` 可能被 Gradle 误认为 Task。`-q` 避免打印命令参数，脚本与记录也不得回显本地路径或 credential。

`$sourceRoot` 必须包含固定工作包列出的两个 ZIP 和 `pilot-preservation-manifest.json`，且 size/SHA-256 与描述符一致。阶段 1 失败时不得修改描述符来迎合本地文件。

## 4. 阶段 1：Release Engineer 归档

```powershell
$archiveArgs = 'archive --work-package=\"' + $workPackage + '\" --source-root=\"' + $sourceRoot + '\" --output=\"' + $archiveReport + '\"'

./backend/gradlew.bat -q -p backend evidenceArchiveOperation "--args=$archiveArgs"
if ($LASTEXITCODE -ne 0) { throw "archive failed with exit code $LASTEXITCODE" }
```

成功输出必须是 `PASS` 且包含两个 Artifact。Release Engineer 随后只读保存：

- `executionId`、descriptor/manifest digest 与时间；
- archive identity fingerprint、`policyFingerprint`、`accessOwner`、retention 和 `COMPLIANCE`；
- 两个 payload 与两个 receipt 的 provider、bucket/key、locator、非空 `versionId`、SHA-256 和 size。

四个 exact object identity（provider、bucket、key、`versionId`）必须全局唯一。失败时保留两个源 ZIP、manifest、已提交 payload/receipt version 和 control object 供对账；只能清理由本次执行拥有的 partial，不删除或覆盖任何已提交版本。

## 5. 阶段 2：Independent Verifier 恢复

结束阶段 1 的身份会话。Independent Verifier 使用另一个经 Provider attestation 的受控身份，在新的 shell 中重新设置公共变量，并准备一个新的空恢复目录和新的受信报告目录。两个目录都必须是绝对规范路径、非 symlink、单写者控制；`recovery-report.json` 不得预先存在。

```powershell
$repoRoot = (Resolve-Path '.').Path
$workPackage = (Resolve-Path 'ops/evidence-archive/v0-2-evidence-archive-001.json').Path
$archiveReport = (Resolve-Path $env:VSRQG_ARCHIVE_REPORT).Path
$recoveryRoot = (Resolve-Path $env:VSRQG_RECOVERY_ROOT).Path
$recoveryReportRoot = (Resolve-Path $env:VSRQG_RECOVERY_REPORT_ROOT).Path
$recoveryReport = Join-Path $recoveryReportRoot 'recovery-report.json'

if ((Get-ChildItem -LiteralPath $recoveryRoot -Force | Measure-Object).Count -ne 0) {
    throw 'recovery root must be empty'
}
if (Test-Path -LiteralPath $recoveryReport) {
    throw 'recovery-report.json already exists; use a new trusted output directory'
}

$verifyArgs = 'verify --work-package=\"' + $workPackage + '\" --archive-report=\"' + $archiveReport + '\" --recovery-root=\"' + $recoveryRoot + '\" --output=\"' + $recoveryReport + '\"'

./backend/gradlew.bat -q -p backend evidenceArchiveOperation "--args=$verifyArgs"
if ($LASTEXITCODE -ne 0) { throw "recovery verification failed with exit code $LASTEXITCODE" }
```

Verifier 只按报告中的 exact `versionId` 回读 receipt/payload，再验证 digest、size、receipt 绑定、实际 protection 与 retain-until；禁止读取 latest。成功后恢复目录应清理为空，同时在报告同目录生成：

```text
recovery-report.json.complete.<sha256(raw recovery-report.json bytes)>
```

marker 必须是零字节普通文件。它是报告成功发布的最后一步，名称绑定原始报告字节；缺失、非零、symlink、摘要不匹配或文件名变化均使离线验收失败。

## 6. 阶段 3：离线交叉校验

离线校验不访问 Provider、不需要 S3 credential。Node CLI 只接受三条绝对规范路径；PowerShell 必须先通过 `Resolve-Path` 固定它们。`pnpm --silent run` 在 Windows 的参数转义由 CLI 严格解码后再次执行绝对规范路径检查，并避免包管理器回显包含本地路径的命令行。

```powershell
$offlineWorkPackage = (Resolve-Path 'ops/evidence-archive/v0-2-evidence-archive-001.json').Path
$offlineArchiveReport = (Resolve-Path $env:VSRQG_ARCHIVE_REPORT).Path
$offlineRecoveryReport = (Resolve-Path $env:VSRQG_RECOVERY_REPORT).Path

pnpm --silent run verify:evidence-archive -- `
    --work-package $offlineWorkPackage `
    --archive-report $offlineArchiveReport `
    --recovery-report $offlineRecoveryReport
if ($LASTEXITCODE -ne 0) { throw "offline verification failed with exit code $LASTEXITCODE" }
```

只有输出 `{"artifactCount":2,"result":"PASS","workPackageId":"V0-2-EVIDENCE-ARCHIVE-001"}` 才能进入人工复核。该 `PASS` 证明三份文件内部一致，但不自行认证执行人授权、Company 环境归属或 Git locator。

M1 的无 Provider fixture gate 使用相同命令和 `ops/evidence-archive/fixtures/offline-test/`。其中所有关键引用带 `TEST_FIXTURE`，只证明工具链可重放，不能复制到 Company 验收记录。

## 7. Evidence 入库与验收交接

在创建实际验收记录之前，先固定候选 commit，再把下列三个文件以原文件名复制到相同的仓库 Evidence 目录；目标已存在时停止，不得覆盖：

1. `archive-report.json`；
2. `recovery-report.json`；
3. 与第 2 项相邻的 `recovery-report.json.complete.<digest>` 零字节 marker。

marker 文件名中的 digest 必须等于仓库内 `recovery-report.json` 原始字节的 SHA-256。复制后复算三者 digest；marker 自身的 SHA-256 应为标准空文件摘要，但其文件名中的 digest 才负责绑定 recovery report。验收记录使用仓库相对 locator、Git commit 与 blob/report digest，不写本地源路径、报告绝对路径或 recovery root。

本类 Evidence 交接至少包含：四个 exact object locator/versionId/digest/size、`accessOwner`、retention、实际保护模式与 retain-until、archive/verifier identity fingerprint、两份报告和 marker 的 Git locator/digest，以及二次 identity 见证 locator、见证责任人和见证时间。任何一项缺失或不可访问时，对应 Acceptance Check 写 `UNKNOWN`。

没有真实 Company archive、独立恢复、离线 `PASS` 和受控 Evidence locator 时，不创建 `V0-2-EVIDENCE-ARCHIVE-001` 记录，也不关闭 `V0-2-PILOT-COMPANY-002` 或 `M1-OWNER-GATE-001`。新记录初始状态必须保持 `PENDING`，Owner 决定由后续独立 commit 记录。

## 8. 失败恢复

- 输入 size/digest/manifest 不匹配：停止，保留源；从权威 CI Artifact 重新取证，不修改工作包事实。
- capability、identity、transport、private access、versioning、Object Lock 或 retention 失败：停止 Company 流程，修复 Provider 配置后用新输出目录重新执行；不得降级到 filesystem 并声称长期成功。
- 第二个 Artifact 失败：保留第一个已提交 exact version 供 inventory 对账；重试可以重用内容寻址对象，但不得删除旧版本。
- 同身份、version/digest/size/receipt/protection 不一致：停止发布并独立调查；不得改用 latest、覆盖报告或缩短 retention。
- 报告或 marker 目标已存在：视为 create-only 冲突，换用新的受信目录；不得删除既有 Evidence 来重用名称。
- recovery cleanup 失败：报告保持 `FAIL`，隔离恢复目录并记录失败；不得手工补零字节 marker 把失败改写为成功。
- 离线校验失败：保留三份输入和 marker 供复核；修正根因并重新执行 Provider 阶段，不能编辑 canonical report。

## 9. Docker、CI 与生产边界

Evidence Archive 离线 gate 不依赖 Docker 或真实 S3，可以在本机和 CI 运行。完整 M1 仍包含 PostgreSQL/Testcontainers、smoke 与 restore；本机无 Docker-compatible runtime 时应在所有 Docker 前置 gate 通过后显式失败，并由获批的 GitHub Actions 或公司兼容 Runner 完整执行。

CI fixture `PASS`、完整 M1 `PASS` 与真实 Company Provider 验收是三类不同 Evidence。前两者不能推导 Company bucket、identity、Object Lock 或 production readiness 已完成；任何 `merge`、Tag、release 或 production deployment 仍需 Owner 单独授权。
