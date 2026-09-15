# 最小质量判定：设计复核与技术探针

- 日期：2026-09-15；类型：Controller 自复核与隔离技术探针，不是独立复审或 Owner 验收。
- 设计基线：ZH `348ce96` / EN `9739218`。六条准确提交 CI 均为 completed/success：ZH 34968778296、34968778151、34968778167；EN 34968777960、34968777984、34968777964。
- 关联：[设计](../../superpowers/specs/2026-09-15-minimal-quality-evaluation-design.md)、[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)。

## 1. 结论与发现

技术路径可继续规划，但不能把探针当作生产解析器或完整规范编码器。三次独立 JVM 运行各通过 17 项能力检查，输出哈希一致。未安装软件、未修改产品依赖、未运行设备/服务。

| 发现 | 证据 | 处置 |
|---|---|---|
| 事件接口不自动执行全部 LoaderOptions 限制 | allowDuplicateKeys=false 仍返回两个相同 key；alias 限额 0 仍暴露 AliasEvent；深度限制 32 仍产生 33 层事件。 | 在事件流上显式拒绝重复 key、anchor/alias、tag、merge key、多文档；显式计数深度与节点。已补入设计，生产拒绝测试仍待实施。 |
| 字节上限不同于 code point 上限 | 22000 个中文字符形成超过 64 KiB 的 UTF-8 输入。 | 解析前按 bytes 限制，不能仅依赖 LoaderOptions。 |
| 隐式转换必须在对象构造前控制 | 日期原词、quoted boolean 与显式 tag 可从事件区分。 | 禁用通用对象构造；自有受限 scalar grammar + schema/目录类型绑定，错误显式拒绝。 |
| 数值编码基础可行，完整格式尚须 golden tests | 大于 2^53 的相邻整数不同；高精度小数无舍入；尾零/负零归一；INTEGER 与 DECIMAL 标签区分。 | 固定有类型树格式、字符串与 key 排序规则；增加 exponent/展开长度限制，避免 toPlainString 资源膨胀。 |
| 目录与规则政策仍需审核 | v1 数字置信度与当前等级事实不一致，required 来源是新规则集政策。 | 保持 TDR Proposed；不以通过探针代替对目录 v2、required 来源和 Case 动作的接受。 |

## 2. 环境与可核对证据

使用已有 Temurin JDK 21.0.7+6 与 Gradle 缓存中的 SnakeYAML 2.5，调用 Yaml.parse 获取事件；未宣称该版本是当前最新或已完成漏洞评估。候选实施依赖锁定为 org.yaml:snakeyaml:2.5，产品接入时需显式声明并核对 BOM 冲突，不依赖偶然的传递依赖。

受控本地根为 `D:/VSRQG-local-smoke/quality-preflight-20260915`。QualityProbe.java 是一次性探针，不属于产品实现。run-1.log、run-2.log、run-3.log 保留每项 PASS；当前仅本地留存，不保证跨主机可复现访问。

| 材料 | SHA-256 |
|---|---|
| snakeyaml-2.5.jar | e6682acf1ace77508ef13649cbf4f8d09d2cf5457bdb61d25ffb6ac0233d78dd |
| QualityProbe.java | 43a170856bb11ceaa6d3b0e1be249dbfe61fbe632e0c85d09427bc08165a7550 |
| 每份 run 日志 | 261ead5650996ce9fc98cd2f8e056c64a39edf11abb5e7b3475097bb78e9dd42 |
| UTF-8 数值样例 ["DECIMAL","1.23"] | 02ebce75b419424adaded408749ac8e5949417c22bab801dd7338be1cd7b7341 |

探针覆盖：单文档、重复 key 可见、anchor、alias、tag、多文档、plain 日期、quoted boolean、merge key、33 层事件、UTF-8 bytes、相邻大整数、小数尾零、负零、高精度小数、数字类型区分、固定数值 bytes。它证明接口信息与基础数值操作可用，不证明最终拒绝器、全编码器、全部操作符或 Quality Result 三次重放已经实现。

## 3. 设计修订的精确边界

事件流检查必须在任何通用构造器前完成。Mapping 使用解码后的标量字符串 key 做重复检查，拒绝非字符串/复合 key 与 merge key；集合栈独立维护上下文。不允许递归对象构造、tag 实例化或先构造再校验。字符串中看起来像日期的 quoted 文本可保留；plain 歧义 scalar 拒绝，true/false/null 与数字只按受限 JSON 风格词法解释。

规范树拟固定为 ["TYPE", value]；OBJECT 的 value 为按 Unicode scalar value 顺序排列的 [key, typedNode] 数组，ARRAY 保持目录语义顺序，INTEGER/DECIMAL 的 value 为规范十进制字符串，STRING 为原字符串，BOOLEAN 为布尔值，NULL 为 null。使用无空白 UTF-8 JSON，只有 quote、反斜杠及控制字符转义；控制字符统一小写四位 Unicode escape，禁止未配对 surrogate，不做 Unicode normalization。每类节点有正反 golden bytes，不能只凭数值样例验收整体编码。

在 BigDecimal 展开前检查输入 token 长度不超过 4096、precision 不超过 4096、绝对 scale 不超过 4096、预计展开字符数不超过 8192；大整数同样限制 4096 位。超限为 ERROR，不截断或使用浮点 fallback。以上是新增待验证资源限制，探针未覆盖其生产拒绝实现。

## 4. 后续出口

当前结果：候选解析接口与基础精确数值可行，设计限制已补齐；生产守卫、完整编码和政策接受尚未完成。Git 状态：复核与设计修订按双语提交，固定提交由 Git history 定位。下一步动作：基于修订设计制定分段实施计划。前置条件：Owner 接受 TDR-026 的目录衔接与规则政策；若涉及冻结变更先 ADR。验收目标：计划覆盖 A1–A8，并将事件守卫、完整编码 golden tests、BOM 校验及源事实绑定列为首段检查；不提前宣称工程验收通过。
