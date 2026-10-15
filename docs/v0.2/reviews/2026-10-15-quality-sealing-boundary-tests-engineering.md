# Quality 封存边界测试补证

- 日期：2026-10-15；性质：目标测试补证，不是 Owner 验收。
- 依据：[封存证据核查](2026-10-15-quality-confirmation-and-sealing-review.md)、[Task 5/6 计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)与 [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)。用户授权补齐两类测试，仅出现真实行为失败时修复；不扩展入口、发布规则或改变验收状态。

## 范围与断言

仅扩展现有 [QualityEvaluationIntegrationTest](../../../backend/src/test/kotlin/com/ricezhou/vsrqg/quality/QualityEvaluationIntegrationTest.kt)，使用真实 PostgreSQL 仓储和原有模拟来源端口；生产代码未修改。

| 测试 | 输入与预期 | 持久化断言 |
| --- | --- | --- |
| Evidence 元数据变化 | 保持相同 Input Digest、Payload size/checksum 和绑定，仅改变 metadataDigest；seal 返回 QUALITY_SOURCE_CHANGED | Snapshot、Rule Results、Quality Result 和完成 Audit/Outbox 均未新增，整个 Job 记录不变；夹具随后显式 fail，原 Evaluation 可查询 ERROR、无固定输入或 Quality Result。 |
| 未重领的 seal 边界 | 首次 claim 后不再 claimNext；299 秒允许，300 秒恰好到期与 301 秒均返回 QUALITY_STALE_LEASE | 允许时一份 Snapshot，拒绝时零 Snapshot；Job 的 attempt、status、时间与摘要均不变；无完成副作用。 |
| 未重领的 complete 边界 | 先在有效 lease 内封存，然后在相同三个时间点提交规则与结果；299 秒完成，300/301 秒拒绝 | 拒绝时原 Snapshot 精确不变，Job 整行不变，Evaluation 仍 RUNNING；Rule Results、Quality Result 和完成 Audit/Outbox 均为零。允许对照可查询原结果，Job 为 SUCCEEDED、attempt 为 1。 |

新增一个普通用例及两个各含三个输入的参数化测试，共七个目标调用；沿用类级 60 秒超时、逐用例独立项目和未完成 Job 清理。删除仓储 Evidence 对象比较会使元数据用例失败；去掉 lease 时间条件或将严格到期比较放宽会使到期用例失败。预期值来自已记录的 300 秒边界，不调用生产比较逻辑生成期望。

## 本地验证与 CI 边界

两条 worktree 的 compileTestKotlin 均成功。中文目标整类 test 的 Testcontainers 静态初始化抛出 Could not find a valid Docker environment，Gradle 报告 18 个初始化失败节点，包含已展开的七个新调用，但业务断言尚未执行。日志与原始 XML 已保存在本机证据目录 `task5-seal-boundary-20261015`；不能把这一环境失败当作修复前红灯，也不能声称七个目标调用在本机通过。两个参数化测试使用分别带操作名称的显示名，以便 CI XML 区分 seal 和 complete 的相同时间输入。

独立只读复审未发现 Critical、Important 或 Minor 问题。新增测试若在隔离 CI 首次直接通过，即为既有行为的补证，不宣称发生了红绿修复；若出现真实业务失败，必须保留输出并诊断后再改代码。准确提交的中英文 M1/M2/M3 与 Pair Gate 尚须在提交后核实，不能以本地编译代替。M1 归档中的目标类 XML 应逐项核对七个新调用，确认零失败、零错误、零跳过。

## 未覆盖范围与治理

来源端口在本类中仍是模拟实现；本补证只证明仓储对已读来源的比较及 Job fencing，不证明正常 API 可修改不可变的 Evidence 元数据，也不证明真实来源存储的并发变化。元数据用例的 ERROR 来自夹具主动 fail，不声称 Worker 自动处理已运行。Payload bytes 校验仍遵循 TDR-026 的事务外窗口，bytes 校验时的事务状态与持锁时长没有新增直接运行证据。

TDR-026 保持 Proposed / REVIEW_REQUIRED；Task 5/6 复合条款和 Owner 验收状态不变。测试规则仅由原隔离夹具在临时数据库构造，不调用规则发布 API，不操作真实 Provider、Company 资源或设备。

当前结果：已补齐 Evidence 元数据变化与未重领 lease 到期的七个目标调用；本地编译通过，数据库行为等待准确提交 CI。Git 状态：以包含本记录的中英文提交及远端核对为准。下一步动作：补齐 Evidence bytes 校验时无活动事务的直接测试证据。前置条件：本轮准确提交的目标用例和 CI 通过；复用隔离环境。验收目标：明确区分来源读取事务、事务外 bytes 校验与封存事务，不重复读取文件或改变窗口语义；工程验证不代替 Owner 验收。
