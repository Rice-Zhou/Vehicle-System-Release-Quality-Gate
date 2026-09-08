# M1 合成演示操作说明

本入口演示实际文件 → Release → Manifest 校验 → Lock → 导出的机械闭环，并执行损坏文件、无身份、权限拒绝及历史重放检查。它不是完整 MVP、真实车辆测试或 Company 部署。

## 运行前准备

在仓库根目录使用已有 PowerShell 7、Git、JDK 21 和可用的本机 Docker Compose。JAVA_HOME 应指向已有 JDK 21；入口不会安装软件或创建云资源。端口 55432 用于独立演示数据库。

沿用仓库已有 Gradle Wrapper；首次构建可能按现有配置下载 Gradle 发行包和构建依赖，需要正常的依赖仓库访问能力。

首次及复用运行都需要一份仓库外的演示数据库口令。保存于自己的口令管理工具，复用保留数据时提供相同值；不要将口令写入仓库、脚本、日志或提交消息。脚本不会生成并丢弃口令，也不会因口令不匹配删除数据。

可在当前 PowerShell 会话通过隐藏输入提供口令：

```powershell
$demoCredential = [pscredential]::new('vsrqg_demo', (Read-Host 'Demo database password' -AsSecureString))
$env:VSRQG_DEMO_DATABASE_PASSWORD = $demoCredential.GetNetworkCredential().Password
```

该环境变量只供当前会话启动的子进程使用。入口固定使用 Compose project vsrqg-m1-demo、数据库/用户 vsrqg_demo、loopback 端口 55432；不使用 Company 或真实 Provider 配置。

## 单命令运行

```powershell
pwsh -NoProfile -File scripts/demo/run-m1.ps1
```

成功退出码为 0，失败为非零；不要仅根据目录或文件存在判断成功。可以通过下述命令读取最近报告：

```powershell
Get-ChildItem backend/build/demo/m1 -Filter summary.json -Recurse |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1 |
    Get-Content
```

## 如何理解结果

每次独立运行写入 backend/build/demo/m1/<runId>/。summary.json 包含 SYNTHETIC_DEMO、运行 ID、代码提交、workingTreeDirty、实际场景状态、HTTP 状态、Release/Manifest ID、摘要及固定错误码。manifest.json 是正常场景实际提交的 Manifest。报告不包含 Token、私钥、口令、原始身份或连接信息。

正常总结果应为 PASS。损坏文件和权限负例的场景 PASS 表示系统按预期拒绝请求；它们对应的 HTTP 状态仍为 422/409、401 或 403。FAILED 表示本轮未完成预期流程；尚未启动 JVM 时，报告只有最小失败元数据，不会伪造业务 ID 或场景成功。

workingTreeDirty=true 表示存在相关未提交修改；codeCommit 此时只标识 HEAD，不能证明这些修改的字节。固定源样例 demo/m1/sample-config.txt 保持不变，实际损坏发生在本轮工作副本。注册后的文件变化不会改写已有历史报告。

## 服务、数据与复用

入口只停止本次从停止状态启动的演示服务，保留 volume 和报告。执行前已经运行的演示服务保持运行；重复执行产生新的合成项目、Release 和报告，不覆盖旧记录。数据库或口令错误会非零退出，不能通过换口令、删 volume 或静默重试伪造成功。

可查看这个独立项目的容器：

```powershell
docker ps -a --filter label=com.docker.compose.project=vsrqg-m1-demo --format '{{.ID}} {{.Status}}'
```

需要手动停止既有服务时，确认上述容器 ID 后使用 docker stop；停止不会删除 volume。不要删除 volume 来绕过未知口令，先从自己的仓库外保存位置找回原口令。

## 验证与边界

无容器的脚本测试入口为 scripts/tests/m1-demo.tests.ps1。现有 CI 另外运行 scripts/tests/m1-demo-ci.tests.ps1，实际验证首次运行、保留 volume 后复用、既有运行服务不被停止、错误口令失败及源文件保留，并上传 summary.json/manifest.json。

[TDR-021](../v0.2/tdr/TDR-021-local-m1-demonstration.md)定义实施边界；最终结果与提交证据见[任务 3 实施记录](2026-09-08-reproducible-m1-walkthrough.md)。本说明不替代 Owner 验收。

Owner 可直接按[审阅记录](../governance/acceptance/records/2026-09-08-tdr-021-m1-demo-review-001.md)检查文件闭环、拒绝行为、重复运行和展示范围；Owner 已确认接受固定 Subject，记录为 APPROVE。
