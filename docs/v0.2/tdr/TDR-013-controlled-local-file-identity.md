# TDR-013 — 受控本地文件身份与 Windows 参数桥

- 状态：Accepted-for-V0.2
- 日期：2026-08-28
- 决策依据：Evidence Archive Windows 实机参数探针与本地文件身份失败分析
- 范围：Evidence Archive 单次运维进程的本地输入、暂存、报告发布和 JVM 参数传递
- 相关决定：[TDR-004](TDR-004-s3-compatible-evidence-storage.md)、[TDR-009](TDR-009-oidc-and-service-identities.md)、[TDR-012](TDR-012-evidence-archive-acceptance-operations.md)

## 1. 为什么选择该技术

V0.2 采用按文件系统能力分层的本地身份策略。Linux/POSIX 目录仍要求非共享可写且每个受控对象具有非空 `fileKey`；Windows 等非 POSIX Provider 在 `fileKey` 不可用时，只有目录满足 Operator-controlled ACL 与单一受信写者约束，才允许使用规范 real path、creation time、last-modified time、size 和对象类型构成的 metadata 身份。目录与文件使用各自适用且在操作阶段稳定的字段，暂存文件每次由受信 channel 写入后刷新预期 metadata，再在发布或清理前复核。

Windows 的 JVM invocation 使用 `VSRQG_EVIDENCE_OPERATION_*` 专用非秘密环境变量桥。Gradle 只接受 `archive` 或 `verify` 的完整精确变量集合，并用 `args(listOf(...))` 把每个值作为单独 argv token 传入；未知、空白或部分组合以固定错误失败。未启用该桥时保留既有 `--args` 兼容入口。

## 2. 解决什么问题

Windows NIO 的 `BasicFileAttributes.fileKey()` 在本项目支持的环境中返回 `null`，导致 canonical `{}` 工作包在实际 JVM 读取前被错误归类为 `WORK_PACKAGE_READ_FAILED`；同时 `gradlew.bat` 会二次解释带空格的嵌套引号，使路径可能被拆成 Gradle Task。该决定使受控 Windows 目录中的稳定读取可以进入 schema 校验并精确返回 `ARCHIVE_INPUT_FAILURE`，且不把本地路径、Provider 环境或 credential 打印到 stdout/stderr。

该决定只处理本地文件系统与进程启动边界，不改变 V0.1 的 Core Contract、Release-centric architecture、Manifest authority、Evidence、Traceability、Deterministic Quality Engine、Adapter、Plugin 或 ADR 治理。

## 3. 为什么没有选择其他方案

- 强制 Windows 提供 `fileKey`：目标 NIO Provider 无法满足，会让受控 Company 操作无法在已批准平台运行。
- 仅比较路径：无法检测常见替换或 metadata 变化，不能满足 fail-closed 重校验要求。
- 关闭身份校验或使用普通 `Files.readAllBytes`：会删除 NOFOLLOW、边界读取和 pre/open/post 重校验，不接受。
- 继续拼接 `--args` 引号：已由含空格实机探针证明不可靠，不应把 shell quoting 当作安全契约。
- 新增常驻服务、数据库或容器包装器：当前单次受控 operation 不需要新的部署与故障面。

## 4. 对 V0.2 的影响

本地读取继续执行绝对规范路径、NOFOLLOW、bounded read、零进度失败、parent pre/open/post 重校验，以及文件 size/time/type 的 before/after 校验。POSIX 缺失 `fileKey` 继续 fail closed。非 POSIX metadata 回退依赖 Operator-controlled ACL 与单一写者，不声称抵御拥有该受信写权限的恶意进程实施 A-B-A 替换；因此共享目录、多写者目录和不受控临时目录不在支持范围内。

Gradle bridge 变量仅承载非秘密路径和 mode，必须配合 `--no-daemon` 使用，完成后只清理本任务变量。Provider 配置与 credential 仍来自既有 repository-external identity chain，不能通过 bridge 传入。

## 5. 对未来 V0.3 的影响

V0.3 若引入受控作业平台，可直接传递 argv 或以相同精确 schema 注入环境变量，而不改变 operation CLI。若公司要求抵御具有本地受信写权限的恶意 writer，应改用具备稳定 handle identity 的平台原语、隔离执行沙箱或签名不可变输入；这属于新的威胁模型，需要新 TDR，不能把当前 metadata 回退描述为等价防护。

Company S3 Object Lock、exact `versionId`、receipt digest 与 Provider protection 是远端长期 Evidence 的独立保证，不依赖本地文件身份实现，未来迁移不得削弱这些控制。

## 6. 如何迁移

现有 POSIX 执行无需改变身份策略。Windows 运行手册从嵌套 `--args` 改为设置完整 `VSRQG_EVIDENCE_OPERATION_*` 组合，调用 `evidenceArchiveOperation --no-daemon`，再在 `finally` 中清理。已有自动化若安全使用旧 `--args` 可暂时保留；迁移完成后应优先统一到精确变量桥。

不得迁移或覆盖既有 archive/recovery report、completion marker 或远端对象版本。失败重试使用新的受信输出目录和新 execution ID，并保留源文件与已提交 exact version 供对账。

## 7. 如何测试

单元测试覆盖 Operator-controlled ACL 下 null `fileKey` 的稳定读取、文件 metadata 变化、父目录身份变化、POSIX null `fileKey` fail-closed，以及既有 symlink、size bound、EOF 和零进度行为。归档与恢复测试覆盖 partial 写入后身份刷新、发布所有权和清理所有权。

Windows 实机探针在含空格临时目录创建 canonical `{}` 无效工作包，隔离 `VSRQG_*`、`AWS_*`、profile、web identity 与 EC2 metadata，分别运行 archive/verify 两次。每次必须原生 exit `1`、精确输出 `ARCHIVE_INPUT_FAILURE`，不得出现 `READ_FAILED`、`USAGE_ERROR`、Gradle Task 误解析、路径或 Provider 环境泄露，也不得创建报告、恢复文件或 marker。不完整与未知 bridge 组合必须以固定 `EVIDENCE_OPERATION_ENV_INVALID` 失败且不打印值。

## 8. 如何部署

不新增服务、端口、数据库、消息系统或镜像。bridge 随现有 Gradle operation task 交付；运行节点必须使用 Java 21、受控仓库 checkout、Owner 管理的单写目录和 repository-external Provider identity。Windows M1 自动运行实机探针；非 Windows Runner 明确记录 `NOT_APPLICABLE`，不得声称验证了 `gradlew.bat` 行为。

日志和验收记录只能保存 canonical safe JSON、fingerprint、Git locator、digest 与 exact object ref，不保存本地绝对路径、原始 principal、环境变量值或 credential。

## 9. 失败时如何恢复

本地身份、parent 重校验、bridge 组合或 argv 解析失败时停止 operation，保留源和已提交远端版本，不创建 completion marker，不把失败改写为成功。若 partial 所有权不能确认，宁可保留并隔离，也不得删除可能属于其他 writer 的文件。修复 ACL、目录所有权或启动配置后，在新的受信输出目录以新 execution ID 重试。

若怀疑单写约束被破坏，立即隔离本地工作目录并重新取得权威源；若怀疑 credential 泄露，交由外部安全流程撤销和替换。任何本地恢复都不得删除 Company S3 Object Lock 版本或降低 retention。

## 重新评估条件

当 Windows Provider 能稳定提供 handle/file identity、公司威胁模型要求抵御受信 writer、运行平台禁止环境变量 bridge，或 V0.3 引入受控作业平台时重新评估。重新评估不得静默修改 V0.1 冻结架构。
