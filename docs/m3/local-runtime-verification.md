# Local PostgreSQL / Backend Demo Runtime Verification

## Scope and Authorization

After the preceding turn specified preparation of a minimal isolated PostgreSQL/Backend demo runtime on drive D and its prerequisite, the Owner replied “execute the next step”, authorizing this local dependency preparation. TDR-003 and TDR-024/025 remain authoritative. No Company, real Provider or system service is enabled; this is not M3 Owner acceptance.

Chinese source d1ac4a0e1645b1344404d5cc117d99e3c206eda9 and paired English 5d9169c57b21eb2e78941808f63df7a7371d2486 were used; fixed implementation Subjects remain dbd59a48ba9c7dc9279588e046182dbf97ab22ef / bc1f62637ac9f9357912abf85961a65cb0035852. No product code, Migration or raw device identity configuration changed. Commits carrying this record are versioned separately.

## Actual Environment

- Date: 2026-09-10; current Windows account, existing JDK 21 and PowerShell 7.
- Directory outside the repository: D:/VSRQG-local-smoke/runtime-20260910-85ebd6c0. Root inheritance is disabled with one explicit FullControl rule for the current account; private keys, passwords, raw device information and database files are not committed.
- PostgreSQL 17.11 matches the repository compose baseline. The [official PostgreSQL Windows page](https://www.postgresql.org/download/windows/) points to the [EDB ZIP page](https://www.enterprisedb.com/download-postgresql-binaries); download locator https://sbp.enterprisedb.com/getfile.jsp?fileid=1260491.
- The ZIP is 341325378 bytes, SHA-256 4b8db0930c38f6ef845db919551dedda3b6b845aeb0927b3d79a6e8e9e4537cf. All ZIP CRC and member-path checks passed; only bin/lib/share were extracted. This is a locally measured digest, without claiming comparison against an independent publisher digest; postgres.exe Authenticode is NotSigned.
- The database listens only on 127.0.0.1:55432, database name vsrqg_demo, with SCRAM-SHA-256. The separate application role has no SUPERUSER/CREATEDB/CREATEROLE. Random passwords remain in controlled files referenced by configuration; no trust authentication, system service or global PATH change.
- Backend uses https://localhost:58443, PILOT, Provider NONE and the original isolated demo environment. Server/Agent use local demo PKCS12 identities and a separate certificate trust store with 90-day validity. Operating-system trust was not modified; TLS certificate and hostname verification were not bypassed.
- Normal/failure main configurations, identity/database/TLS references and payload/evidence are prepared, referencing the previous private device.json and APK. No device reread or ADB execution occurred; device environment must be rechecked before actual execution.

## Checks and Evidence

Local RuntimeHealth.java only calls existing M3Config.read and M3DemoBootstrap.start, checks health/liveness/readiness and the unauthenticated business API through real HTTPS, then closes the Spring context. It does not call initialize, M3DemoMain or Agent. A Gradle init script outside the repository exports the original demo runtimeClasspath; original demoClasses were UP-TO-DATE and the export task succeeded. This small probe is not a new product launcher or protocol fixture.

| Check | Result | Actual evidence |
|---|---|---|
| PostgreSQL version, connection and role limits | PASS | Actual --version is 17.11; psql connects to vsrqg_demo and all three administrative privileges are false. |
| Flyway migrations | PASS | 15 successful entries; the last version by installed_rank is 15. No Migration added or modified. |
| First HTTPS health round | PASS | health, liveness and readiness return HTTP 200 / UP; unauthenticated business API returns 401. |
| Normal Backend shutdown | PASS | Port 58443 no longer listens after context closure. |
| Database stop/start | PASS | Bounded pg_ctl waits succeed; neither 55432 nor 58443 listens while stopped, followed by successful restart. |
| Migration state after restart | PASS | Output bytes for migration version/checksum/success and four entity counts have identical digests. |
| Second HTTPS health round | PASS | Again three HTTP 200 / UP responses and business API 401, followed by normal Backend closure. |
| No demo business execution | PASS | release_record, test_run, test_result and agent counts are all 0; no Run creation, APK installation or device Evidence collection. |
| Final state | PASS | PostgreSQL and Backend stopped; no listeners on 55432/58443. Data, configuration and logs retained. |
| Real normal/FAIL, disconnection/Agent restart, database/Payload restore | UNKNOWN | Not executed; ordinary database restart is not backup restoration. |

Actual logs remain under the external logs directory. SHA-256 values identify the same materials:

| Material | SHA-256 |
|---|---|
| RuntimeHealth.java | f23c695d0f577d492b816c42511649920aa6ca3c57283190bcdd2d5034b339cb |
| postgres.ps1 | 0ec1acef743bc121c1c654c748e7b8e236fc1c9585bd9640437700e074fff274 |
| backend-health-1.log / backend-health-2.log | ae1e1364f0d3b8e0a5f9d265ea116495b9fba8f6ab3c08beccecc24716d7b81e |
| db-before-restart.txt / db-after-restart.txt | 79042344d2d0e7aa6cfeada7e1cce0cca8b2c49df7af9a8bd0d99296c5cbf0a0 |

## Observed Problems and Retained Limits

The initial direct pg_ctl invocation remained blocked in PowerShell after its log reported server startup, because the long-lived child inherited output handles. Stopping this owned database separately released the call, and subsequent database creation explicitly failed to connect. The corrected invocation uses Start-Process Hidden mode, separate output files and bounded Process.WaitForExit for pg_ctl alone; startup, database creation, shutdown and the second startup passed. Initial failure logs remain; initdb was not rerun and the database was not deleted. A diagnostic query also used the wrong release table name and failed read-only; following the actual Migration with release_record made the count checks pass.

This turn only resolves local runtime prerequisites and does not add Docker/Testcontainers; the previous local m3IntegrationTest Docker failures remain historical facts. The six implementation Artifacts were not rerun and ordinary restart is not claimed as fault recovery. Existing P3, performance, canonical and expiry limits remain in the [integration engineering record](single-device-smoke-verification.md). The new Owner Gate remains PENDING.

## Operations and Next Action

Using PowerShell 7, run postgres.ps1 -Action Start / Status / Stop in the private directory above. The script targets only its own data directory and explicitly rejects an occupied port on Start; no service registration or automatic startup. RuntimeHealth.java, compiled classpath and controlled configuration remain available. Actual normal/failure demos still use the original repository scripts/demo/run-m3.ps1 with config-normal.json / config-fail.json; the health probe cannot replace that entry. Do not rerun the one-time prepare-config.ps1 or initialize-postgres.ps1.

Current result: two local database/Backend health rounds, normal stop/start and migration-state retention checks are complete; processes are stopped. Git status: this record is committed and pushed as a bilingual pair, identified through Git history.

Subsequent normal and deterministic FAIL real-device execution and its fixed Subjects are recorded separately in [real-device verification](real-device-smoke-verification.md). The text above preserves the historical absence of business execution during runtime preparation. Current sole next action: follow that record for Task 7 Step 5 controlled connection interruption, Agent restart and paired database+Payload restoration; its prerequisites and acceptance evidence apply. Unexecuted checks remain UNKNOWN; ordinary stop/start is not restoration.
