# 离线报告合成样例

来自已获 Owner 批准的 [M2 演示](../../../docs/governance/acceptance/records/2026-09-09-tdr-022-m2-demo-review-001.md)，不是人工补写的成功结果。

- CI run：34307583566；Artifact：10087409439。
- 原 ZIP SHA-256：1490d7979dcac9815bdbaadeaed832be1a3c401ff905f5a385e63dfceb1d65b4。
- codeCommit：8d5354dcf21ae7b506b27f56eae4d044b9beb895；workingTreeDirty=false。
- runId：3b6697c8-0e88-452f-9b87-f5ee43bfd4ef。
- 从已核验本地 ZIP 选取同次成功 IncludeM2 运行，三份文件保持原字节；以下摘要只记录文件来源与复制完整性，不是新的业务摘要权威。

| 文件 | 原 ZIP 成员 | Bytes | SHA-256 |
|---|---|---|---|
| summary.json | 3b6697c8-0e88-452f-9b87-f5ee43bfd4ef/summary.json | 1310 | 76e1d6ee2992e8f8ed3dc06349494180aff7d53da27ea11c5b4e6d44042f4edf |
| manifest.json | 3b6697c8-0e88-452f-9b87-f5ee43bfd4ef/manifest.json | 603 | f7eb22fc375d794837a54bd112402d9c7d61bfa169d182e4774af4a6a39498b8 |
| m2-summary.json | 3b6697c8-0e88-452f-9b87-f5ee43bfd4ef/m2-summary.json | 4885 | 593bf2688b4473976911f7db564583512713adb5da525ec9addc913c99a02e2e |

样例仅含合成业务标识与受控输出，无 Token、密码、连接信息或原始公司数据。manifest.json 是注册输入，不能仅凭文件存在认定 Lock/导出；应阅读 summary 的场景结果。全部 Verified=false，SYNTHETIC_FIXTURE 不是实际 GitHub Build 或真实车辆证明。

原 CI Artifact 有保留期限；这里的三个 JSON 已作为项目材料随 Git 保存。报告生成方式见[操作说明](../../../docs/demo/offline-report-runbook.md)。
