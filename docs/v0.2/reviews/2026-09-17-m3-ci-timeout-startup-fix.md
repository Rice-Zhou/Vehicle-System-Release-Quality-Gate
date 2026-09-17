# M3 CI 超时测试启动预算修复

日期：2026-09-17。范围：测试夹具与断言，不改变生产进程控制。

## 失败与修复

Quality Task 1 Subject 为 97600e6 / 8a5b8a3。英文 [M3 CI 35174638386](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/35174638386) 在 m3-demo.tests.ps1 读取 owned.pid 时失败；APK 构建成功，后续 Backend/Agent 步骤跳过。中文 M3 35174638109 成功。

原测试只给 PowerShell 启动与执行 1 秒，却假定夹具已写 PID。加入延迟 2 秒写 PID 的夹具后，本地复现相同失败，退出码 1。测试现使用 10 秒执行预算和 12 秒清理/调度余量，检查超时下限与上限、PID 存在、所属进程退出和无关进程存活。未初始化明确失败，不跳过断言。生产 helper、默认超时和 API 保持不变，无新增技术选型。

## 验证与边界

中文及英文工作区完整 wrapper 测试均 14/14 PASS，退出码 0。覆盖配置拒绝、构建/子进程失败、报告缺失、摘要错误、超时清理、进程隔离、非零退出传播和输出上限。Windows 本地结果不替代修复提交的 Linux CI。

原 CI 失败保留可追溯，新修复不代录 Owner 验收；未启动 Quality Task 2、设备、Company Provider 或部署。

## 下一步执行计划

当前结果：已定位并修正测试启动预算。Git 状态：本记录与测试按双语提交，版本由 Git history 定位。下一步动作：核对修复提交六条 CI。前置条件：GitHub CI 完成。验收目标：六条 CI 成功，尤其 Linux 延迟启动、超时清理和进程隔离通过，再进入 Quality Task 2。
