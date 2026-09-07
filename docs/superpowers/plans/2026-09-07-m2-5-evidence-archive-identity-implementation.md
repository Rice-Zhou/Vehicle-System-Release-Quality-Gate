# M2.5 Evidence Archive 身份扩展实施计划

> 执行者须使用 superpowers:subagent-driven-development 或 superpowers:executing-plans，逐任务执行复核；步骤以复选框跟踪。

**目标:** 在同一归档工具链支持 M1 v1 与 M2.5 v2，保证身份绑定、失败诊断和旧工作包兼容。

**架构:** 单一 Kotlin profile 校验与既有 parser/Archive facade；共享 Schema identity 定义；既有 Node 离线交叉校验。没有新服务、数据库或 Provider。

**Tech Stack:** Kotlin/JVM 21, JUnit 5, Jackson, JSON Schema 2020-12, AJV 8.17.1, Node.js, PowerShell, Gradle.

**Spec:** [TDR-019](../../v0.2/tdr/TDR-019-versioned-evidence-archive-work-package-identity.md). **授权记录:** [TDR-019-WRITTEN-REVIEW-001](../../governance/acceptance/records/2026-09-07-tdr-019-written-review-001.md).

**状态:** Owner 已明确授权并完成任务 1；任务 2、3 尚未执行，未获授权。

## 全局约束与执行前检查

- 只支持 `1 / V0-2-EVIDENCE-ARCHIVE-001` 与 `2 / M2-5-EVIDENCE-ARCHIVE-001`。
- 每包固定两份 Artifact，保持 `LOCAL_PILOT_NOT_IMMUTABLE`、`conditionBClosed=false`、`companyArchiveCompleted=false`。
- 不更改冻结语义、Archive Receipt/Port/Capability、create-only、exact-version、双身份、canonical 算法或完成 marker 语义。
- M1 descriptor、清单、原 ZIP 与历史报告字节保持原样；M2.5 原 Subject、两份 Artifact 与准备清单摘要保持 TDR-019 固定值。
- 禁止真实 Provider/Company、merge、Tag、发布、部署、下一里程碑。仅使用既有测试替身与本地临时目录；TEST_FIXTURE 不能用于 Company 验收。
- 实现只能在明确授权后开始。先读 AGENTS.md、冻结架构权威、TDR-012/013/019、本计划和准备包；用 git worktree list 定位现有双语 worktree，检查未提交修改及远端差异。
- 每个任务结束记录 red/green 命令、实际退出码、发现和提交；任务 1 是中间集成状态，任务 3 完成前不得宣称 M2.5 工具可交付。
- 代码块定义计划要求；实际完成范围以任务复选框和验证记录为准。

## 任务 1：版本契约与单一校验

**文件:**
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveSourceVerifier.kt`
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveModels.kt`
- `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveSourceVerifierTest.kt`
- `ops/evidence-archive/schemas/work-package.schema.json`
- `ops/evidence-archive/schemas/archive-execution.schema.json`
- `ops/evidence-archive/schemas/recovery-verification.schema.json`
- `scripts/evidence-archive/verify-evidence.mjs`
- `scripts/tests/evidence-archive-evidence.test.mjs`

**接口:** 在 SourceVerifier 文件中新增下列 internal profile；ParsedEvidenceArchiveWorkPackage 与 VerifiedEvidenceArchiveWorkPackage 追加显式 Int schemaVersion，无隐式 M1 默认值。所有构造点同步传入 1 或已解析版本。保持其余字段及既有 parser 入口不变。

```kotlin
internal enum class EvidenceArchiveWorkPackageProfile(
    val schemaVersion: Int,
    val workPackageId: String,
) {
    M1(1, "V0-2-EVIDENCE-ARCHIVE-001"),
    M25(2, "M2-5-EVIDENCE-ARCHIVE-001");

    companion object {
        fun resolve(version: Int, id: String): EvidenceArchiveWorkPackageProfile? =
            entries.singleOrNull { it.schemaVersion == version && it.workPackageId == id }
    }
}
```

- [x] 先在 SourceVerifierTest 中加入以下测试。使用既有 descriptorBytes、objectMapper 和导入；新增 schemaVersion 属性前应编译失败，补齐属性后 v2 应因现有固定 ID 被拒绝，分别保存证据。

