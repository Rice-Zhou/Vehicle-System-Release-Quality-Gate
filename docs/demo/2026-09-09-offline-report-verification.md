# 离线演示报告实施验证记录

## 范围与来源

按 [TDR-023](../v0.2/tdr/TDR-023-offline-demo-report.md)实施任务 1，仅新增离线呈现工具、测试、样例和现有 M1 CI 集成。Owner 的“执行下一步”针对方案 8b1dbc3 / 78bf739 授权实施，不表示验收通过。

输入样例来源、原成员与摘要见[样例记录](../../demo/report/sample/README.md)；三个 JSON 从已核验 CI ZIP 原样复制，6 项 M1 与 10 项 M2 场景均 PASS，runId/commit/dirty/Release/Manifest 和文件摘要逐项匹配。样例的源 commit 8d5354d 不等于报告生成器实施提交。

## 本地验证

- RED：先写测试，在核心模块缺失时 node:test 返回 ERR_MODULE_NOT_FOUND，退出 1。GREEN：Node v20.14.0 执行 node --test scripts/tests/demo-report.test.mjs，初轮 21/21 PASS；独立审查发现三项边界问题后增加回归，第一轮修复后为 24/24；最终空值类型修复后 Node v20.14.0 与 v24.19.0 均为 25 tests、25 PASS、0 failed/skipped。
- 两个新增模块语法检查通过；workflow YAML 和新增嵌入 PowerShell 语法检查通过。
- Edge 152.0.4191.66 实际 headless 浏览器，以离线 context 打开正常、最小 FAILED、部分 FAILED/仅 HTTP 状态、特殊文本四类各 zh/en，共 8/8 PASS；检查源状态、标识、Gap、缺失字段与转义，0 HTTP 请求、0 page errors、0 script/img 元素，特殊文本未执行。
- 1280px 桌面与 390px 窄屏检查；窄屏页面宽度为 390px，表格在容器内滚动。首轮发现表头挤断，已调整 Issue/Artifact 列宽、桌面主体宽度及中文说明后复测。
- 本地 HTML、截图和机器检查记录在 backend/build/report-verification/1788935729190/；检查脚本、实施报告与后续评审记录保留于本计划独立 SDD 工作区。截图是本机验证材料，不声称已上传 CI。

## 独立审查与修复

首轮任务审查发现三项问题：FAILED 部分 Issue/history 被要求完整、仅有 HTTP 状态的字段被遗漏、大小检查之后使用无界读取。新增回归先复现前两项失败，随后保留已有的部分字段、按场景/HTTP 字段并集呈现，并改为单文件句柄元数据检查和至多 1 MiB + 1 字节读取；超限拒绝。上述 25 项测试和 8 项浏览器检查针对最终修复后的代码；局部复审已确认三项问题全部关闭，Spec Compliance / Task Quality 均 PASS；最终审查与 CI 见下方交付证据。

最终审查另发现显式 path/gaps=null 被当作缺失接受；已按源字段存在性校验数组，PASS/FAILED 的显式 null 均拒绝，真正省略的 FAILED 字段仍可显示为未提供。新增回归先复现失败，再通过；最终局部复审 Approved，P2 已关闭，未发现直接回归。

## 交付与限制

本地检查与准确实施提交的 CI 分别记录；已核实的交付证据见下方，不推导 Owner 批准。

浏览器只打开派生报告，不重跑数据库/HTTP/历史验证。全部合成、Verified=false；REPORT_RENDERED 不是 Release 或 Owner PASS。原 M2.5 性能参考差距、canonical 覆盖限制、既有 Windows ACL 跳过和未单独故障注入的 Worker FAILED/超时传播限制保持不变。原 CI Artifact 有限保留，三个样例 JSON 已随 Git 保存。

## 准确提交交付证据

实施 Subject：中文 c5400fd33e6fa502f141f6ce25bf950a9b350fb6；英文 1fc37d4f795f815a7643c3791d7fc4878d6f1681。最终局部复审 Approved，无遗留可执行发现，仅为工程评审。实施 Pair Gate 已通过，双语分支已原子推送。

四条运行均创建于 2026-09-09T06:40:04Z，GitHub API 已核对准确 head_sha 与 completed/success：[中文 M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34320107876)、[中文 M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34320107724)、[英文 M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34320107781)、[英文 M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34320107709)。两条 M1 job 中新增报告测试与生成步骤均成功。

下载的两份演示 ZIP 各有四组同次 JSON 与八份 HTML（zh/en）：三次源 M1 PASS、一次错误口令导致的预期 FAILED，其中两次包含 M2。M2 场景状态均 10/10 PASS，commit 和 workingTreeDirty=false 与 Subject 对应。全部 16 份 HTML 使用随包同目录 JSON 离线重新生成，完整 HTML 文本逐份一致，另核对源标识/状态/Snapshot ID/Gap code；未发现 script、事件属性或远程资源引用。源 FAILED 保持 FAILED，与生成成功分开。

| 分支 | Artifact ID | 生成 UTC | 到期 UTC | ZIP SHA-256 |
|---|---|---|---|---|
| zh | 10091796746 | 2026-09-09T06:50:06Z | 2026-10-09T06:50:05Z | 4e7852199fcda03ff8cda11b03362314e6eb34833c22a00864c8f55363758241 |
| en | 10091809014 | 2026-09-09T06:50:32Z | 2026-10-09T06:50:31Z | 3808ca4dc9368b9f7699beb615518cd9e9e6145ae01a9d6c0b28c9d3c65aafa4 |

上述 Artifact 属于所链接的 M1 运行，定位与 ZIP 摘要标识实际下载字节，不声称永久保留；原样例 JSON 另已随 Git 保存。[Owner 审阅记录](../governance/acceptance/records/2026-09-09-tdr-023-demo-report-review-001.md)为 PENDING，文档记录提交与实施 Subject 分离。

## 下一步执行计划

当前结果：TDR-023 任务 1 实施、最终评审及准确提交的双语 CI/Artifact 核对已完成，Owner 审阅记录为 PENDING。Git 状态：实施已推送，本交付记录按双语治理独立版本化。下一步动作：Owner 审阅 TDR-023-DEMO-REPORT-REVIEW-001，Subject c5400fd / 1fc37d4。前置条件：Owner 明确决定；无需 Company 资源或新环境。验收目标：正常及失败报告能清楚解释已记录事实与边界，满足当前阶段展示目标。
