# Change-restriction override for running resources (maintainer runbook)

This is a break-glass support tool, not a user feature. It isn't in `--help`, completion, denial messages, or
hints. Use it when a running migration needs a change the admission policies refuse and resetting the resource
would throw away too much progress. The motivating case is changing a document transform partway through a long
backfill.

## What it overrides

| Guard                                                                 | Overridden? |
|-----------------------------------------------------------------------|-------------|
| Impossible fields                                                     | Yes         |
| Gated fields (already approvable with `workflow approve change`)       | Yes         |
| `nonDecreasing` invariants, which no approval can override            | Yes         |
| Lock-on-Complete: a `Completed` resource is sealed                     | No: reset it |
| The deleting-phase guard                                              | No          |
| MigrationRun immutability                                             | No          |

Every field rule in the generated ValidatingAdmissionPolicies also passes when the resource carries a
override annotation whose value equals its `migrations.opensearch.org/run-number` label:

- one field: `change-restriction-override.migrations.opensearch.org/<specPath>: "<run>"`
- every field on the resource: `migrations.opensearch.org/change-restriction-override-all-fields: "<run>"`

Because the value is a run number, an override only applies to the run it was written for.

## Using it

The workflow must be parked on the resource's retry gate after the denial (it is, until someone acts). Then:

```
workflow approve retry <resource> --override-change-restrictions            # the fields named in the denial
workflow approve retry <resource> --override-change-restrictions --field documentBackfillDocTransformerConfig
workflow approve retry <resource> --override-change-restrictions --all-fields
```

`approve change` accepts the same flags. The command:

1. refuses a `Completed` or `Deleting` resource (use `workflow reset`),
2. reads the run number from the workflow's `migrationRunNumber` parameter,
3. prints a warning and asks you to type the resource name (or pass `--yes`),
4. writes the annotations onto the resource (a metadata-only patch, which every policy accepts),
5. approves the gate without requiring a reset, recording `change-restriction override: <fields> by <user> at <time>` in the
   gate's status message.

The workflow's retry loop then re-applies the resource, and the policies accept it. If the workflow is no
longer parked (stopped or failed), resubmit it, let it hit the denial again, then override. The annotation
can't be written before submitting, because the run number isn't known yet.

The annotations stay on the resource as the audit record, and the console logs each use at WARN.

## Caveats

- **The change applies in place and may only partly take effect.** Work already done isn't redone: documents
  already migrated keep the old transform; metadata already created keeps its old form. For fields that select
  work, such as `indexAllowlist`, the coordinator's work items were fixed at setup, so the effect is partial.
- **The backfill keeps its fleet and its progress.** The RFS session (Deployment and coordinator) is normally
  named from a checksum of the Impossible backfill fields. The workflow records the session name on the
  SnapshotMigration (`status.rfsSessionName`) when the backfill first starts and reuses it afterward, so an
  overridden change to one of those fields rolls the existing Deployment instead of starting a second fleet
  with a fresh coordinator. A reset deletes the resource, which clears the record.
- **Downstream applies can still refuse.** Kubernetes or Strimzi may reject the new Deployment or Kafka
  resource. Resetting the resource is then the way out.
