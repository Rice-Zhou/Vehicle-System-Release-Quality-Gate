# 最小质量判定 Task 2：严格解析与规范编码

日期：2026-09-17。依据：[实施计划 Task 2](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)、[探针修订](2026-09-15-quality-evaluation-preflight.md)。

## 授权与范围

Owner 在 Task 2 被列为下一步后回复“执行下一步”，据此实施解析与编码段；不代录 TDR Accepted、规则发布或产品验收。Task 1 修复提交 7f7ca0e / b4904ef 的六条 CI 已核实成功，原英文失败记录保留。

本段只提供有界 YAML 语法树、明确失败代码与有类型规范字节。规则 AST、事实目录绑定及求值在 Task 3；发布、数据库、Job、真实输入与重放在后续段。既有 Core、v1 目录与各模块摘要不变。

## 依赖与兼容边界

修改前 dependencyInsight 已解析 org.yaml:snakeyaml:2.5：Jackson YAML 2.21.4 请求 2.5，Spring Boot 3.5.16 的 2.4 软约束已由现有依赖图提升。显式锁定当前解析版本，不升级 Spring Boot/Jackson，不把 Gradle 已成功处理的既有版本选择称为新依赖冲突；新增无法解析的冲突必须停止。此记录不是依赖漏洞评估。

## 边界解释

规则输入按 UTF-8 原始 bytes 限制为 64 KiB；根节点深度为 1，映射 key 同样计入 4096 节点预算。重复 key 按解码后的字符串判断。解析仅产生语法值树，成功不表示 Rule Schema、目录路径或操作符已经合法。

编码格式沿用有类型树，版本标识与 Task 1 一致；对象 key 按 Unicode scalar value 排序，数组保持调用方提供的语义顺序，不在编码器中猜测事实目录排序。输出上限 4 MiB 包含类型包装、转义和 UTF-8 开销。

数值 token、precision、整数位数和绝对 scale 的 4096 上限共同生效；因此可接受数值的最大全展开长度仅为 4099 字符（负号、0. 与 4096 位小数），8192 的展开守卫为额外防护。不能削弱其他限制来制造 8192 的“合法边界”测试；测试可达边界以及被更早拒绝的膨胀输入。

## 验证记录

- TDD：初始 15 项断言失败；追加 YAML/TAG 指令守卫与隐式空值边界后，18 项中的 2 项失败；再修复。日志保存在本地本计划的 .superpowers/sdd 目录。
- 独立复审发现 P2：底层 Resolver 将 08/09 以及超过 1024 字符的旧式数字当成字符串。补充 RED 后移除该隐式分类依赖，采用显式有界 scalar 词法与线性 sexagesimal 检查；1024/1025、长旧式数字、64 KiB 边界、quoted 保留及数字开头业务文本均覆盖。独立 JDK 21 探针复核确认 P2 关闭，无剩余阻断。
- 最终中文回归 27/27 PASS：StrictRuleYamlTest 11、QualityCanonicalEncoderTest 8、ArchitectureTest 6、ApplicationContextTest 2；英文目标测试 19/19 PASS。全部零失败、错误和跳过，双语 assemble 成功；新测试类配置 60 秒超时。
- Node 契约测试 7/7 PASS；全量验证器 schemas=7、positive=20、negative=9、operations=36。未修改既有机器契约或 v1 文件。
- golden bytes 覆盖全部值类型、控制字符、Unicode scalar 排序、无 normalization、精确大整数、负零与尾零；直接构造 20000 层树不发生递归栈溢出，4 MiB 的字节/转义/key 开销按准确上限测试。
- 修改后的 dependencyInsight 确认 strictly 2.5 解析成功，原平台版本不变。YAML/TAG 指令显式拒绝；未知但无语义效果的 YAML 指令由底层忽略，不声称全部指令均拒绝。
- 本段不代表 Operator Matrix、真实输入、数据库恢复、三次质量重放或 Owner 验收通过。最终 Pair Gate、推送与 CI 以固定交付提交核查为准，不提前宣称远端成功。

## 下一步执行计划

当前结果：以本记录的实际验证结果为准。Git 状态：双语配对提交，固定版本由 Git history 定位。下一步动作：Task 2 工程验证完成后实施 Task 3 受限规则求值器。前置条件：本段测试、复审及固定提交 CI 通过。验收目标：操作符矩阵、错误传播、资源上限和两条演示规则 golden tests 通过，不自动发布规则。
