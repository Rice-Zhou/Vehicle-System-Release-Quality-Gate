# Task 6 Evidence 元数据定位工程记录

- 日期：2026-10-10；性质：隔离夹具工程验证，不是 Owner 或里程碑验收。
- 依据：[TDR-026 Task 6 补充提议](../tdr/TDR-026-minimal-quality-evaluation.md)、[正式来源 GET 记录](2026-10-10-quality-task6-source-get-engineering.md)。

## 验证范围与结果

沿用现有 `EvidenceFixture` 的 PostgreSQL、受控 Payload 与启用的 `vsrqg.demo.evidence.enabled=true` 测试上下文，对固定 Evidence ID 调用正式 `GET /api/v1/evidence/{evidenceId}`。有 `evidence:read` scope 和项目成员关系时，HTTP 200 返回同一 ID 及 `integrity=VERIFIED`，数据库 `evidence_integrity_observation` 增加一条 `VERIFIED`。缺 scope 或撤销项目成员关系时返回 403，observation 数量不变。仅在测试专用文件中破坏同一 Payload 后，GET 返回 `integrity=INTEGRITY_ERROR`，数据库增加对应 observation。测试没有请求下载授权或 Payload。

双语静态质量报告测试使用非空 `evidenceRefs`，确认页面保留 Evidence ID、类型、Run/Attempt、摘要及大小，不生成下载链接、`grantId` 或 `/payload` 路径。正式质量 Evaluation 的当前隔离夹具仍没有 Evidence 引用，因此本段不能证明报告中的 ID 与上述 Evidence GET 属于同一质量输入，也不能声称真实设备 Evidence 导航已验收。

本机目标 Node 测试、契约校验、`backend/gradlew.bat compileTestKotlin --no-daemon` 与 `git diff --check` 通过。本机无 Docker；下方 M1 的 `Run M1 candidate gate` 执行 `clean test bootJar`，包含新增 PostgreSQL HTTP 测试。未修改生产 Evidence 路由、权限、下载实现或报告契约。

## 固定提交 CI

中文实施 `d3efcda7478be66289d97dedae0643f2bc5c1a51`：[M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38038955641)、[M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38038955660)、[M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38038955639) 均成功。英文实施 `c286994d506aca41bffd9e0cc8736048f9b8e4ab`：[M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38038962558)、[M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38038962569)、[M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38038962573) 均成功。两次 M1 的 Backend gate 均成功。

TDR-026 和 TDR-023 的 Task 6 补充仍待评审；四类实际质量决定、同一质量输入到 Evidence 的导航、真实规则发布、真机 Evidence、A1–A8 和 Owner 验收仍未完成。
