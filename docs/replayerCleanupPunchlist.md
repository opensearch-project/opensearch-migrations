# Replayer cleanup punchlist

Non-authoritative inventory only. Designs, `AGENTS.md`, Plan A, and the live status register retain
their defined roles.

| Item | State | Evidence / next action |
|---|---|---|
| Remove unreachable replay helpers and unused overloads | complete, local commits | `546158d76`, `818fc4937`; no live references remain |
| Retain `SolrSnapshotDataProvider` | complete | File and Solr reader path remain unchanged |
| Drop production `AsyncLink` adoption | unstaged | Request owner is back to direct owner-specific composition; `AsyncLink` remains an expository helper |
| Authorize the expository-only `AsyncLink` design wording | gated | Keep the design edit and its dated register row in a separate commit; run `tools/verify-design-authorization.sh` before any push |
| Remove synthetic Kafka and heartbeat samples from `test_cdc_base.py` | unstaged | Consumer-assignment parser coverage remains in `test_log_kafka_consumer_group_state.py`; deployed heartbeat assertion remains an E2E check |
| Reject conflicting workflow-managed Kafka timestamp configuration | complete, unstaged | Source-specific policy: live capture topics default to and require `LogAppendTime`; BYOC import topics default to and require `CreateTime` so imported records retain archived timestamps. Schema, transformer, and generated-resource tests pass; record the contract decision in the live register before commit |
| Retire obsolete markdown and Plan B references | awaiting owner disposition | Four deletion candidates plus their active-document reference cleanup remain unstaged |
| Verify accumulated cleanup | in progress | Rerun narrow Gradle compile/tests, focused Python tests, and `git diff --check` |
| Publish branch | blocked by owner | Do not push until Greg explicitly directs it |