```kotlin
@Test
fun `accepts M25 pair and rejects crossed pairs`() {
    val root = objectMapper.readTree(descriptorBytes()) as ObjectNode
    root.put("schemaVersion", 2)
    root.put("workPackageId", "M2-5-EVIDENCE-ARCHIVE-001")
    val parsed = EvidenceArchiveWorkPackageParser().parse(objectMapper.writeValueAsBytes(root))
    assertThat(parsed.schemaVersion).isEqualTo(2)
    assertThat(parsed.workPackageId).isEqualTo("M2-5-EVIDENCE-ARCHIVE-001")
    root.put("schemaVersion", 1)
    assertThatThrownBy {
        EvidenceArchiveWorkPackageParser().parse(objectMapper.writeValueAsBytes(root))
    }.isInstanceOf(EvidenceArchiveInputFailure::class.java)
}
```

- [x] 再加入未知版本 0/3、未知 ID、2/M1、超大非 Int 数字、缺失字段负例；保留源摘要、大小、ZIP32、ACL 与重复字段测试。
- [x] 在单一 parser 中先严格读取可表示为 Int 的 schemaVersion，再调用 resolve；null 使用现有 DESCRIPTOR_INVALID，不从 ID 猜版本。SourceVerifier 返回 Verified 对象时传递版本。
- [x] 把以下定义放入 work-package Schema 的 $defs.identity；根通过 allOf 引用，去掉原单值 const。archive Schema 引用同一定义。定义中不使用 additionalProperties=false，根的既有未知字段拒绝继续生效。

```json
"identity": {
  "oneOf": [
    {
      "type": "object",
      "required": ["schemaVersion", "workPackageId"],
      "properties": {
        "schemaVersion": { "const": 1 },
        "workPackageId": { "const": "V0-2-EVIDENCE-ARCHIVE-001" }
      }
    },
    {
      "type": "object",
      "required": ["schemaVersion", "workPackageId"],
      "properties": {
        "schemaVersion": { "const": 2 },
        "workPackageId": { "const": "M2-5-EVIDENCE-ARCHIVE-001" }
      }
    }
  ]
}
```

- [x] recovery Schema 使用二选一：上述绑定 identity，或版本 2/null ID 的未绑定 FAIL；后者强制 executionId、descriptorSha256、pilotManifestSha256、archiveIdentity、verifierIdentity 全为 null，artifacts 为空。保持 errorCode 必填且安全、cleanup 约束、FAIL/PASS 规则；不接受 IN_PROGRESS 为最终报告。
- [x] 离线加载器与测试 AJV 实例先注册 work-package Schema，再编译引用它的报告；保留初始化失败的明确错误。Node 删除 WORK_PACKAGE_ID 判断和固定成功输出，交叉检查三个文档版本与 ID 同时相等；成功输出采用已校验 descriptor ID。
- [x] 加入下面使用现有 fixture/helper 的实际离线测试；再覆盖每个文档的独立 ID/版本突变及未绑定 FAIL/null PASS。

```javascript
test("accepts M25 identity and rejects mixed report versions", () => {
  const f = evidenceFixture();
  f.descriptor.schemaVersion = 2;
  f.descriptor.workPackageId = "M2-5-EVIDENCE-ARCHIVE-001";
  f.descriptorBytes = Buffer.from(JSON.stringify(f.descriptor));
  for (const report of [f.archiveReport, f.recoveryReport]) {
    report.schemaVersion = 2;
    report.workPackageId = f.descriptor.workPackageId;
    report.descriptorSha256 = sha256(f.descriptorBytes);
  }
  f.archiveReportBytes = canonicalBytes(f.archiveReport);
  f.recoveryReportBytes = canonicalBytes(f.recoveryReport);
  assert.equal(verifyFixture(f).workPackageId, f.descriptor.workPackageId);
  f.recoveryReport.schemaVersion = 1;
  f.recoveryReportBytes = canonicalBytes(f.recoveryReport);
  assert.throws(() => verifyFixture(f));
});
```

- [x] 运行目标测试，修复至通过，再审查 diff 和提交。Kotlin 构造点仅为显式版本迁移，禁止随手重构；现有 M1 测试仍通过。

