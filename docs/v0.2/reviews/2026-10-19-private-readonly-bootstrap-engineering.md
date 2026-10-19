# BC01–BC03 私有只读装配隔离测试工程记录

- 日期：2026-10-19；范围：隔离测试与最小 demo 装配，不是实际只读启动、G4 串联或 Owner 验收。
- 依据：[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)及离线 BR01/BR02、BC01–BC03 合同与复核。原说明及缺失引用保持不变。

## 实现

在既有 demo source set 新增 `PrivateReadOnlyBackendBootstrap.kt`，以同一份 44 项合同生成最终阶段属性并验证值、来源、缺席及脱敏，不增加配置文件、依赖、Main 或 JavaExec。生产模块、数据库迁移和既有演示流程不变。

P35 使用实际标量读取及现有 `ManifestValidationTrustPolicy` 对照，拒绝仅能被 Binder 绑定的索引表示、CSV 空项和重复项。profile 在 Boot 准备前建立 T0 记录，在最终检查中同时核对属性与实际 active/default 集合；晚期覆盖不能追认早期选择。

`InspectionContext` 保存本次 Environment 的不可变登记和准备记录。来源按引用同一性核对；overlay 前先检查，防止同名替换抹去不合法来源。Boot 透明视图由受控 attach 捕获；正常框架来源在相应准备位置登记。factory 提前用既有 YAML loader 捕获固定 classpath 资源证据，仅用于核对后续来源内容及 Origin，不添加第二个 Environment 来源。准备后的来源替换或复制仍拒绝。

`ResolvedTlsInput` 要求外部提供的不可变独立引用记录，与既有 helper 的路径及八项属性关联；测试记录为合成材料，不证明实际 R1 批准已经发生。检查器不读取 TLS 文件、创建 Decoder、取得连接或生成批准。最后一次检查与证据写入成功后才委托刷新，已实例化的 DataSource 或 sink 失败均阻止继续。

## 测试与审查

先写 `PrivateReadOnlyBackendBootstrapTest`。首次缺类编译单独留证，不计行为失败。最小可编译骨架的 15 项测试中 14 项断言失败，表明新门禁尚未实现；不是既有生产代码回归。索引反例曾受下层标量干扰，修正同一属性的夹具后重新确认失败，其余输入保持合法。

首轮实现后 15 项通过。独立审查又指出外来 Boot 视图误放行、正常 ApplicationInfo 来源误拒绝及伪造 classpath Origin 误接纳；新增测试连同 CSV 空项/重复项共出现四项真实失败，修复后通过。后续独立复核无剩余阻断发现。该工程复核不替代项目规则的独立评审者批准。

最终中文目标测试 21/21、既有打包回归 7/7 通过，无失败、错误或跳过；测试统一 60 秒超时。覆盖来源冻结、晚期同内容资源复制、独立 profile 反例、独立 TLS 引用缺失/脱节、无连接及证明先于替身刷新。TLS 文件是临时占位 bytes，没有有效证书或实际握手；阶段测试仅调用配置准备方法和不真正刷新的 Context 替身，不运行应用。

本地也尝试完整后端测试：1,253 项中 350 失败、11 跳过。首因是 Testcontainers 无可用 Docker，相关类随后初始化失败；M1/M2 demo 集成测试同样报告 Docker 初始化不可重试。完整日志、按类失败清单和 XML 均保留，不修改或静默跳过这些既有测试。英文目标测试、编译、打包以及固定提交的 M1/M2/M3 CI 和 Pair Gate 最终结果以交付回执为准，不提前宣称通过。

## 边界与下一步

证据目录为 `D:/VSRQG-local-smoke/g4-bc01-bc03-implementation-20261019`。实际 TLS/Decoder/凭据/来源登记、只读会话和端点证明仍缺失。身份绑定保持 `DEFERRED_BY_OWNER`，发布版本、四场景新 Run/Attempt/Evaluation 及正式业务回执未填充。未启用实际配置、启动用户隔离 Backend/Agent、发送业务请求、创建业务 Run、发布规则、授予权限或启用真实 Provider/Company 资源。既有自动测试的临时夹具不视为用户环境事实。

没有 merge、Tag、发布或部署；计划勾选和验收状态不变。TDR-026 仍为 Proposed / REVIEW_REQUIRED，Evidence 窗口及两层完成证据语义不变。

当前结果：BC01–BC03 的隔离边界已有代码与测试证据。Git 状态：配对提交、推送及 CI 以交付回执为准。下一步动作：只读核对纯装配调用者与 R1 实际材料准备的缺口清单。前置条件：实际材料读取和启动须分别授权，现有身份暂缓决定继续有效。验收目标：来源记录、TLS/Decoder 与私有目标可逐项定位，缺失继续显式保留；不据本轮测试授权实际启动或认定 G4/Owner 验收完成。
