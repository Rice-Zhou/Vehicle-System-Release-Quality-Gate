# M1/M2 离线只读演示报告实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从同次既有 M1/M2 JSON 生成能离线打开的中英文只读报告。

**Architecture:** 单一纯呈现模块负责字段投影、关联检查与 HTML；独立 CLI 只负责参数和文件 I/O。不连接 Backend，不重算业务结论，不改变演示生命周期。

**Tech Stack:** 现有 Node v20.14.0 / CI Node 24、内置 node:test、HTML/CSS、现有 GitHub M1 workflow；无新依赖。

**Spec:** [TDR-023](../../v0.2/tdr/TDR-023-offline-demo-report.md)，同时作为本计划设计规范；Proposed，等待 Owner 确认实施范围。

## Global Constraints

- 必填 --run-dir、--output、--scope m1|m2、--language zh|en；每输入文件最多 1 MiB；固定文件名；输出不得覆盖旧文件或输入。
- 只呈现 SYNTHETIC_DEMO / SYNTHETIC_FIXTURE，Verified=false；JSON 的 PASS/FAILED 与 REPORT_RENDERED 分开，不能生成 Release PASS/BLOCK。
- 中文/英文文案由同一模块显式选择；非 Markdown 文件在双语分支相同。
- 单个测试默认 timeout: 60000；纯生成器无需 JVM、Docker、服务、密钥或网络。
- 原业务代码、Schema、Migration、鉴权、DemoReport/M2DemoReport、run-m1.ps1 与已接受记录不改；不得 merge、Tag、发布、部署或启用真实 Provider/Company。

## Task 1: 生成器、样例与展示交付

**Files:**
- Create: scripts/demo/demo-report.mjs（唯一呈现/输入投影模块）、scripts/demo/render-report.mjs（CLI）。
- Create: scripts/tests/demo-report.test.mjs（node:test，含内存负向变体）。
- Create: demo/report/sample/summary.json、manifest.json、m2-summary.json 及 README.md（原样例与双语来源记录）。
- Create: docs/demo/offline-report-runbook.md（操作与结果含义）、docs/demo/2026-09-09-offline-report-verification.md（实际验证记录）。
- Modify: .github/workflows/m1-backend.yml（测试、生成与原 Artifact 路径）、docs/m2/synthetic-demo-runbook.md（链接离线报告说明）。
- 状态记录：实施获授权后更新 TDR-023 及 TDR 索引；本计划勾选完成项；最终 Owner 决定另按既有验收治理记录。

**Interfaces:**
- renderDemoReport({ summary, manifest, m2 }, { scope, language }) -> string；输入为已解析 JSON，缺失可选文件传 null；返回完整 HTML，不修改输入。只由此函数实现 TDR 的字段类型、同次关联、白名单、转义和展示规则。
- ReportInputError extends Error，公开 code 为 REPORT_INPUT_INVALID 或 REPORT_INPUT_MISMATCH；CLI 不打印原始 message/stack。
- generateReport({ runDirectory, outputFile, scope, language }) -> Promise<void>；位于 CLI 文件且可导入；严格解析参数只在直接执行时调用，导入不启动命令。文件检查/JSON 解析之后调用 renderDemoReport；失败只映射 TDR 固定码，不重复业务/格式规则。

- [ ] **Step 1: 固定合成输入。** 按 TDR 的 Artifact ID、ZIP digest 和 source commit 核对已有样例，选择同一个成功 IncludeM2 run 的三份文件。原样保存并用 README 记录来源、成员路径、摘要和合成边界；两分支使用同一组字节。不可用时明确记录，不把人工夹具写成实际 CI Evidence。
- [ ] **Step 2: 先写失败测试。** 在 scripts/tests/demo-report.test.mjs 读取固定样例；实现以下关键测试并运行，确认因模块/函数尚不存在失败，再进入实现。readSample 只解析三个已知文件，负例用 structuredClone，不污染源样例。

```javascript
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { renderDemoReport } from '../demo/demo-report.mjs';
async function readSample() {
  const read = async name => JSON.parse(await readFile(
    new URL(`../../demo/report/sample/${name}`, import.meta.url), 'utf8'));
  return { summary: await read('summary.json'),
    manifest: await read('manifest.json'), m2: await read('m2-summary.json') };
}
test('same-run values and escaping', { timeout: 60000 }, async () => {
  const input = await readSample();
  input.manifest.artifacts[0].name = '<img src=x onerror=alert(1)>';
  const before = JSON.stringify(input);
  const html = renderDemoReport(input, { scope: 'm2', language: 'en' });
  assert.ok(html.includes(input.summary.runId));
  assert.ok(html.includes(input.m2.traceabilitySnapshotIds.A));
  assert.ok(html.includes('&lt;img src=x onerror=alert(1)&gt;'));
  assert.ok(!html.includes('<img'));
  assert.equal(JSON.stringify(input), before);
});
test('mixed run is rejected', { timeout: 60000 }, async () => {
  const input = await readSample();
  input.m2.runId = '00000000-0000-4000-8000-000000000000';
  assert.throws(() => renderDemoReport(input, { scope: 'm2', language: 'zh' }),
    error => error.code === 'REPORT_INPUT_MISMATCH');
});
```

- [ ] **Step 3: 实现唯一呈现模块。** 按 TDR 输入表检查 shape、可选 FAILED 字段和关联，投影到局部对象；使用固定 zh/en 字典与同一个 escapeHtml 函数输出完整文档。固定模板用 data-field 标识行/单元格，测试能精确定位原字段；不存在数据不默认成功。主体以 Artifact 表、A/B Issue 表、path/Gap 列表和原生 details 呈现，不加入脚本、图计算或任意对象序列化。

