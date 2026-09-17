# M3 CI timeout test startup budget fix

Date: 2026-09-17. Scope: test fixtures and assertions, without changing production process control.

## Failure and fix

Quality Task 1 Subjects are 97600e6 / 8a5b8a3. English [M3 CI 35174638386](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/35174638386) failed when m3-demo.tests.ps1 read owned.pid; APK build succeeded and subsequent Backend/Agent steps were skipped. Chinese M3 35174638109 succeeded.

The original test allowed only 1 second for PowerShell startup/execution while assuming the fixture had written its PID. Adding a fixture that delays PID writing by 2 seconds reproduced the same local failure with exit code 1. The test now allows 10 seconds for execution and 12 seconds for cleanup/scheduling, checking timeout lower/upper bounds, PID existence, owned-process exit and unrelated-process survival. Missing initialization fails explicitly without skipping assertions. The production helper, default timeout and API are unchanged; no new technology choice.

## Verification and boundaries

Complete wrapper tests passed 14/14 in both Chinese and English worktrees with exit code 0. Coverage includes configuration rejection, build/child failures, missing reports, wrong digests, timeout cleanup, process isolation, nonzero exit propagation and output limits. Local Windows results do not substitute for Linux CI on the fix commits.

The original CI failure remains traceable; this fix does not record Owner acceptance. Quality Task 2, devices, Company Providers and deployment were not started.

## Next execution plan

Current result: test startup budget diagnosed and corrected. Git status: this record and tests are committed bilingually, with versions identified by Git history. Next action: verify the six CI runs for the fix commits. Prerequisites: GitHub CI completion. Acceptance target: all six succeed, especially Linux delayed startup, timeout cleanup and process isolation, before Quality Task 2.
