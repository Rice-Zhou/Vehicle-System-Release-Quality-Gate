# Traceability v1 使用方盘点

## 可重放命令

```powershell
rg -n "TraceabilityFactBatch|TraceabilityFactInput|facts:ingest" . --glob '!docs/superpowers/**'
```

## 结果

2026-09-03 在本工作树执行该命令。命中项仅属于以下允许类别：

- OpenAPI Contract、compatibility baseline 与 `M2ApiContractTest`；测试中的 `TraceabilityFactInput` 断言用于证明 v1 已被 supersede，并非调用方。
- 已有书面设计、治理验收记录、TDR-017 与本盘点记录；这些内容保存 v1 pre-release 的历史和迁移决策。
- 已冻结 Endpoint `/api/v1/traceability/facts:ingest` 的设计文档引用；该 Endpoint 保持不变，不代表 v1 request body 使用方。

未命中 Controller、Application、Adapter、fixture、script 或 workflow 等真实 v1 consumer。因此可在同一 Contract Subject 中以 `BuildProvenanceEnvelope schemaVersion: 2` supersede 未实施且无消费者的 `TraceabilityFactBatch`/`TraceabilityFactInput` 草案；Path、Method、Permission、`Idempotency-Key` 与 `serviceOauth` 保持不变。

若未来命令发现任何真实 v1 consumer，必须停止 supersession、保留 v1，并重新打开 TDR-017 compatibility review。
