# 离线演示报告操作说明

从一份明确的已有 M1/M2 运行目录生成中英文 HTML，浏览器打开后集中阅读 Release、Manifest、Issue、Gap 和历史。它呈现已有记录，不重新执行校验或生成 Release 质量结论。

## 生成

生成使用已有 Node（本地验证环境为 v20.14.0，CI 为 Node 24），无需安装依赖、启动 Backend、数据库或 Docker。先准备输出父目录，使用尚不存在的文件名；已有输出不会覆盖。

在仓库根目录运行下列命令。demo/report/sample 是已保存的[同次 CI 合成样例](../../demo/report/sample/README.md)，backend/build 必须已存在；若报告已存在，为 --output 指定另一个新文件名。

```powershell
node scripts/demo/render-report.mjs --run-dir demo/report/sample --output backend/build/report.zh.html --scope m2 --language zh
node scripts/demo/render-report.mjs --run-dir demo/report/sample --output backend/build/report.en.html --scope m2 --language en
```

也可将 --run-dir 指向自己明确选择的 backend/build/demo/m1/<runId> 或已解压 CI Artifact 中的一个运行目录；不要把不同运行的文件拼在一起。--scope m2 需要同次 M1/M2，--scope m1 只显示 M1 并且不读取 M2 文件；不自动选“最新”，不静默切换范围。所有四个参数必须显式给出。

## 打开与解释

用已有浏览器打开生成的 HTML，打开阶段不需要 Node、数据库或网络。两个语言文件的业务值相同，说明文案不同；原始 ID、枚举、HTTP 状态和错误码不翻译。详情可展开，窄屏表格可横向滚动。

REPORT_RENDERED 与退出码 0 只表示报告成功生成；页面中的源 M1/M2 status 仍可为 FAILED。它们不表示整个演示、Owner 验收或 Release Gate 通过。负向场景 PASS 表示按预期拒绝；NOT_RUN/RUNNING 和缺失数据仍可见，不补写成功。

SYNTHETIC_DEMO / SYNTHETIC_FIXTURE 与 Verified=false 表明这是合成追溯演示。manifest.json 是注册输入，Lock/导出结论来自源 summary 的场景状态；history.snapshotABytesStable 引用源运行检查，不是重新验证。报告保留源 runId/codeCommit/workingTreeDirty，dirty=true 或 commit 未提供时不能把该 commit 当作全部输入字节的证明。

## 失败与文件保留

| 情况 | 结果 |
|---|---|
| 最小或部分 FAILED 源记录 | 可以生成失败报告；业务 ID/文件尚未产生时明确缺失，不猜测失败原因 |
| PASS 源记录缺少所需文件、非法 JSON/类型、超过每文件 1 MiB、符号链接或非普通文件 | REPORT_INPUT_INVALID，非零，不生成 HTML |
| 运行/代码/dirty/Release/Manifest 关联不符 | REPORT_INPUT_MISMATCH，非零，不拼接数据 |
| 参数缺失、重复、未知或范围/语言错误 | REPORT_ARGUMENT_INVALID，非零 |
| 输出已存在、父目录不存在、输出写失败 | REPORT_OUTPUT_FAILED，非零，不覆盖原文件 |

源 JSON 不修改；失败后先修正文件选择或明确输入问题，不通过删数据库/旧结果制造成功。诊断只包含固定错误码，不输出原始输入、凭据、异常正文或绝对路径。报告只显示既有白名单字段，未知扩展字段不展示。

## CI 与来源

现有 M1 CI 在实际演示之后为每个 summary 目录生成 zh/en；其受控样例目录中有 m2-summary.json 才选择 m2，否则选择 m1。报告生成错误会令步骤失败，原 demo gate 结果不会被覆盖。HTML 与原 JSON 一起存入既有 m1-demo Artifact，保留 30 天。

Git 保存的样例不依赖原 CI Artifact 永久在线；其他运行结果仍应按既有 GitHub 资料治理保存所需合成材料或明确标记不可用。无需新增 Company 或云归档环境。

技术边界见 [TDR-023](../v0.2/tdr/TDR-023-offline-demo-report.md)，实际工程验证见[验证记录](2026-09-09-offline-report-verification.md)。此入口不授权 merge、Tag、发布、部署或真实 Provider。
