# CRD, VAP, and Checksum Hardening Plan

This document turns the VAP/checksum coverage audit into an implementation plan.
It is intentionally separate from [reconfiguringWorkflows.md](reconfiguringWorkflows.md),
which describes the broader state-aware workflow model.

> **Follow-up:** A focused cleanup of the gaps found while reviewing this plan's
> implementation is tracked in
> [migrationResourceContractCleanupPlan.md](migrationResourceContractCleanupPlan.md). That
> work implements the "write all projected DataSnapshot/SnapshotMigration fields in the apply
> manifest" item below (via `spec.dependsOn` written by `tryApply`), moves the Solr-only
> `solrCollections` off the user schema, adds the DataSnapshot VAP-retry recovery loop, and
> aligns resolved parameters with the applied CR spec. The projection⟷apply parity test this
> plan proposed is superseded by the "generate apply manifests from the projection table"
> follow-on described in that document (which makes the drift structurally impossible), rather
> than added as a standalone test.

## Summary

The current implementation generally protects workflow dependency freshness with
checksums, but it does not always expose the same material inputs through CRD
specs. That means the workflow can often notice that a resource is stale, while
the ValidatingAdmissionPolicy layer cannot always block, require approval, or
force reset/recreate for the underlying material change.

The hardening goal is to make the story consistent:

| Layer | Responsibility |
| --- | --- |
| CRD `spec` | Durable, inspectable desired contract for the migration resource. This is what VAPs compare. |
| VAP | Admission-time guardrail for dangerous spec updates and terminal-resource provenance. |
| Status checksums | Durable record of the contract version that was actually completed or made ready. |
| Dependency checksums | Downstream freshness guard for upstream resources that the current resource waits on. |
| Trace annotations | Explicit escape hatch for workflow-only or sensitive material that should not be expanded into `spec`. |

The main design correction is: every input that can change resource behavior or
downstream correctness must be represented in exactly one of these ways:

| Representation | Use when | Example |
| --- | --- | --- |
| Same-resource `spec` | The value is part of the resource's desired contract and is safe/useful for users to inspect. | Snapshot mode, source label, repo URI, target endpoint. |
| Upstream dependency checksum | The value belongs to another migration resource that this resource waits on. | Replayer waits on captured traffic checksum. |
| Same-resource spec hash | The value is material but should not be fully disclosed or is too verbose. | Auth config containing mTLS CA material. |
| Trace annotation | The value is workflow-only and intentionally outside the CRD contract. | CaptureProxy file-source bridge fields. |
| Tested derivative | The value cannot change independently of another projected field. | A derived mode only if its source discriminant is projected and tested. |

Anything else is hidden material and should be treated as a bug.

## Evidence From History

The history suggests that labels were originally used for identity, naming,
dependency references, and operator visibility. They were not a complete
material contract for source/target/repository values.

The later checksum work deliberately added source and target connection identity
to internal checksums and RFS workload identity, but those fields were not
promoted to the CRD/VAP surface. The result is a split model:

| Existing pattern | What it protects | What it misses |
| --- | --- | --- |
| Material fields in checksums | Workflow skip/freshness and downstream waits. | VAP cannot reject hidden material changes. |
| Labels in CR specs/metadata | Resource names, references, history, display, dependency keys. | Same label can point at different endpoint/auth/repo material. |
| Terminal lock-on-complete | Blocks visible spec changes after completion. | Cannot block a hidden material change if the spec remains unchanged. |

This plan closes that gap without treating labels as full cluster identity.

## Terms

| Term | Meaning |
| --- | --- |
| Metadata label | Kubernetes `metadata.labels`. Useful for selection and display, not the primary VAP contract. |
| Spec identity field | A normal `spec` field used by VAPs, resolved resource output, and users. |
| Connection identity | Stable, sanitized description of a source/target/Kafka endpoint and auth identity. |
| Repo identity | Stable description of snapshot/backup repository location and access role. |
| Workflow-only field | Runtime helper that belongs to the workflow/deployment bridge, not the migration CRD contract. |

## Target Invariants

1. If a resource `configChecksum` changes, at least one of these must also be
   true:
   - that resource's resolved CR `parameters` changed
   - a listed upstream dependency's relevant checksum changed
   - a workflow-only trace annotation changed
   - a test proves the changed checksum input is a derivative of a projected field

