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

首轮任务审查发现三项问题：FAILED 部分 Issue/history 被要求完整、仅有 HTTP 状态的字段被遗漏、大小检查之后使用无界读取。新增回归先复现前两项失败，随后保留已有的部分字段、按场景/HTTP 字段并集呈现，并改为单文件句柄元数据检查和至多 1 MiB + 1 字节读取；超限拒绝。上述 25 项测试和 8 项浏览器检查针对最终修复后的代码；局部复审已确认三项问题全部关闭，Spec Compliance / Task Quality 均 PASS；最终审查与 CI 在后续交付记录补齐。

最终审查另发现显式 path/gaps=null 被当作缺失接受；已按源字段存在性校验数组，PASS/FAILED 的显式 null 均拒绝，真正省略的 FAILED 字段仍可显示为未提供。新增回归先复现失败，再通过；最终局部复审结论在后续交付记录补齐。

## 交付与限制

本地检查不代替准确实施提交的 CI；本次实施提交推送后须补充双语 M1/M2 CI、8 份每分支 HTML 与同目录 JSON 的对照结果以及独立评审结论。此初始记录不宣称这些待核对项已通过。

浏览器只打开派生报告，不重跑数据库/HTTP/历史验证。全部合成、Verified=false；REPORT_RENDERED 不是 Release 或 Owner PASS。原 M2.5 性能参考差距、canonical 覆盖限制、既有 Windows ACL 跳过和未单独故障注入的 Worker FAILED/超时传播限制保持不变。原 CI Artifact 有限保留，三个样例 JSON 已随 Git 保存。

## 下一步执行计划

当前结果：实现与本地检查完成，独立评审及准确提交 CI 待收口。Git 状态：与实施按双语治理版本化，推送以远端核对为准。下一步动作：完成固定实施提交的 CI/Artifact 对照并整理 Owner 审阅记录。前置条件：独立评审与双语 CI 实际结果；无需新环境。验收目标：报告原始状态和字段可逐项定位、失败不被掩盖、证据绑定准确实施提交，供 Owner 决定。
