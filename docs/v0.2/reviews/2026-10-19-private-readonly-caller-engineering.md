# H01–H05 私有只读调用者隔离测试工程记录

- 日期：2026-10-19；范围：合成材料、空 Context/替身和最小可调用装配，不是实际材料准备、Backend 启动或 Owner 验收。
- 依据：[装配工程记录](2026-10-19-private-readonly-bootstrap-engineering.md)、[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)及已确认的仓库外合同。原说明与缺失引用不改写。

## 实现

PrivateReadOnlyCaller 接受 ReadOnlyHandoff、材料 resolver、ReadOnlyProofFile 和非业务观测回调。15 项前置引用缺失或空白时，在解析材料之前拒绝；解析后精确绑定 tlsIndependentRecord 与 TlsReferenceEvidence.recordReference。这只校验交接完整性和引用同一性，不生成批准或认证外部记录。运行时证明、连接观测、握手和关闭回执不作自身准入条件，也不要求人类账号、发布规则或新 Attempt。

ReadOnlyMaterialReferences 只解析显式交接的数据库/TLS 描述与验证公钥。数据库描述解析从原 M3 流程抽出共享；DemoJwtDecoder 共享原签名、issuer、audience、时间及必需声明校验链，旧身份实现也调用它。新路径不造签名身份、不签 Token、不调用全量 M3 配置或旧演示入口。

工厂仍唯一实现 44 项属性/来源校验。文件 sink 保存现有 ReadOnlyStartupProof 的 40 项输出，四项凭据/口令整项省略，TLS 路径仅保留匹配标记。沿用私有路径边界，以 CREATE_NEW 同步写入并成功关闭后才返回；旧目标不覆盖，写入/关闭失败阻止刷新，不新增第二属性验证器。

调用者在本次 Context 创建时捕获归属。Spring 默认销毁可能只记录池关闭错误，因此在实际 merged definition 禁用该池的推断销毁，实例暴露后、属性填充前捕获池，最后显式关闭池并继续关闭 Context。运行、池与 Context 的错误均保留；多个不同池使装配失败，但已捕获池仍被清理。没有第二池、连接探测或外部进程操作。

## 测试与审查

先写测试；缺少 API 的编译失败单独保存，不计行为失败。可编译骨架出现 13 项失败，覆盖引用绕过、未实现材料/Decoder、空 sink 和清理缺失。修正测试替身中与 Spring 方法重名的回调后重现失败；只有测试反射传输解包 checked IO 异常，产品代码不吞错。

独立只读审查指出两项 Important：TLS 记录交接失配、Spring/Boot 提前关闭丢失真实池错误。新增五个反例先失败再修复；另补属性填充失败与三层异常保留。最终调用者测试 20 项、既有装配测试 21 项、打包回归 7 项，中英文各 48 项通过，无失败、错误或跳过；测试超时为 60 秒，受影响 demo/test 编译成功。

新测试仅使用临时合成描述、口令哨兵、测试签名/公钥及不可连接 DataSource，运行替身或空 Spring Context，不运行调用者默认启动函数。既有自动测试的临时 Web 夹具不视为用户环境启动。生产 JAR 和组件扫描排除沿用既有检查，无新 Main、JavaExec、profile 或依赖。

本地完整后端测试也尝试执行，出现 350 项失败、11 项跳过；首因及连带初始化错误为 Testcontainers 无可用 Docker。完整结果和按类失败清单保留，不宣称本地全套通过。修复后的完整英文结果及固定提交的中英文 M1/M2/M3 CI、Pair Gate 以交付回执为准，不提前填写成功。

## 保证边界

池归属从构造完成并暴露给受控 Spring 创建链时开始，不承诺任意构造函数内部尚未返回的资源、任意外部 shutdown listener 或任意自定义销毁机制。关闭调用失败可见，但正常返回不等于真机、线程或全部连接终止已被独立观测；非受控扩展需另行评审。

实际材料、独立批准、ACL、私有根归属仍由 R1 外部权威核对；真实 TLS、副本身份与池会话只读仍未验证。不承诺 WORM、fsync 或文件系统与数据库原子性。修复由反例和回归验证，不声称再次独立审查；工程审查不替代规则评审者或 Owner 验收。

## 状态与下一步

证据目录：D:/VSRQG-local-smoke/g4-h01-h05-implementation-20261019。原合同的 19 项实际引用、身份、发布版本和新 Run/Attempt 继续显式缺失；身份绑定仍 DEFERRED_BY_OWNER。代码存在不等于实际交接已绑定。

未读取实际敏感材料、启用用户配置、启动用户私有 Backend/Agent、发送业务请求、创建业务 Run、发布规则、授予权限或启用真实 Provider/Company 资源。未 merge、Tag、发布或部署；计划勾选和验收不变，TDR-026 仍 Proposed / REVIEW_REQUIRED。

当前结果：调用者、文件 sink 与生命周期已有隔离反例和回归。Git 状态：配对提交推送和固定提交 CI 以交付回执为准。下一步动作：另行授权后准备并核对 R1 受控材料包。前置条件：明确材料读取范围、受控来源及独立只读数据库核查授权，实际启动另行授权。验收目标：独立记录匹配实际材料引用，私有目标具备当前证明，缺失不回填，不据工程测试提升验收状态。
