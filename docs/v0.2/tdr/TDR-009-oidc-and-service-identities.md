# TDR-009 — OIDC for Users and Separate Service Identities

- 状态：Proposed for V0.2 Review
- 范围：人员、CI、Adapter、Agent 认证

## 问题与需求

约 300 人公司需要统一人员身份、离职/撤权、角色、审计；CI、Adapter 和 Agent 又不能共享人员凭证。项目无必要也无资源自建安全身份系统。

## 决策与理由

用户通过公司 OIDC/OAuth 2.1 登录；CI/Adapter 使用独立 service account 和短期凭证；Agent 使用 mTLS 或短期 client identity。应用将外部 subject 映射到本地 RBAC/project scope，Secret 仅保存在 Secret Manager。

## 未选方案

- 自建用户名密码：安全、合规、生命周期和运维成本高。
- 共享 API key：无法区分主体、撤销和审计。
- 仅网络白名单：不是身份，不能满足操作权限。
- 把 token 存 Manifest/配置库：违反安全边界。

## V0.2 / V0.3 影响

V0.2 依赖公司 IdP，但获得统一治理。V0.3 可增加 group sync、细粒度项目策略和更强 workload identity，不改变 principal/permission 模型。

## 迁移与回滚

通过 issuer+subject 保持稳定映射；更换 IdP 使用受控 identity mapping 和双 issuer 过渡。认证集成失败回滚应用；绝不回退到匿名或默认 Admin。

## 测试、部署与恢复

测试 issuer/audience/expiry/signature、撤销、权限矩阵和跨项目访问。部署时从 Secret Manager 注入 client 配置。IdP 故障时 fail closed；break-glass 必须由公司流程管理并强审计。

## 重新评估条件

公司无 OIDC 能力、合规要求改变或需要设备规模化证书生命周期平台。