2. If a terminal resource reaches `Completed`, rerunning with different material
   work-product inputs must be blocked by VAP unless the user deletes/recreates
   the resource.

3. CRD specs should show users the meaningful resolved contract. Metadata labels
   can duplicate important identity for filtering, but labels do not replace spec
   fields.

4. Secrets and bulky auth payloads should not be copied into CR specs. Use a
   stable identity hash only for verbose pieces, and expose safe identity fields
   such as auth type, secret names, region, service, endpoint, and `allowInsecure`
   where useful.

5. Workflow apply manifests, resolved resources, generated CRD schemas, generated
   VAP rules, and checksum computation must agree on the contract. A projected
   field that is never written by `resourceManagement.ts` is drift.

## Cross-Cutting Implementation Changes

| Area | Change |
| --- | --- |
| Identity helpers | Add canonical helpers for connection identity, explicit auth identity fields, and repo identity. Reuse them in checksum computation and resolved resource projection. |
| Projection metadata | Add internal projected fields for resource identity values that do not come directly from user option schemas. |
| Resolved resources | Make `resolvedMigrationResources` emit the same fields that the workflow applies to CR specs. |
| Workflow manifests | Update `resourceManagement.ts` apply manifests to write every projected field for the resource, or remove the projection if the field is not owned by the CR. |
| Checksums | Compute resource checksums from the canonical contract plus dependency checksum values, not broad denormalized helper objects. |
| VAP dry run | Keep dry-run policy based on projected fields, then add coverage tests proving checksum changes are visible to the policy model. |
| Generated snapshots | Refresh CRDs, VAPs, workflow snapshots, and resolved-resource snapshots after projection changes. |
| Docs | Update `reconfiguringWorkflows.md` and `resolvingMigrationParametersFromConfigs.md` after the implementation lands. |

## Sanitized Identity Shapes

These are proposed shapes for CR specs and resolved resources. Exact field names
can be adjusted during implementation, but the content should remain stable.

### Cluster Connection Identity

| Field | Include raw value? | Reason |
| --- | --- | --- |
| `label` | yes | User-facing logical identity. |
| `version` | yes for sources | Behavior can depend on ES/OS/Solr version. |
| `endpoint` | yes | Determines the cluster being read from or written to. |
| `allowInsecure` | yes | Changes TLS verification behavior. |
| `authType` | yes | Basic, SigV4, mTLS, or none changes connection behavior. |
| `authBasicSecretName` | yes | Secret name is identity, not secret payload. |
| `authSigv4Region` | yes | Region changes SigV4 signing behavior. |
| `authSigv4Service` | yes | Service name changes SigV4 signing behavior. |
| `authMtlsClientSecretName` | yes | Client secret name identifies the mTLS client certificate. |
| `authMtlsCaCertHash` | hash | Avoid copying inline CA PEM content into every CR. |

For Basic auth, SigV4, and the mTLS client secret, the CR spec should expose the
actual identifying fields. Only inline mTLS CA certificate content should be
hashed to keep CR specs readable.

### Repository Identity

| Field | Include raw value? | Reason |
| --- | --- | --- |
| `repoName` | yes | Logical repository reference in source config. |
| `repoPathUri` | yes | Bucket/path determines snapshot location. |
| `awsRegion` | yes | Determines S3 region. |
| `endpoint` | yes | LocalStack/fake GCS and custom endpoints are material. |
| `s3RoleArn` | yes | Role controls snapshot repository access. |
| `useLocalStack` | yes | Transform-time behavior and endpoint handling differ. |

The current repo schema does not contain inline credentials, so this is safe to
put in specs. If future repo types add sensitive payloads, add a repo hash field
instead of copying those payloads into the CR.

## Resource Plan

### KafkaCluster

Current state:

| Aspect | Current behavior |
| --- | --- |
| Spec | Version, auth type, node pool replicas/roles/storage, and `dependsOn`. |
| Checksum | Hashes the denormalized Kafka object. |
| Concern | The denormalized object can include helper material such as aggregated topic names that are not part of `KafkaCluster.spec`. Topic-specific behavior belongs to `CapturedTraffic`. |

Implementation changes:

