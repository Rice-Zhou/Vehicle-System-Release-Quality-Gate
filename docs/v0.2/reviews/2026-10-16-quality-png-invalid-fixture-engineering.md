# G4 PNG_INVALID 非真机夹具工程记录

- 日期：2026-10-16；范围：已确认 D3 的受控输入、失败回归与最小测试专用夹具，不是 G4 正式串联或 Owner 验收。
- 依据：[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)、[G4 入口](2026-10-15-quality-controlled-entry-engineering.md)及[运行手册](../../demo/quality-evaluation-runbook.md)。作者和独立评审者仍待指定，不据本改动授予项目权限或发布规则。

## 实现与隔离

复用测试源码中的 `M3FixtureAgentMain`、既有 SmokeDevice、AgentLoop、ScreenshotCollector、LogCollector 和 mTLS client。入口仍要求 `VSRQG_M3_EXECUTION_MODE=CI_FIXTURE`，仅新增显式测试输入 `VSRQG_M3_SCREENSHOT_INPUT=PNG_INVALID`。未设置时维持正常 PNG；未知或空值以 `FIXTURE_SCREENSHOT_INPUT_INVALID` 在配置解析和协议活动前失败。

无效模式只改变 screenshot 返回的 bytes：已正常 launch 且 foreground 为真后，返回八个零字节。现有 ScreenshotCollector 自行报 PNG_INVALID；原 AgentLoop 保留已确认 LOG，以 ERROR 和原原因提交，并等待 RESULT_ACKED。没有伪造结果、绕过校验或修改生产采集器。失败截图没有候选、声明、上传或落盘。

类仍只在 test classpath，由原有 m3FixtureClasspath 构建，不进入生产 Agent JAR/distribution。未增加 G4 配置字段、生产入口、Plan/Case、规则、数据库迁移或第二套执行器；既有 normal Plan/Case v1 是后续合法来源选择，不需预设 v3。

## 失败与验证

先增加 `M3FixturePngInputTest`，通过子 JVM 运行现有测试入口，使用本地模拟 mTLS HTTPS 服务。首轮离线构建缺少 kotlin-reflect 缓存，未执行测试，不计作红灯；正常解析依赖后，四项中三项真实失败：PNG_INVALID 输入仍返回 PASS，未知和空值仍以 exit 0 完成；默认 PNG 路径通过。根因是旧夹具忽略该输入选择。

补入输入解析与无效 bytes 后，目标四项全部通过，无失败、错误或跳过。反例覆盖未知与空值不静默 fallback；正常对照保留 PASS、LOG 和 SCREENSHOT。缺图测试核对安装及 Smoke 检查正常、精确 ERROR/PNG_INVALID、仅 LOG 声明和上传、无 screenshot 文件、持久化 observation、result receipt 与 RESULT_ACKED。

复用现有 M3 workflow 的 Agent 全量测试和 XML 归档，无新增 CI 基础设施。双语完整 Agent 测试/构建、生产打包排除检查、Pair Gate、实际推送 Subject 与六条 GitHub CI 的最终结果以交付回执为准，不预先把待完成 CI 标为通过。红/绿 XML、构建日志及回执目录：`D:/VSRQG-local-smoke/g4-readonly-audit-20261015-d3516207/g4-png-fixture-20261016`。

## 未验证边界与下一步

本轮只执行自动测试自建的模拟服务和临时输出，不连接私有数据库/Backend，不创建用户环境的业务 Run、不发布规则、不创建身份或授予权限，也不启动受控环境 Agent。没有真机、真实 Provider、Company、merge、Tag 或部署。

此处证明采集输入经真实 AgentLoop 成为可持久化 ERROR；模拟服务不验证正式数据库的 Evidence requirement 状态，也不产生 Quality Evaluation。相同正式 Release 的 QUALITY_REQUIRED_EVIDENCE_MISSING、NOT_EVALUATED、无固定 Input Snapshot/Quality Result 及报告“未固定/未确定”仍须在后续获授权的 G4 串联中单独留证，不能拼接本轮测试来宣称完成。TDR-026 的事务外 bytes 校验和封存窗口语义保持不变，状态仍 Proposed / REVIEW_REQUIRED；Task 5/6、A1–A8 与验收状态不变。

当前结果：D3 的 PNG_INVALID 输入及独立失败回归已实现。Git 状态：中英文分别提交推送，固定 Subject 和验证结果见交付回执。下一步动作：Owner 指定作者与独立评审责任人后，只读核对身份映射。前置条件：不同责任人及对应身份；身份创建、权限授予、规则发布和实际 Run 仍需分别授权。验收目标：身份及项目映射明确、权限缺口可核对；本夹具测试不代替 Owner 验收或 G4 四场景证据。
