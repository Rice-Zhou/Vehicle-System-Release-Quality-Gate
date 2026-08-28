# Company 环境验收延期决定

- **决定时间**：`2026-08-28T05:46:26Z`
- **决定人**：Project Owner
- **适用范围**：`V0-2-PILOT-COMPANY-002`、`V0-2-EVIDENCE-ARCHIVE-001`、`M1-OWNER-GATE-001`
- **决定**：公司当前无法提供真实对象存储、独立 Provider 身份及相应运维支持，因此真实 Company 环境验收延期；项目继续以 `PILOT` 模式推进。

## 边界

- 本决定只延期真实 Company 基础设施验收，不删除或弱化既有 Company 配置契约、Adapter、失败关闭控制、自动化测试或恢复工作包。
- 当前不得创建 `V0-2-EVIDENCE-ARCHIVE-001` 正式验收记录，不得关闭 `V0-2-PILOT-COMPANY-002` 或 `M1-OWNER-GATE-001` 的 Company 条件。
- Pilot/CI `TEST_FIXTURE` 和本地保全副本只能证明工具链或临时保全事实，不能证明 Company Provider、Object Lock、retention、独立恢复或 production readiness。
- 不得通过把控制项设为 `true`、使用共享身份、使用可变本地目录或降低验证要求来模拟 Company 验收成功。
- 本决定不授权 merge、Tag、release 或 production deployment，也不改变 V0.1 冻结架构。

## 恢复触发条件

只有同时具备以下条件时，才重新启动真实 Company 验收：

1. 公司提供受控对象存储，并能验证加密、私有访问、Versioning、`COMPLIANCE` Object Lock 和正 retention；
2. 公司提供两个不同的 Provider-attested 身份，分别承担归档与独立恢复；
3. 公司明确 `accessOwner`、凭据托管、网络访问和操作责任边界；
4. Project Owner 对当次真实外部写入单独授权；
5. 执行环境能够保存归档报告、独立恢复报告、离线校验结果和不可变 Evidence locator。

恢复后必须按既有运行手册完成真实归档、精确 `versionId` 恢复和离线交叉校验，再由 Project Owner 通过独立提交决定相关 Gate 的 `APPROVE` 或 `REJECT`。延期期间不设置一个无法由当前团队控制的日历截止时间；生产部署前置条件保持不变。

## 当前执行方向

项目继续完成不依赖公司基础设施的 Pilot 实现、自动化测试、文档和可重放 Gate。Company 能力只能表述为“实施设计和可测试实现已具备，真实环境验收延期”，不得表述为 `Company Ready`。
