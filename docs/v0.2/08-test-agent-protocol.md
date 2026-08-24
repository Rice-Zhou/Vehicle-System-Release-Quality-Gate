# 08 — Test Agent Protocol

## 1. Choice and Boundary

Agent uses Agent-initiated HTTPS registration, heartbeat, and long polling to claim work. Evidence uploads directly to object storage through presigned URLs. See [TDR-006](tdr/TDR-006-agent-pull-protocol.md) for the rationale.

The protocol defines observable behavior, not a specific HTTP library, thread, or process structure. Agent executes and collects; it does not own Release, Manifest, or Quality decisions.

## 2. Identity and Version Negotiation

Initial Agent configuration contains a non-plaintext bootstrap identity reference. After registration it uses short-lived client credentials or mTLS identity. The registration request includes agentVersion, protocolVersions, device reference, capabilities, and collector versions.

Server returns selected protocolVersion, agentId, heartbeat interval, lease policy, and minimum supported version. If there is no common version, return `426 AGENT_PROTOCOL_UNSUPPORTED` and dispatch no work.

## 3. Endpoints

| Method | Endpoint | Behavior |
|---|---|---|
| POST | `/agent-api/v1/agents:register` | Register/idempotently recover Agent |
| POST | `/agent-api/v1/agents/{id}:heartbeat` | Report state, Device, Capability, and active Command summary |
| POST | `/agent-api/v1/agents/{id}/commands:poll` | Long-poll one or a small batch of Commands |
| POST | `/agent-api/v1/commands/{commandId}:ack` | Accept/reject and obtain fencing token |
| POST | `/agent-api/v1/commands/{commandId}/events` | Idempotently report progress and phase state |
| POST | `/agent-api/v1/evidence/uploads` | Create upload session and presigned URL |
| POST | `/agent-api/v1/evidence/uploads/{id}:complete` | Ask Server to validate and persist Metadata |
| PUT | `/agent-api/v1/attempts/{attemptId}/result` | Idempotently submit terminal Test Result |

Every Endpoint in the table is a complete Versioned Path. A client must not prepend `/agent-api/v1` again, and an implementation must not expose an unversioned alias.

## 4. Command Envelope

```json
{
  "protocolVersion":"1.0",
  "commandId":"cmd_01...",
  "attemptId":"att_01...",
  "commandType":"EXECUTE_TEST_CASE",
  "issuedAt":"2026-08-21T12:00:00Z",
  "deadline":"2026-08-21T12:10:00Z",
  "leaseDurationSeconds":90,
  "idempotencyKey":"att_01...:execute",
  "payloadSchemaVersion":"1.0",
  "payload":{
    "caseId":"boot-smoke",
    "caseVersion":1,
    "timeoutMs":300000,
    "requiredEvidence":["LOG","SCREENSHOT"]
  }
}
```

Agent must persist commandId, attemptId, last sequence, and local execution state before ACK. Command Payload contains no Secret; required access credentials use short-lived restricted references.

## 5. ACK, Events, and Idempotency

- ACK states are ACCEPTED/REJECTED; rejection requires a stable reason code.
- Server returns `leaseId` and monotonic `fencingToken` for ACCEPTED.
- Every Event includes `(commandId, sequenceNo)`; a duplicate sequence returns the accepted response without repeating side effects.
- Result uses PUT by attemptId. The same digest returns the original Result; a different digest returns 409 and quarantines diagnostics.
- A write with expired fencing token returns 409 STALE_LEASE, stopping an old Agent from contaminating a new Attempt.
- After Attempt/Run terminal state, a repeated Event/Result with the same digest returns the original acknowledgement. A different digest or illegal sequence returns 409 LATE_EVENT_CONFLICT, enters quarantined diagnostics, and does not modify terminal Facts.

## 6. Heartbeat, Disconnection, and Reconnection

Heartbeat includes monotonic agent uptime, current command, last sequence, Device power/connectivity, temporary disk capacity, and clock offset. Server does not use Agent wall-clock time to decide leases.

```text
Disconnect
→ lease remains valid for grace window
→ Agent reconnects and reports persisted command state
   ├─ same active lease: resume/report
   ├─ result already accepted: acknowledge and clean local spool
   └─ lease expired/reassigned: stop side effects, upload diagnostics only
→ grace expired: Server keeps RECOVERY_PENDING until recovery deadline, then writes ERROR/TIMEOUT Result
```

## 7. Sudden Device Power Loss

When Agent and Device are deployed separately, Agent reports DEVICE_UNREACHABLE. When Agent runs on Device, heartbeat loss is used to infer it. Server retains Attempt and uploaded Evidence during the recovery window. After restoration, Agent reports boot/session identity so the restarted environment is not mistaken for one continuous execution.

Non-idempotent device actions are not replayed automatically. After expiry, Attempt becomes explicit ERROR/TIMEOUT. Retry creates a new Attempt and commandId.

## 8. Evidence Upload

Agent first computes SHA-256 and creates an Upload Session to obtain a short-lived, single-object, size-limited presigned URL. After upload, Complete includes actual size/checksum/contentType/capturedAt/collectorVersion. Server queries object Metadata and verifies it. Failure remains REJECTED/PENDING and does not create AVAILABLE Evidence.

The local spool is indexed by attempt/evidence ID. At the capacity threshold, Agent stops claiming new work and reports DEGRADED; required Evidence must not be silently deleted.

## 9. Agent Lifecycle and Upgrade

States: REGISTERING, ONLINE, BUSY, DEGRADED, DRAINING, OFFLINE, REVOKED. Before upgrade, enter DRAINING and accept no new Command. Upgrade after completing/aborting current work. Server defines minimum/recommended version. Forced upgrade occurs only with no running task; failure rolls back to the prior signed version.

V0.2 does not provide arbitrary Server remote shell execution. Command types and Payload schemas must be allowlisted, versioned, and from trusted signed sources.

## 10. Acceptance

- Duplicate poll/ACK/event/result does not create duplicate execution Results.
- Network disconnection, Server restart, Agent restart, and Device power loss are rehearsed.
- Expired lease/fencing token cannot write a valid Result.
- Interrupted Evidence upload can resume/retry and ends with the same checksum.
- An incompatible Agent is explicitly rejected rather than run in degraded compatibility.
- Contract Tests assert that every Agent Endpoint has the unique `/agent-api/v1` prefix and no unversioned alias.
- RECOVERY_PENDING, a late Event/Result, and an expired fencing token do not change terminal Run input.

Evidence: protocol contract tests, fault-injection logs, command timeline, reconnection/power-loss report, and Agent upgrade rollback record.