| Change | Reason |
| --- | --- |
| Define a canonical KafkaCluster contract used by both resolved resources and checksum computation. | Prevent hidden checksum inputs. |
| Keep topic creation/configuration out of the KafkaCluster checksum unless it is also in KafkaCluster spec. | Topics are represented by CapturedTraffic resources. |
| Verify existing Kafka cluster connection fields are represented by downstream users or a Kafka connection identity field. | Existing Kafka has no workflow-managed KafkaCluster CR, so consumers must carry enough identity for VAP/freshness. |

### CapturedTraffic

Current state:

| Aspect | Current behavior |
| --- | --- |
| Spec | Kafka cluster label, topic name, partitions, replicas, topic config, source kind, S3 source URI, and load-started marker. |
| Checksum | For live proxy paths, topic checksum is derived from topic config plus Kafka checksum. For S3 loader paths, checksum includes S3 URI, topic, and Kafka checksum. |
| Concern | Mostly aligned. Existing Kafka connection material should be explicitly represented where no KafkaCluster dependency exists. |

Implementation changes:

| Change | Reason |
| --- | --- |
| Keep `sourceKind`, `s3SourceUri`, and `loadStarted` as impossible fields. | Preserves exactly-once loader semantics. |
| Add or verify Kafka connection identity for existing Kafka clusters. | `kafkaClusterName` alone is not enough if the label points to a different external broker. |
| Keep topic settings in CapturedTraffic, not KafkaCluster. | CapturedTraffic is the topic/stream contract. |

### CaptureProxy

Current state:

| Aspect | Current behavior |
| --- | --- |
| Spec | Proxy deployment/process options plus dependency on CapturedTraffic. |
| Checksum | Hashes proxy options plus Kafka checksum. |
| Hidden material | Source endpoint, source `allowInsecure`, source auth, and possibly source version are in `sourceConfig` and used by deployment/runtime, but not in CaptureProxy spec or checksum. |
| Existing good pattern | Workflow-only file/TLS bridge fields are omitted from spec and traced via annotations. |

Implementation changes:

| Change | Reason |
| --- | --- |
| Add source connection identity fields to CaptureProxy spec. | Changing the source destination changes what traffic the proxy captures. |
| Include source connection identity in CaptureProxy `configChecksum`, `checksumForSnapshot`, and `checksumForReplayer` as appropriate. | Snapshots and replays must re-evaluate when captured traffic could come from a different source. |
| Keep workflow-only file-source bridge fields as annotations, not spec fields. | This is already a reasonable explicit exception. |
| Add tests for source endpoint/auth mutation. | Prevent the current hidden-source gap from returning. |

### DataSnapshot

Current state:

| Aspect | Current behavior |
| --- | --- |
| Spec | A subset of create/import options: snapshot prefix, index allowlist, max rate, JVM args, logging config. |
| Checksum | Source connection identity, item config, Solr external backup name, repo config, and proxy dependency checksums. |
| Hidden material | Source identity, repo identity, `mode`, Solr external backup name, several create options, and Solr collection allowlist are not consistently in spec/apply manifests. |

Implementation changes:

| Change | Proposed restriction | Reason |
| --- | --- | --- |
| Add `sourceLabel`, `sourceVersion`, source endpoint, source `allowInsecure`, and explicit source auth identity fields. | impossible | A completed snapshot/backup is tied to a specific source. |
| Add `snapshotLabel`. | impossible | Makes the resource identity visible in spec, not only metadata/name. |
| Add repository identity fields. | impossible | Bucket/path/repo changes produce a different artifact. |
| Add `mode` with values `create` and `import`. | impossible | Create versus import is material behavior. |
| Add `solrExternalBackupName`. | impossible when present | Selects the exact external Solr backup used for import preparation. |
| Stop stripping `mode` in `dataSnapshotParameters`. | n/a | Resolved resources should show what is applied. |
| Write all projected DataSnapshot fields in `upsertDataSnapshotResource`. | n/a | Prevent projection/apply drift. |
| Reclassify material output-scope fields such as `indexAllowlist`, `solrCollections`, `compressionEnabled`, and `includeGlobalState`. | impossible | These alter snapshot/backup contents and should force reset/recreate rather than silently update in flight. |
| Keep JVM args, logging config, telemetry endpoints, and max snapshot rate as safe or gated based on operational risk. | safe/gated | These tune execution without changing artifact identity. |