```javascript
const escapeHtml = value => String(value).replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[character]);
// 固定字段示例；所有动态文本都经 escapeHtml。
const cell = (key, value) => `<td data-field="${key}">${escapeHtml(value)}</td>`;
// key 只来自内部固定字段常量，不能取自输入对象键。
```

- [ ] **Step 4: 实现 CLI 边界并扩充测试。** 使用 lstat/文件类型检查、限量读取、严格 UTF-8 解码、JSON.parse、renderDemoReport 和独占创建输出；I/O 错误映射固定码，stderr 不含输入或路径。每个任务测试均设置 60 秒上限。按下表逐项加入有具体结果断言的测试。

| 测试输入 | 必须断言 |
|---|---|
| zh/en 正常 M2、m1 scope | 原标识、摘要、布尔值、路径/Gap 和各自 status 显示一致；语言文案变化，技术值不变；m1 不读取 M2 |
| summary 最小 FAILED，null commit/dirty，无业务文件 | 正常生成，页面 FAILED/未提供，不能出现虚构 PASS、Lock 或 M2 原因 |
| 部分 FAILED、NOT_RUN/RUNNING、m2 FAILED | 保留已有字段和状态；不合并成成功 |
| summary PASS 缺 Manifest/M2、损坏 JSON、错误类型或 Verified=true | 非零，固定 INPUT_INVALID，无 HTML |
| runId/commit/dirty/Release/Manifest 不同 | INPUT_MISMATCH，无 HTML；不靠“最新目录”消除不一致 |
| 文件超 1 MiB、非法 UTF-8、非普通文件/符号链接 | INPUT_INVALID；无源文件写操作 |
| 输出已存在、输出等于任一输入、父目录不存在 | OUTPUT_FAILED，原文件 bytes 不变 |
| 缺失/重复/未知参数、非法 scope/language | ARGUMENT_INVALID，不能静默选默认 |
| 特殊文本、未知 token/locator 扩展字段 | 特殊文本只显示为文本，未知字段和值不在 HTML；无 script、网络资源或事件属性 |

Run: node --test scripts/tests/demo-report.test.mjs。目标：全部目标测试通过；文件与 CLI 负例用临时目录和 subprocess 验证真实退出码，不能只测纯函数。

- [ ] **Step 5: 实际生成并检查浏览器。** 使用以下命令在已存在的 backend/build 目录生成两份报告；再用测试临时失败样例生成失败报告。浏览器打开正常/失败的 zh/en，检查窄屏与长 ID、表格内容、无网络请求和无可执行注入。把实际环境、固定输入、命令、结果及截图定位记录在验证文档；不可用的检查明确标注未完成。

```powershell
node scripts/demo/render-report.mjs --run-dir demo/report/sample --output backend/build/report.zh.html --scope m2 --language zh
node scripts/demo/render-report.mjs --run-dir demo/report/sample --output backend/build/report.en.html --scope m2 --language en
```

- [ ] **Step 6: 接入现有 CI 和操作说明。** M1 workflow 在 Node setup 后运行目标 node:test；在既有 demo lifecycle 检查后枚举本次 CI backend/build/demo/m1 下实际 summary 所在目录，存在 m2-summary 则传 m2，否则 m1；每目录生成 zh/en。不存在 summary 时明确失败；每个 CLI 非零透传。原 Artifact path 增加 backend/build/demo/m1/*/report.*.html，保留 always 上传和 retention-days: 30。手册区分“生成需要已有 Node”和“浏览器打开无需 Node”，说明显式目录/范围、REPORT_RENDERED 与源 status 的区别、已有输出不覆盖及数据到期；不改变 run-m1.ps1。
- [ ] **Step 7: 完成验证与配对交付。** 运行下列文档检查、目标测试及 diff review；配对提交后运行 Pair Gate，确认非 Markdown 相同，再原子推送。核对准确实施 commit 的现有 CI、HTML/JSON Artifact 和实际字段对应，将验证记录与产品实施提交分开；缺失或失败不得标为完成。Owner 验收只在实际报告可审阅之后按既有记录流程处理。

```powershell
node --test scripts/tests/demo-report.test.mjs
node scripts/contract-validator.mjs
node scripts/acceptance-record-validator.mjs
git diff --check
pwsh -NoProfile -File scripts/verify-language-branches.ps1 -Mode Pair -ChineseRef docs/m2-issue-traceability-design -EnglishRef docs/m2-issue-traceability-design-en
```

每条命令分别检查退出码，失败立即停止后续提交/推送。纯报告工具无后端代码变更，不要求为了 HTML 重新安装本机 Docker；现有 CI 继续按原流程验证实际演示。

## 方案自检与下一步

一个任务涵盖输入定位、失败语义、双语呈现、安全、CI 和浏览器验证；TDR 每项完成条件均有明确步骤与测试。未运行计划中的新增命令，未创建或宣称新的报告 Evidence。

当前结果：实施步骤已编制，尚未实施。Git 状态：与 TDR-023 按双语文档版本化。下一步动作：执行任务 1。前置条件：Owner 确认 TDR-023 实施范围；沿用当前 worktree，无需重复创建或新增环境。验收目标：自动化测试、双语实际渲染、准确提交 CI 和输入不变证据齐全，交付报告供 Owner 审阅。
