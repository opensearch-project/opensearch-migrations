# Focused CR Reconciliation Patterns

## Purpose

This document only covers the work that is still left.

The project now has the basic ownership model in place:

- `KafkaCluster` owns Kafka cluster infrastructure
- `CapturedTraffic` owns the capture topic / stream contract
- `CaptureProxy` owns the live proxy infrastructure

The next step is to bring every workflow-managed root resource up to one shared standard:

- write the desired contract into the root CR `spec`
- treat `status.configChecksum` as the last configuration the workflow actually finished applying
- skip subtree work when `status.configChecksum == desiredChecksum`
- descend into subtree reconciliation when the checksum is missing or different
- patch the success status only after the subtree finishes

## What is already done

The following should be treated as complete enough to stop planning around them:

- approval-gate retries replaced Argo suspend for the active flows
- recursive retry loops reset gates back to `Pending`
- the capture lifecycle was split into:
  - `CapturedTraffic` for topic / stream lifecycle
  - `CaptureProxy` for proxy lifecycle
- per-proxy workflow now has separate topic and proxy phases
- per-proxy data threading now includes:
  - Kafka cluster UID
  - topic checksum
  - topic contract fields
- dependency direction is now:
  - `KafkaCluster -> CapturedTraffic -> CaptureProxy`
- downstream waits now use `CaptureProxy` instead of the old proxy-oriented `CapturedTraffic`
- reset behavior and tests were updated to reflect:
  - `CaptureProxy` protected by default
  - `CapturedTraffic` independently resettable
  - Kafka blocked by real dependencies, not proxy protection alone

## Shared standard to apply everywhere

### Pattern A: root CR contract

Each workflow-managed root CR should follow this sequence:

1. read the current resource status
2. compare `status.configChecksum` to the desired checksum for this run
3. if they match:
   - treat the resource as already done
   - skip subtree work
4. if they do not match:
   - reconcile the root CR `spec` to the desired contract
   - descend into the subtree
   - only after subtree success, patch:
     - `status.phase`
     - `status.configChecksum`
     - any other waiter-facing success fields

This means:

- `spec` is the desired contract
- `status` is the last successfully applied contract

### Pattern B: approval / manual intervention loop

The workflow should still use approval gates for persistent update failures that need operator intervention.

Operationally, the important distinction is:

- `ALREADY_DONE`
- `NOT_DONE`

If a reconcile attempt is not already done and cannot proceed automatically, the workflow can:

1. surface an approval/manual-intervention point
2. let the user fix or delete the conflicting resource if needed
3. retry the same reconcile path

For current planning purposes, `GATED` and `BLOCKED` can share that same workflow action.

### Pattern C: accepted skip risk

The current model intentionally accepts this risk:

- if child resources drift or are deleted outside the workflow, but the root CR still carries the old successful checksum, a rerun may skip that subtree

That is acceptable for now.

The status checksum is treated as the durable record of the last successfully applied contract, not as a continuously reconciled proof of live child state.

## Remaining work

### 1. Finish Kafka root reconcile

Kafka is the next root resource that should fully match the checksum/status standard.

What is still left:

- keep `KafkaCluster` focused on cluster infrastructure only
- make the root Kafka flow use checksum-based skip as the primary "already done" signal
- write the real Kafka contract into `KafkaCluster.spec` before descending into child reconciliation
- patch the Kafka success checksum only after Kafka child resources finish reconciling
- keep the approval/manual-intervention loop for persistent update failures
- finish the root Kafka VAP coverage for true cluster-level fields, especially:
  - `auth.type`
  - any other true cluster knobs that should be `safe`, `gated`, or `impossible`

Kafka-specific follow-up:

- the root Kafka flow should stop pretending every failure is a distinct approval-worthy category
- the more important behavior is:
  - checksum matches -> skip
  - checksum differs -> try reconcile
  - persistent failure -> approval/manual intervention and retry

### 2. Finish `SnapshotMigration` root reconcile

`SnapshotMigration` should then be brought to the same standard.

Still needed:

- compute and carry the desired checksum for the migration contract
- reconcile the real VAP-relevant fields into `SnapshotMigration.spec`
- skip subtree work when `status.configChecksum` already matches
- otherwise run metadata/backfill subtree work
- only after subtree success, patch:
  - `status.phase`
  - `status.configChecksum`

### 3. Finish `TrafficReplay` root reconcile

`TrafficReplay` is the next resource in the same category.

Still needed:

- compute and carry the desired checksum for the replay contract
- reconcile the real VAP-relevant fields into `TrafficReplay.spec`
- skip subtree work when `status.configChecksum` already matches
- otherwise run the replay subtree
- only after subtree success, patch:
  - `status.phase`
  - `status.configChecksum`

## Recommended order

1. Lock in the checksum/status contract in docs and workflow helpers
   - use the same meaning everywhere:
     - `spec` = desired contract
     - `status.configChecksum` = last successfully applied contract

2. Finish Kafka root reconcile
   - it is the next most important long-lived root resource
   - it should become the reference implementation of the checksum-based skip model

3. Apply the same pattern to `SnapshotMigration`

4. Apply the same pattern to `TrafficReplay`

## Practical checklist

The remaining work should eventually let us answer yes to these:

- Does each root CR write the desired contract into `spec` before descending into child reconciliation?
- Does each root workflow skip subtree work when `status.configChecksum` already matches the desired checksum?
- Does each root workflow descend when the checksum is missing or different?
- Does each root workflow patch success status only after subtree convergence?
- Does `KafkaCluster` use that model cleanly for cluster-level fields only?
- Does `SnapshotMigration` reconcile its own contract before running metadata/backfill work?
- Does `TrafficReplay` reconcile its own contract before running replay work?