```powershell
node --test scripts/tests/evidence-archive-evidence.test.mjs
# Run from backend
./gradlew test --tests "*EvidenceArchiveSourceVerifierTest"
```

后端本次触及的测试类添加 JUnit @Timeout(60)（使用默认秒），保证单测不无限阻塞；编译与依赖下载耗时单独报告，不把它们算作单测超时。建议提交信息： `feat(archive): validate versioned work package identities`.

## 任务 2：执行、恢复和安全摘要

**文件:**
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveRunner.kt`
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveRecoveryVerifier.kt`
- `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/archive/operations/EvidenceArchiveOperationMain.kt`
- `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveRunnerTest.kt`
- `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveRecoveryVerifierTest.kt`
- `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveOperationMainTest.kt`

**接口:** 消费任务 1 的 resolve 与显式 schemaVersion。RecoveryReport.workPackageId 改为 String?；safeFailureReport 接受本 invocation 已完整解析的工作包或 null。OperationSummary 追加内部使用的 Int? schemaVersion（所有构造点显式填写），其 JSON 输出仍保持既有字段形状。没有新的 public 管理入口。

- [ ] 先加入 Runner 的 v2 行为测试；使用既有 resultFor、ScriptedArchiveAdapter 与 WORK_PACKAGE。另加 v2 ID/v1 版本以及 receipt acceptanceId 不匹配负例；不得只检查 mock 次数。

```kotlin
@Test
fun `propagates M25 identity through facade and report`() {
    val id = "M2-5-EVIDENCE-ARCHIVE-001"
    fun result(source: VerifiedArchiveSource): ArchiveResult {
        val original = resultFor(source)
        return original.copy(receipt = original.receipt.copy(acceptanceId = id))
    }
    val adapter = ScriptedArchiveAdapter(result(FIRST_SOURCE), result(SECOND_SOURCE))
    val report = runner(adapter).run(WORK_PACKAGE.copy(workPackageId = id, schemaVersion = 2))
    assertThat(report.status).isEqualTo(OperationStatus.PASS)
    assertThat(report.schemaVersion).isEqualTo(2)
    assertThat(report.workPackageId).isEqualTo(id)
    assertThat(adapter.commands).allSatisfy { assertThat(it.acceptanceId).isEqualTo(id) }
}
```

- [ ] 加入恢复解析前失败测试；再用有效 v2 descriptor 与损坏 archive JSON 验证解析后失败仍为 v2/M2.5。覆盖 recover 与 recoverFiles 两个入口，包括无法读取 archive 文件而 descriptor 有效的情形。

```kotlin
@Test
fun `malformed descriptor has no inferred work package identity`() {
    val output = reportOutput("unbound.json")
    val result = fixture.verifier().recover(
        "{".toByteArray(),
        fixture.archiveReportBytes(),
        emptyRoot("unbound-recovery"),
        output,
    )
    assertThat(result.status).isEqualTo(OperationStatus.FAIL)
    assertThat(result.schemaVersion).isEqualTo(2)
    assertThat(result.workPackageId).isNull()
    assertThat(result.descriptorSha256).isNull()
    assertThat(result.artifacts).isEmpty()
}
```

- [ ] Runner 在任何 facade/provider 工作前用 resolve 验证工作包配对；报告版本来自工作包，receipt acceptanceId 继续沿现有通路传递和核对。固定 M1 样本 canonical bytes 不变。
- [ ] 将 staged recovery 的输入读取改为显式顺序：beginOutput → 读取并完整 parse descriptor → 保存本 invocation 的已验证上下文 → 读取/parse archive report → execute。不能先把两份文件同时读为 Pair，否则第二份读取失败时会丢失已经可确认的身份。该上下文只存在于当前调用，不写全局状态。
- [ ] 输入尚未完整通过 descriptor parser 时，provisional 为 v2/null/IN_PROGRESS，最终失败为 v2/null/FAIL，其他未绑定字段按任务 1 清空。解析后失败使用上下文的版本/ID；不可信 archive identity 不得复制到失败报告。保持已有 exception 分类、清理结果、Error 传播与安全诊断，不新增吞错。
- [ ] Recovery 的 validateWorkPackage、validateArchive 与 exactSchemaVersion 路径改用同一 profile/相等约束；保留 digest、executionId、精确引用、实际保护及 receipt acceptanceId 检查。canonical writer 明确写 null ID，不能写字符串 null。provisional 不当作最终报告；completion marker 保持现有字节绑定与发布语义，FAIL 即使具有完成 marker 也不能通过离线验收。
- [ ] OperationMain 的摘要 PASS 需要 resolve 成功、两份 Artifact 和无错误；FAIL 允许安全 null 身份。测试正确两种配对、不匹配、未知 ID、null PASS、原 M1 JSON 字节、标准错误和非零退出。传给摘要的版本来自已验证报告，禁止靠 ID 重建。
- [ ] 保留并扩展现有 Fixture，使版本/ID 作为显式测试参数同时进入 descriptor、报告与 receipt；计算原始 descriptor 摘要，禁止硬编码一个摘要模拟绑定通过。先保存失败输出，再运行以下命令至通过，审查并提交。