For Solr, both automatic backups and external backup import preparation should
flow through the same DataSnapshot CR contract. The difference is `mode` and
the optional external backup name.

### SnapshotMigration

Current state:

| Aspect | Current behavior |
| --- | --- |
| Spec | Source/target/snapshot/migration labels, source version, metadata migration options, document backfill options, and dependency names. |
| Checksum | Source connection identity, metadata options, document backfill options, target config, snapshot checksum, snapshot name resolution, and snapshot repo config. |
| Hidden material | Source endpoint/auth, target endpoint/auth, external snapshot/backup name, and repo identity are not all visible to VAP. |

Implementation changes:

| Change | Proposed restriction | Reason |
| --- | --- | --- |
| Add source connection identity fields. | impossible | Metadata migration and Solr paths can read source directly. |
| Add target connection identity fields. | impossible | Migration output is tied to a specific target. |
| Add explicit snapshot resolution fields: `snapshotSourceType`, `dataSnapshotResourceName`, and `externalSnapshotName` where present. | impossible | VAP should see whether this migration uses a generated DataSnapshot or an external snapshot. |
| Add repo identity when no DataSnapshot dependency represents the snapshot material. | impossible | ES/OS external snapshots otherwise have no upstream CR carrying repo/source material. |
| Keep `snapshotConfigChecksum` out of spec when represented by a DataSnapshot dependency wait. | dependency material | Avoid duplicating upstream status checksums as desired spec. |
| Keep metadata and document backfill option projections aligned with ARGO schemas and workflow apply fields. | existing restrictions | These fields are already largely projected, but generated field names and docs need to match. |
| Recompute workload identity from the same canonical material used for spec/checksum decisions. | n/a | Prevent a fourth, divergent identity model. |

### TrafficReplay

Current state:

| Aspect | Current behavior |
| --- | --- |
| Spec | Replayer options and `dependsOn`. |
| Checksum | Replayer config, target config, and upstream captured-traffic checksum. |
| Hidden material | Target connection identity is checksum material but not spec material. Source/captured-traffic identity is mostly represented through dependencies. |

Implementation changes:

| Change | Proposed restriction | Reason |
| --- | --- | --- |
| Add `fromCapturedTraffic`, `fromCapturedTrafficSourceKind`, `sourceLabel`, and `targetLabel` to spec. | impossible | Makes replay identity inspectable and VAP-visible. |
| Add target connection identity fields. | impossible | Changing target cluster changes replay semantics. |
| Keep captured-traffic stream freshness represented by `dependsOn` plus upstream checksum waits. | dependency material | The captured stream belongs to CapturedTraffic/CaptureProxy. |
| Ensure snapshot migration dependencies use resource names and checksums consistently. | dependency material | Replayer must wait for the exact migration pass it depends on. |

### ApprovalGate and MigrationRun

Current state:

| Resource | Current role |
| --- | --- |
| ApprovalGate | User-intervention checkpoint for VAP retry loops. |
| MigrationRun | Immutable history of resolved resource parameters and workflow/run metadata. |

Implementation changes:

| Change | Reason |
| --- | --- |
| Keep ApprovalGate outside checksum material. | It gates admission; it is not migration resource configuration. |
| Ensure MigrationRun captures the hardened resolved resource parameters. | Users should be able to inspect the exact CR contracts submitted in a run. |
| Keep MigrationRun immutable. | It is historical evidence, not desired state. |

## Coverage Test Plan

Add a table-driven config-processor test that mutates one material input at a
time and compares:

1. transformed workflow checksums
2. resolved resource parameters
3. resolved resource annotations
4. dependency links

The assertion should be:

> If a checksum changes, the test must identify a spec field change, a dependency
> checksum source, a trace annotation change, or a tested derivative.

Initial mutation cases:

| Mutation | Expected visible coverage |
| --- | --- |
| Source endpoint for CaptureProxy source | CaptureProxy source identity changes, proxy checksums change. |
| Source auth for DataSnapshot source | DataSnapshot source auth identity fields change, snapshot checksum changes. |
| Target endpoint for SnapshotMigration | SnapshotMigration target identity changes, migration/workload checksums change. |
| Target endpoint for TrafficReplay | TrafficReplay target identity changes, replay checksum changes. |
| Solr external backup name | DataSnapshot `solrExternalBackupName` and SnapshotMigration external name change. |
| Solr create backup versus external backup | DataSnapshot `mode` changes. |
| Solr collection allowlist | DataSnapshot `solrCollections` and migration allowlists change. |
| ES/OS index allowlist | DataSnapshot `indexAllowlist` changes. |
| Snapshot repo URI or endpoint | DataSnapshot repo identity changes, and SnapshotMigration repo identity changes for external snapshots without DataSnapshot dependency. |
| Existing Kafka broker connection | CapturedTraffic or consumer connection identity changes. |
| CaptureProxy file-source bridge field | Workflow-only trace annotation changes, not spec. |

Also add generated-resource tests:

| Test | Purpose |
| --- | --- |
| Projection/apply parity | Every projected field for a resource is written by that resource's apply manifest or documented as initializer-only. |
| Resolved resource parity | Resolved resource parameters contain the same spec contract as workflow apply manifests. |
| VAP dry-run parity | Dry-run policy sees the same restricted fields as generated VAPs. |
| Terminal lock samples | Completed DataSnapshot and SnapshotMigration reject material spec changes. |

## Documentation Updates After Implementation

### `reconfiguringWorkflows.md`

| Needed update | Reason |
| --- | --- |
| Replace broad "each CRD carries all downstream checksums" language with resource-specific checksum status fields. | Only resources with downstream waiters need named downstream checksums. |
| Update phase names to current generated values: `Created`, `Pending`, `Ready`, `Completed`, `Deleting`, `Error`. | The doc still contains older lifecycle terms in places. |
| Update DataSnapshot field table with concrete create/import/repo/source fields. | The current table collapses hidden material. |
| Update SnapshotMigration field names to generated prefix paths such as `metadataMigrationIndexAllowlist`. | The doc uses older nested config paths. |
| Add the checksum visibility invariant from this document. | Prevent future hidden material. |
| Clarify that workflow apply manifests are part of the contract. | Projection metadata alone does not write Kubernetes objects. |

### `resolvingMigrationParametersFromConfigs.md`

| Needed update | Reason |
| --- | --- |
| Describe hardened resolved resource parameters as the CR spec contract. | Users and MigrationRun history should inspect the same fields VAPs see. |
| Add workflow apply manifests to the "single source of truth" story. | Hand-written manifests can drift. |
| Explain trace annotations for deliberate workflow-only omissions. | Avoid making hidden material look accidental. |
| Add coverage test expectations. | The doc currently lists useful tests but not checksum/spec coverage. |

## Implementation Order

| Priority | Work | Notes |
| --- | --- | --- |
| 1 | Add canonical identity helpers and coverage tests in failing form. | This locks in the desired invariant before changing many fields. |
| 2 | Harden DataSnapshot projection, resolved parameters, apply manifest, checksums, and tests. | Highest-risk gap for Solr create/import behavior and terminal artifact provenance. |
| 3 | Harden CaptureProxy source identity. | Current checksum/spec omit source destination even though deployment uses it. |
| 4 | Harden SnapshotMigration source/target/snapshot identity. | Aligns VAP with existing checksum/workload identity intent. |
| 5 | Harden TrafficReplay target identity and replay identity fields. | Closes target hidden material for replay. |
| 6 | Audit KafkaCluster/CapturedTraffic existing Kafka identity and topic ownership. | Mostly aligned, but current Kafka checksum may be broader than spec. |
| 7 | Regenerate CRDs/VAPs/workflow snapshots and update integration snapshots. | Mechanical fallout from projection changes. |
| 8 | Update design docs. | Keep docs in sync after code behavior is settled. |

## Open Decisions

| Decision | Recommendation |
| --- | --- |
| Full auth object in specs versus hash | Expose auth identity fields directly; hash only bulky inline mTLS CA content. |
| Repo config in specs versus hash | Include current repo identity fields directly; add a hash later only if future repo types add sensitive payloads. |
| Material snapshot fields as gated or impossible before completion | Use impossible for artifact identity/scope fields. Users should delete/recreate terminal resources to change the artifact. |
| Whether to include upstream checksum values in specs | Do not include status checksum values in desired spec. Use `dependsOn` plus wait conditions. |
| Whether labels alone can represent identity | No. Labels are useful display/reference fields, but material identity belongs in spec fields or hashes. |
