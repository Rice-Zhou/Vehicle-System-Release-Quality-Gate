# 最小质量判定 Task 5：正式输入与异步评估工程记录

日期：2026-10-08。依据：[实施计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)、[Task 4 工程记录](2026-09-30-quality-task4-engineering.md)。本记录只报告工程证据，不代替 Owner 验收。

## 实施结果

V17 增加 Evaluation、Input Snapshot、Rule Result 和最终 Quality Result 的增量表与不可变约束。`quality:evaluate` 使用 Owner 明确的 Engineer、Quality Owner、Administrator 项目角色，并叠加 JWT scope；`quality:read` 覆盖项目成员。请求只接受 Rule Set、Traceability Snapshot 和一个 Test Run 的正式引用。已发布规则集的 required Issue 与 selected Case、锁定 Manifest、Issue/Traceability 固定快照、终态 Test Run/Result 和 Evidence 模块的元数据及 Payload 校验共同形成固定输入；客户端不能提交 facts。

Job 沿用 PostgreSQL claim、attempt count、lease 与 fencing。Worker 在只读源获取和 Evidence bytes 校验后，于短事务复读源并封闭输入；纯求值后，Rule Results、Quality Result、Audit、Outbox 与 Job 终态原子写入。源失败保留可查询的 ERROR，不生成虚假的最终 Result。查询只读持久化历史，重放由固定输入、规则/目录/引擎/编码版本确定；未知引擎版本拒绝重放。调度默认关闭，需要受控环境显式启用；本轮没有发布真实规则、启用 Company 资源、部署或操作真机。

当前规则发布门禁仍只接受 Task 4 的两条内置演示 YAML。数据库恢复演练使用隔离的 DB+Payload 副本；正式来源读取的正向测试使用模拟的各模块仓储，没有冒充真实设备端到端验收。

## 工程验证

- 单元与契约：正式来源绑定、项目权限、Evidence 跨 Run 拒绝、Worker 失败、三次新 JVM 固定输入重放、未知引擎拒绝；`node scripts/contract-validator.mjs` 为 7/7，schemas=7、positive=22、negative=9、operations=36。
- PostgreSQL CI：V17 迁移/回退恢复、幂等重复请求、唯一最终结果、事务失败回滚、重领和旧 lease 晚写、可查询 ERROR。独立 DB+Payload 副本恢复验证历史结果摘要与当前 Payload 损坏诊断分离。本机无 Docker，数据库结论以固定提交的 CI 为准。
- 固定核心修复提交 `8140fee072aa62200f5334650a73e26f4d44d2d8`（中文）/ `01c5861d79726d9a106b1f32c8e74da1caf4b991`（英文）的 M1/M2/M3 均成功。正式来源事实测试提交 `644b0ca7b16aa7d5b8e4a825bae4e5399647db5b` / `21f7d13fe4b41834b74bc361a51bed2bfe843ec6` 的 M1/M2/M3 也均成功。

| 最终测试样本修正 | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| 中文 `c889cf0` | [37722834666](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37722834666) | [37722834847](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37722834847) | [37722834965](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37722834965) |
| 英文 `b016358` | [37722840061](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37722840061) | [37722839968](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37722839968) | [37722839961](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37722839961) |

最终样本修正提交的中英文六项固定 CI 均为 success；非 Markdown 内容经 Pair Gate 校验一致。

## 正式来源矩阵与 API 边界复核

随后补齐正式来源反例：同 Manifest 摘要的跨 Release Run、错误 Release/摘要的锁定 Manifest、Test Result 绑定其他 Manifest、空 selectedCaseRefs、多 Run、缺 Result、缺 required Issue 与缺 Traceability 引用。既有跨项目 Traceability、跨 Run Evidence 和 appliesWhen=false 测试保留；新增 Evidence 元数据固定后 Payload 摘要不一致的拒绝测试。正向读取所得 Input Snapshot 由仓库使用的 2020-12 JSON Schema 校验器对实际产物验证。相关目标单测和 Kotlin 测试编译在本机通过。

隔离 PostgreSQL 夹具增加正式 HTTP POST → Evaluation QUEUED → GET 历史查询检查；规则版本在临时测试库由夹具插入，不调用真实发布 API。该测试覆盖 API、鉴权、项目作用域和持久化查询的前半段，不覆盖正式来源加 Worker 决策后的完整 HTTP 串联；本机无 Docker，数据库运行结果以固定提交 CI 为准。

| 反例矩阵与 API 夹具提交 | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| 中文 `dc9700a` | [37739784435](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37739784435) | [37739784342](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37739784342) | [37739784331](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37739784331) |
| 英文 `4de90ac` | [37739790841](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37739790841) | [37739790868](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37739790868) | [37739790826](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37739790826) |

反例矩阵提交的中英文六项固定 CI 均为 success；非 Markdown 测试文件经 Pair Gate 校验一致。

## 剩余边界与下一步

正式 API 从新 Release、Traceability Snapshot、终态 Run、已发布演示规则和真实 Evidence 到完成/错误决策及来源导航的受控串联仍未执行。发布规则受当前项目约束禁止，本轮未调用发布端点或变更规则状态；该步骤须等待单独授权和隔离环境。Task 6 的只读展示、真机相关证据和 Owner 验收均保持独立。TDR-026 仍为 Proposed / REVIEW_REQUIRED。

当前结果：Task 5 正式来源反例矩阵与实际快照 schema 投影已在本地验证，隔离 API 队列/查询夹具已通过固定提交 CI；完整正式决策串联未执行。Git 状态：中英文测试提交已推送；本记录更新待双语配对提交。下一步动作：核查本记录双语提交的 CI；在获授权的隔离环境串联正式决策并复审。前置条件：测试提交 CI 成功；规则发布及演示资源须另行授权。验收目标：提供固定来源、失败路径、原子结果、恢复和正式 API 决策的证据，交由 Owner 独立验收。