```powershell
# Run from backend
./gradlew test --tests "*EvidenceArchiveRunnerTest" --tests "*EvidenceArchiveRecoveryVerifierTest" --tests "*EvidenceArchiveOperationMainTest"
./gradlew assemble
```

建议提交信息： `feat(archive): propagate verified identity through recovery`.

## 任务 3：固定输入与端到端回归

**文件:**
- `ops/evidence-archive/m2-5-evidence-archive-001.json`
- `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/archive/operations/EvidenceArchiveIdentityIntegrationTest.kt`
- `backend/src/test/resources/evidence-archive/identity-m25/descriptor.json`
- `backend/src/test/resources/evidence-archive/identity-m25/archive-report.json`
- `backend/src/test/resources/evidence-archive/identity-m25/recovery-report.json`
- `backend/src/test/resources/evidence-archive/identity-m25/recovery-report.json.complete.<sha256>`
- `scripts/tests/evidence-archive-evidence.test.mjs`
- `ops/evidence-archive/m2-5-preparation/README.md`
- `docs/m1/evidence-archive-runbook.md`

**接口:** 新增 identity-m25 目录仅存测试生成的固定 canonical 样本；marker 文件名最后一段是 recovery 原始字节真实摘要，<sha256> 不作为字面文件名。JVM 集成测试调用真实 SourceVerifier、Runner、RecoveryVerifier；复用测试 S3Gateway/ArchiveAdapter，Node 读取其实际报告字节。不新增生产 Provider。

- [ ] 在授权实施时从仓库根执行下面 Node 模块内容，创建正式 descriptor；使用 create-only，存在则先核对，不覆盖。检查结果的原 Subject、run、Artifact、摘要与准备清单逐项一致。

```javascript
import fs from "node:fs";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
const bytes = fs.readFileSync("ops/evidence-archive/m2-5-preparation/pilot-preservation-manifest.json");
assert.equal(createHash("sha256").update(bytes).digest("hex"),
  "c5f3b1e7ffa11a1627de70cf9b9f4853d50af5e6ad3aa40608113327fdc87300");
const manifest = JSON.parse(bytes);
assert.equal(manifest.artifacts.length, 2);
const descriptor = {
  schemaVersion: 2,
  workPackageId: "M2-5-EVIDENCE-ARCHIVE-001",
  subjectCommit: manifest.implementationSubjectCommit,
  pairedSubjectCommit: manifest.pairedImplementationSubjectCommit,
  pilotManifest: {
    fileName: "pilot-preservation-manifest.json",
    sha256: createHash("sha256").update(bytes).digest("hex"),
    classification: "LOCAL_PILOT_NOT_IMMUTABLE",
    conditionBClosed: false
  },
  artifacts: manifest.artifacts.map(a => ({
    artifactId: a.artifactId, artifactName: a.artifactName, fileName: a.fileName,
    sourceRunId: a.sourceRunId, sourceCommit: a.sourceCommit,
    sizeBytes: a.sizeBytes, sha256: a.sha256
  }))
};
fs.writeFileSync("ops/evidence-archive/m2-5-evidence-archive-001.json",
  JSON.stringify(descriptor, null, 2) + "\n", { flag: "wx" });
```

- [ ] 为 descriptor 添加 Schema 通过及固定输入清单相等测试；保留原 M1 文件摘要对比。不得用准备清单直接调用 operation。
- [ ] 集成测试固定时钟、executionId、测试身份和本地受控目录，用测试 ZIP 和清单执行 source→archive→verify。Fixture 文件必须由这条 JVM 实际流程导出，再由 Node 读取；不要手写“PASS”报告来替代 producer 证明。复用现有测试 gateway 的实际 receipt body、versionId 和 protection 语义。
- [ ] JVM 测试把生成字节与提交样本逐字节比较；Node 添加下列消费断言。M1 沿用既有 JVM 样本，不重写。测试生成在临时目录完成，实际报告输出目录不得冒充 Company Evidence。

```javascript
test("consumes actual JVM M25 archive and recovery output", () => {
  const root = path.join(repositoryRoot, "backend/src/test/resources/evidence-archive/identity-m25");
  const result = evidenceVerifier.verifyEvidenceFiles({
    workPackagePath: path.join(root, "descriptor.json"),
    archiveReportPath: path.join(root, "archive-report.json"),
    recoveryReportPath: path.join(root, "recovery-report.json")
  });
  assert.equal(result.workPackageId, "M2-5-EVIDENCE-ARCHIVE-001");
});
```

- [ ] 将集成路径扩展到 M1/v1 对照，失败矩阵覆盖下表；给出实际预期错误类/码、无 Provider 写入或无假成功的证据。保留全部既有回归，不能删除失败用例来满足总数。

| 场景 | 必须证明 |
|---|---|
| 任一文档 ID/版本单独突变 | Schema 或跨文档检查拒绝，不能借另一工作包通过 |
| 同 ID descriptor 原始字节变化 | 摘要不符即拒绝；不重新 canonicalize descriptor 掩盖变化 |
| receipt acceptanceId | 恢复真实 receipt body 后拒绝跨包值 |
| 解析前/解析后失败 | 前者 null，后者保留正确配对；两者 FAIL 且非零退出 |
| 恶意 ZIP、ACL/身份、清理/发布失败 | 既有 fail-closed 与外来文件保护保持；不变成归档成功 |
| 有效 M1 与 M2.5 | 各自 JVM→Node PASS，M1 原 canonical 字节保持 |

- [ ] 更新 runbook：v1/v2 工具兼容、null 诊断的含义、独立 Company 授权、回退时保留 v2 原报告；准备包只记录技术支持验证结果，不把资源 UNKNOWN 改成 PASS。
- [ ] 运行完整受影响归档测试、构建和既有门禁；依次检查双语提交的非 Markdown 字节一致、Pair Gate、各自 exact-head CI。任何失败先定位并修复，不使用上轮 CI 代替。

```powershell
# Run from repository root
node --test scripts/tests/evidence-archive-evidence.test.mjs
node scripts/acceptance-record-validator.mjs
node scripts/contract-validator.mjs
# Run from backend
./gradlew test --tests "*EvidenceArchive*Test"
./gradlew assemble
# Run from repository root after paired commits
pwsh -NoProfile -File scripts/verify-language-branches.ps1 -Mode Pair -ChineseRef docs/m2-issue-traceability-design -EnglishRef docs/m2-issue-traceability-design-en
```

- [ ] 审查完整 diff：无重复业务权威、宽泛吞错、隐式默认 M1、过度输入泛化、秘密或假 Company Evidence。生成实施验证记录，包含每个任务提交、测试结果、失败修正、最终 CI 及原限制；保留 Owner 判定待定，另行提交验收。
建议提交信息： `feat(archive): verify fixed M2.5 package end to end`.

## 完成证据与下一步执行计划

本计划覆盖 TDR-019 的输入、身份链、失败、兼容、迁移及验证矩阵。当前任务 1 已完成；任务 2、3 未执行，不宣称完整 v2 运行流程已通过。计划自检包括文件定位、接口一致性、双语技术标识、约束覆盖与占位项检查。

当前结果：任务 1 已完成，见[验证记录](../../m2/2026-09-07-evidence-archive-identity-task1.md)。Git 状态：以本计划所在双语提交和远端为准。下一步动作：执行任务 2。前置条件：Owner 明确授权任务 2。验收目标：运行时身份贯穿、解析前后失败处理、M1 兼容、red/green 与双语 CI。Company 写入与独立恢复仍另行授权。
