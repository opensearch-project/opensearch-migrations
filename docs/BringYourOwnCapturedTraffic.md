# Bring-Your-Own Captured Traffic — Plan

## Context

Users today can hand the migration workflow a pre-existing snapshot (`externallyManagedSnapshotName` in `SNAPSHOT_NAME_CONFIG` at `orchestrationSpecs/packages/schemas/src/userSchemas.ts:927-939`) and optionally hand it a pre-existing Kafka cluster (`KAFKA_CLUSTER_CONFIG.existing`, `userSchemas.ts:827-833`, already wired through `buildKafkaClusters` at `config-processor/src/migrationConfigTransformer.ts:519-520` — **already supported end-to-end, no work needed**).

There is no equivalent escape hatch for **traffic capture**. The only way to feed the replayer today is to run a live capture-proxy in front of the source and have it produce to a Kafka topic. We want a third mode: **"I already have the captured traffic — replay it without standing up a proxy."** The user's authoritative artifact is an S3 object produced by the migration console's `kafkaExport.sh` (`migrationConsole/kafkaExport.sh:94`, `kafka_export_from_migration_console_<ts>.proto.gz` — base64 lines of `TrafficStream` protobuf, gzipped). Example object the user has: `s3://opensearch-migrations-upload-bucket-for-02-26/kafka_export_from_migration_console_1778717282.proto.gz`.

User-confirmed design choices:
- **Schema**: parallel top-level `traffic.s3Sources` map alongside `traffic.proxies`, not a discriminated union inside `proxies`.
- **Replayer waits**: switch *both* live and S3 paths to gate on `CapturedTraffic`, dropping the replayer's dependency on `CaptureProxy` (snapshot path keeps gating on `CaptureProxy`).
- **Phase model**: keep just `Ready`/`Error`. **For S3, `Ready` means "fully loaded"** — it flips Ready only after the loader exits zero. The intermediate "topic provisioned, load in flight" state is encoded by the *absence* of phase plus an explicit `Started` marker (see §7). CaptureProxy keeps its own Ready/Error phases for operator visibility.
- **Loader**: pure shell pipeline using the existing `KafkaLoaderFromFile` Gradle entry point in `libraries/kafkaUtils/`; no new Java code. Documented in `kafkaCmdRef.md` and reused by the workflow.
- **Loader is exactly-once with explicit-reset semantics**: once an S3 load completes, the loader must not run again. If a load started but did not complete (workflow killed mid-run, network failure, etc.), the next reconcile must **fail fast** rather than silently re-running the producer (which would duplicate every record on the topic). This is enforced via two-step "create" semantics on the `CapturedTraffic` CR: first create with a `Started` marker (a CR create, not a patch — VAP-protected so re-creates are blocked); then on success, the loader itself patches the CR with `Completed` plus metrics. A re-run sees `Started` without `Completed` and fails fast. Recovery requires a manual reset (delete the `CapturedTraffic` CR).

## Critical files

Schema:
- `orchestrationSpecs/packages/schemas/src/userSchemas.ts` `TRAFFIC_CONFIG` (910), `CAPTURE_CONFIG` (881), `REPLAYER_CONFIG` (899), `OVERALL_MIGRATION_CONFIG` super-refine (1136-1193).
- `orchestrationSpecs/packages/schemas/src/argoSchemas.ts` `DENORMALIZED_PROXY_CONFIG` (236), `DENORMALIZED_REPLAY_CONFIG` (272), `ARGO_MIGRATION_CONFIG` (295).

Transformer:
- `orchestrationSpecs/packages/config-processor/src/migrationConfigTransformer.ts` `buildKafkaClusters` (508), `buildProxies` (533), `buildTrafficReplays` (713), `normalizeTrafficConfig` (222), `buildKafkaClientConfig` (296).

Workflow templates:
- `orchestrationSpecs/packages/migration-workflow-templates/src/workflowTemplates/` `fullMigration.ts` — `setupSingleProxy` (183), `runSingleReplay` (549; `waitForProxy` at 607 is what we replace), `main` (672). `setupCapture.ts` — `reconcileCaptureTopicAndProxy` (635); the topic-only sub-flow lives at lines 664-715 and needs to be extracted. `replayer.ts` — `setupReplayer` (340). `resourceManagement.ts` — `makeCapturedTrafficManifest` (191), `patchCapturedTrafficReady` (893), `waitForCapturedTraffic` (992 — exists, currently used by snapshot deps; we add a second use site).

Console export format (round-trip target):
- `migrationConsole/kafkaExport.sh:94-128` — `<key>|<base64(payload)>` per line, gzipped.
- `libraries/kafkaCommandLineFormatter/src/main/java/.../Base64Formatter.java` — the export-side formatter (no companion reader needed; see below).
- `libraries/kafkaUtils/` — **already contains the import-side tool**. `KafkaLoaderFromFile.java` accepts `--stdin` (uncompressed records) or `--inputFile` (gzipped), `--kafkaBrokers`, `--topicName`, `--batchSize`, and full `KafkaConfig.KafkaParameters` (broker URL, client ID, MSK-IAM, property file). Format match is exact — `KafkaLoader.java:78-83` splits on `\\|` and base64-decodes, the inverse of `Base64Formatter`. The library's README points at it but notes it's not yet shipped in any image; we just need to wire it in.
- `migrationConsole/kafkaCmdRef.md` — sample-command reference doc. The new import recipe goes here.
- `migrationConsole/README.md` — currently documents only the export direction; gets a new "Importing Captured Traffic into Kafka" section.

## Recommended approach

### 1. Loader = pure shell + existing `KafkaLoaderFromFile`

No new Java. The migrationConsole image gains a packaged `KafkaLoaderFromFile` jar (built from `libraries/kafkaUtils/`) so the loader recipe is one shell command end to end.

**Image change**: the migrationConsole `Dockerfile` already bundles `kafka/bin/`, the `aws` CLI, and various jars (see `migrationConsole/build/dockerContext/`). Add the `kafkaUtils` jar (and a thin wrapper like `kafkaImport.sh`) so the recipe doesn't depend on Gradle being available at runtime.

### 2. Documented loader recipe (kafkaCmdRef.md)

Add an "Importing Captured Traffic from S3" section:

```shell
aws s3 cp "$S3_URI" - \
  | gunzip \
  | java -jar kafkaUtils.jar \
      --stdin \
      --topicName "$TOPIC" \
      --kafkaBrokers "$MIGRATION_KAFKA_BROKER_ENDPOINTS" \
      $kafka_command_config
```

(Followed by the same MSK-IAM and EKS notes already at the top of `kafkaCmdRef.md`.) The companion update to `migrationConsole/README.md` points users at this section. The wrapper script `kafkaImport.sh` handles the same MSK-IAM / kafka.properties detection that `kafkaExport.sh` does today (lines 8-13).

`KafkaLoaderFromFile` is the right tool — it has batching, future handling, full `KafkaConfig.KafkaParameters` support including MSK-IAM, and matches the export format byte-for-byte. The migrationConsole image already follows this pattern (it bundles `kafkaCommandLineFormatter-*.jar` for the export side, see `kafkaExport.sh:99`); we extend the same Dockerfile to also bundle the `kafkaUtils` jar plus a `kafkaImport.sh` wrapper that mirrors `kafkaExport.sh:8-13` (MSK-IAM / EKS property-file detection).

### 3. User schema additions (userSchemas.ts)

```ts
// New
export const S3_CAPTURED_TRAFFIC_SOURCE = z.object({
  s3Uri: z.string().regex(/^s3:\/\/[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]\/.+\.proto\.gz$/),
  awsRegion: z.string(),
  // Note: no s3RoleArn — the loader pod uses the workflow executor /
  // migration-console SA's principal directly. Configure S3 read at the
  // SA level when the environment is deployed.
  kafka: z.string().regex(K8S_NAMING_PATTERN).default("default").optional(),
  kafkaTopic: z.string().regex(K8S_NAMING_PATTERN).default("").optional(),
  sourceLabel: z.string()
    .describe("Label of the source cluster the dump was originally captured from. " +
      "Used for resource labeling. Does NOT need to match a sourceClusters key."),
});

// Modified — add s3Sources sibling map
export const TRAFFIC_CONFIG = z.object({
  proxies:    z.record(z.string().regex(K8S_NAMING_PATTERN), CAPTURE_CONFIG).default({}).optional(),
  s3Sources:  z.record(z.string().regex(K8S_NAMING_PATTERN), S3_CAPTURED_TRAFFIC_SOURCE).default({}).optional(),
  replayers:  z.record(z.string(), REPLAYER_CONFIG),
}).superRefine(...);
```

`REPLAYER_CONFIG` (899) renames `fromProxy` → `fromCapturedTraffic`. This is the right name — the replayer's actual dependency is on a CapturedTraffic CR, not on a proxy. The field references *either* a key in `proxies` *or* a key in `s3Sources`; they share the same namespace. The super-refine at line 915-925 + the top-level one at 1136-1193 are updated:

- Replayer key resolution checks both maps.
- `proxies` and `s3Sources` keys must be globally unique across the two maps (name collisions otherwise break replayer resolution).
- `s3Sources` entries don't reference `sourceClusters`, so the source-existence check at 1138 is skipped for those.

This is a hard rename — `fromProxy` is removed. Existing test fixtures and configs are updated in the same change.

### 4. Argo / denormalized schema (argoSchemas.ts)

Add a parallel denormalized shape and a parallel array:

```ts
export const DENORMALIZED_S3_TRAFFIC_LOADER_CONFIG = z.object({
  name: z.string(),
  s3Uri: z.string(),
  awsRegion: z.string(),
  sourceLabel: z.string(),
  kafkaConfig: NAMED_KAFKA_CLIENT_CONFIG,
  topicConfigChecksum: z.string(),
  checksumForReplayer: z.string(),
  configChecksum: z.string(),
  resourceUid: z.string(),
});

export const ARGO_MIGRATION_CONFIG = z.object({
  kafkaClusters: z.array(NAMED_KAFKA_CLUSTER_CONFIG).min(1).optional(),
  proxies:         z.array(DENORMALIZED_PROXY_CONFIG).default([]),
  s3TrafficLoaders:z.array(DENORMALIZED_S3_TRAFFIC_LOADER_CONFIG).default([]),  // NEW
  snapshots:       z.array(DENORMALIZED_CREATE_SNAPSHOTS_CONFIG).default([]),
  snapshotMigrations: z.array(SNAPSHOT_MIGRATION_CONFIG).default([]),
  trafficReplays:  z.array(DENORMALIZED_REPLAY_CONFIG).default([]),
});
```

`DENORMALIZED_REPLAY_CONFIG.fromProxy` is renamed to `fromCapturedTraffic` (it's just the traffic-source name; the replayer no longer cares whether the producer is a proxy or a loader). The replayer template looks up the CapturedTraffic CRD by `<name>-topic` (same naming as proxies use today at `fullMigration.ts:738`).

### 5. Transformer updates (migrationConfigTransformer.ts)

- `buildKafkaClusters` (508): aggregate topic names from both `proxies` and `s3Sources` for autoCreate Kafka clusters.
- `buildProxies` (533): unchanged (still emits live-capture entries).
- New `buildS3TrafficLoaders` method: emits one `DENORMALIZED_S3_TRAFFIC_LOADER_CONFIG` per `s3Sources` entry.
- `buildTrafficReplays` (713): **uniform across both source kinds**. Resolve `fromCapturedTraffic` to whichever map has it (`proxies` or `s3Sources`), build `kafkaConfig` via the same `buildKafkaClientConfig` helper, derive the upstream checksum from whichever entry was resolved. The replayer's view of "what does my traffic source look like" is identical for both kinds — there's no branching in `buildTrafficReplays` itself.

#### Bug fix to roll into this work: percolate proxy-config changes through the buffer

Today, editing `proxyConfig` on a proxy (e.g., `setHeader`, `suppressCaptureForUriPath`, `noCapture`, `maxTrafficBufferSize`) already participates in the proxy-side `checksumForReplayer` (see `schemas/src/userSchemas.ts:392-439` `.checksumFor('snapshot', 'replayer')`), but the replayer's wait gates on the *proxy*'s checksum, not the *CapturedTraffic*'s. With the move to the replayer gating on CapturedTraffic, the topic CR has to carry that information or a downstream proxy edit won't dirty the replayer.

Fix: extend the CapturedTraffic CR (and the associated `patchCapturedTrafficReady` template at `resourceManagement.ts:893-897`) to carry `checksumForReplayer` (and `checksumForSnapshot`, for parity), exactly mirroring how CaptureProxy does it today. The proxy path computes the checksum from proxyConfig fields tagged `checksumFor('replayer')`; the S3 path computes it from the s3Source fields. The replayer's `waitForCapturedTraffic` already supports a `checksumField` parameter (`resourceManagement.ts:992-1016`) — we just pass `"checksumForReplayer"` to it from `runSingleReplay` at `fullMigration.ts:607-615` (the same value previously passed to `waitForCaptureProxy`).

Net effect: edit a proxy's `setHeader` → CapturedTraffic's `checksumForReplayer` rotates → replayer wait re-evaluates and restarts. This was already broken for live capture today; the S3 work forces us to fix it.

### 6. Workflow template changes

**a) Extract topic-only flow from `setupCapture.ts`.** Lines 664-715 of `reconcileCaptureTopicAndProxy` (the `reconcileCapturedTrafficResource → waitForKafkaCluster → createKafkaTopic → patchCapturedTrafficReady` chain) become a separate template `reconcileCapturedTrafficTopic` so both the live path (which then layers proxy setup on top) and the new S3 path (which doesn't) can share it.

**b) New `s3TrafficLoader.ts` template package.** Single template `loadS3IntoTopic` that runs as an Argo container task (NOT a CRD apply) using the `MigrationConsole` image. The script also handles the success-side patch on the CapturedTraffic CR — see §7 for why this lives in the loader script rather than as a separate Argo step:

```ts
.addContainer(b => b
  .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
  .addCommand(["/bin/bash", "-lc"])
  .addArgs([`
set -euo pipefail

# Stream S3 -> gunzip -> KafkaLoaderFromFile (existing tool).
# Capture metrics for the success-side patch.
metrics=$(aws s3 cp "$S3_URI" - \
  | gunzip \
  | java -jar kafkaUtils.jar \
      --stdin \
      --topicName "$TOPIC" \
      --kafkaBrokers "$BROKERS" \
      $KAFKA_PRODUCER_PROPS \
  | tee /tmp/loader.log \
  | grep -E '^Sent total of' || true)

# On success, the loader itself patches CapturedTraffic to Completed
# with metrics (records loaded, bytes, duration, sourceUri).
# Failure of either pipe stage triggers set -euo pipefail and the
# CR is NOT patched -> Started-without-Completed -> next run fails fast.
records=$(echo "$metrics" | sed -E 's/.*Sent total of ([0-9]+).*/\\1/')
kubectl patch capturedtraffic "$CT_NAME" --type merge --subresource=status -p '{
  "status": {
    "phase": "Ready",
    "loadCompleted": true,
    "loadStats": {"records": '"\${records:-0}"', "sourceUri": "'"\$S3_URI"'", "completedAt": "'"$(date -u +%FT%TZ)"'"}
  }
}'
`])
  .addEnv(...))
```

Inputs: `s3Uri`, `awsRegion` (S3 region), `brokers`, `topic`, plus the same Kafka-auth ConfigMap/Secret mounts the proxy and replayer use (`setupCapture.ts:437-446`, `replayer.ts:327-338`). Auth comes from the resolved `kafkaConfig`, same as the proxy's.

**S3 auth**: the loader runs under the workflow executor / migration-console service account. Assume the bound principal already has S3 read on the bucket (set up by the user when the migration environment was deployed). No `s3RoleArn` field on `S3_CAPTURED_TRAFFIC_SOURCE`, no `aws sts assume-role` in the script — keep it simple. If users need per-bucket roles down the road, that's a follow-up.

**c) New top-level wrapper `setupSingleS3Source` in `fullMigration.ts`.** Sits next to `setupSingleProxy` (line 183). Steps:

```
reconcileCapturedTrafficTopic     // shared with proxy path
  → patchCapturedTrafficReady      // happens at end of step above
loadS3IntoTopic                    // S3-specific
```

**d) `runSingleReplay` (549) replace `waitForProxy` (607)** with `waitForCapturedTraffic`. Resource name = `<fromProxy>-topic` (matches the CR naming at `fullMigration.ts:738`). This unifies the two paths and drops the replayer's CaptureProxy dependency. `createSnapshot`'s `waitForProxyDeps` (256-276) keeps using `waitForCaptureProxy` — unchanged.

**e) `main` step list (672)** gains a `createS3Source` step in the same step group, looping over `config.s3TrafficLoaders` (mirrors the existing `createProxy` step at line 712).

### 7. CapturedTraffic phase semantics & exactly-once via create-not-patch

The phase model stays minimal: `Ready` / `Error`, plus the implicit "absent or set but not yet Ready" state. Exactly-once is enforced by **how we write to the CR**, not by adding a new state machine.

**Two-step CR pattern, leveraging Argo `create` actions and VAP gates:**

1. Workflow does a `create` (not `apply`) of the `CapturedTraffic` CR with `spec.loadStarted = true` (and the proxy/loader source metadata baked into spec).  A VAP blocks creation if a CR with the same name already exists with `loadStarted=true` but `status.loadCompleted != true` — that's the "started but not finished" lockout. Argo's `create` action fails idempotently if the resource already exists, which is fine for the `loadStarted+loadCompleted` case (we want to skip).
2. After the loader pipe exits zero, the loader script itself patches `status.loadCompleted=true` plus `status.loadStats` (records, bytes, duration, sourceUri) and flips `status.phase=Ready`.

This means there is exactly one writer of `loadCompleted=true` (the loader script, only on success), and the existence-check on `create` handles re-run skipping. No ResourceManagement template needs to reason about partial-vs-complete states; the CR's existence and the presence/absence of `status.loadCompleted` is the state.

**Add to the `CapturedTraffic` CR schema:**
- `spec.loadStarted: boolean` — true when the workflow has committed to running a loader against this CR.
- `spec.sourceKind: "proxy" | "s3"` — selector that the controller and VAP use to enforce shape (e.g., `s3SourceUri` only valid when `sourceKind="s3"`).
- `spec.s3SourceUri?: string` — recorded once at create time. Editing this field after creation is blocked by VAP; users must reset the CR (delete + recreate) to point at a different file.
- `status.loadCompleted: boolean` — set true by the loader on success.
- `status.loadStats: { records, bytes, durationMs, completedAt, sourceUri }` — recorded by the loader on success, surfaces useful observability data in `kubectl describe`.

**VAP gates** (under `kubernetesCRDs/`/`opensearch-migrations/`, mirroring the existing VAPs that protect the kafka-cluster `approved-replicas=3` pattern):
- Block updates to `spec.s3SourceUri`, `spec.sourceKind`, `spec.loadStarted` after creation.
- Optionally: gate the `create` itself on a unique-name policy if needed (Kubernetes already enforces unique name per namespace, so this is mostly defensive).

#### Live capture path

```
create CapturedTraffic CR (spec.loadStarted=true, spec.sourceKind=proxy)
  → fails idempotently if already exists; that's fine
createKafkaTopic
patchCapturedTrafficReady (phase=Ready, loadCompleted=true, no stats)
                                    ← replayer can wait safely
setupProxy                          ← runs in parallel with replayer
patchCaptureProxyReady / Error
```

For the live path, "load" is conceptually instantaneous (the topic is the buffer; the proxy keeps producing as long as it lives), so the workflow patches `loadCompleted=true` immediately after the topic is created. The state machine is degenerate but uniform.

#### S3 path

```
create CapturedTraffic CR (spec.loadStarted=true, spec.sourceKind=s3,
                           spec.s3SourceUri=...)
  ─ if create fails because CR already exists:
       readCR; check status.loadCompleted
         true  → SKIP loadS3IntoTopic; phase already Ready (idempotent)
         false → FAIL with "load was started but not completed; reset
                 required: kubectl delete capturedtraffic <name>"
  ─ if create succeeds:
       createKafkaTopic
       loadS3IntoTopic                          ← producer pipe runs
                                                  (replayer waits on
                                                  status.phase=Ready, so
                                                  it does NOT start during
                                                  the load)
       loader script: patch status to:
         { phase=Ready, loadCompleted=true, loadStats={...} }
       [on loader failure]
         set -euo pipefail kills the script
         status.loadCompleted stays absent/false
         pod exits non-zero; workflow records failure
         next workflow run hits the "started but not completed" branch
```

The phase is `Ready` only after a successful load. The replayer's `waitForCapturedTraffic` already keys on `status.phase==Ready` (`resourceManagement.ts:1004-1010`), so it naturally blocks until the S3 load is fully drained — matching the user's "complete only after fully loaded" constraint without any extra logic.

**Rationale:**
- **No two-writer races on `loadCompleted`:** only the loader script ever writes `true`. Argo workflow steps don't pre-emptively patch the CR partway through.
- **No new phase enum:** stays `Ready`/`Error`, matching live capture and other resources.
- **Reset is one command:** `kubectl delete capturedtraffic <name>`. No need to chase down extra status fields. The error message in the "started but not completed" branch names this command.
- **Source-change-blocks-retry:** VAPs reject mutating `spec.s3SourceUri`, so changing the URI requires a reset just like a partial-failure recovery.

#### New ResourceManagement template

Just one addition: `tryCreateCapturedTrafficResource` (mirroring `upsertCapturedTrafficResource` at `resourceManagement.ts:388-412`, but using `action: "create"` and surfacing "AlreadyExists" as a specific output flag rather than failing the step). The S3 wrapper template branches on that flag.

CaptureProxy phases (`patchCaptureProxyReady`, `patchCaptureProxyError` at `setupCapture.ts:786-808`) stay as-is — they remain the source of truth for snapshot gating and operator visibility. The replayer no longer reads them; the *snapshot* dependency at `fullMigration.ts:256-276` does.

## Verification plan

1. **Schema unit tests** (`schemas/tests/userSchema.{valid,invalid}.test.ts`):
   - Valid: `traffic.s3Sources` config with all required fields; replayer pointing at it.
   - Invalid: bad S3 URI; missing `awsRegion`; name collision between `proxies` and `s3Sources`; replayer `fromProxy` referencing a non-existent name.
2. **Transformer tests** (`config-processor/tests/migrationConfigTransformer.test.ts`):
   - `s3Sources` flows through to `s3TrafficLoaders` array.
   - Topic name aggregated into auto-created KafkaCluster topics.
   - Replayer's `kafkaConfig` resolved uniformly whether `fromCapturedTraffic` references a proxy or s3Source entry.
   - Editing a proxy's `setHeader` rotates the corresponding CapturedTraffic's `checksumForReplayer` (covers the percolation-bug fix).
3. **Round-trip integration test (no new Java needed)** (`libraries/kafkaUtils/`):
   - Confirm `KafkaLoaderFromFile` + the export from `kafkaExport.sh` round-trip cleanly. Existing `Base64Formatter`/`KafkaLoader` tests likely cover most of this; verify there's a smoke test exercising the gzipped path.
4. **End-to-end** (`orchestrationSpecs/packages/e2e-orchestration-tests`):
   - Add an e2e fixture using the LocalStack S3 endpoint already installed for local builds via Helm `valuesDev.yaml`. Loader's `S3_URI` and `awsRegion` resolve against LocalStack the same way other S3-touching workflow steps do today.
   - Run a workflow with no live capture: assert
     - **no** `CaptureProxy` CRD,
     - `CapturedTraffic` CRD reaches `Ready`,
     - `loadS3IntoTopic` Job runs and exits 0,
     - `TrafficReplay` succeeds against a target.
   - **Idempotency test**: re-submit the same workflow against an already-`Completed` CapturedTraffic; assert the loader is **skipped** and replayer still succeeds.
   - **Partial-failure-blocks-retry test**: kill the loader Job mid-run, re-submit the workflow; assert the workflow fails fast with a "reset required" message and the loader does **not** run a second time (i.e., topic does not contain duplicated records).
   - **Source-change-blocks-retry test**: change the `s3Uri` on a `Completed` CapturedTraffic and re-submit; assert the workflow fails fast with a "source config changed; reset required" message.
5. **Local smoke test (manual)**:
   - Run `kafkaExport.sh` in a live capture run, then feed the resulting `.proto.gz` back via the new `traffic.s3Sources` config and verify the second target ends up matching the first replay's output.

## Running it today

For exporting captured traffic to S3 (`kafkaExport.sh`) and the inverse — loading an export back onto a Kafka topic (`kafkaImport.sh`) — see [`migrationConsole/README.md`](../migrationConsole/README.md) and the full reference in [`migrationConsole/kafkaCmdRef.md`](../migrationConsole/kafkaCmdRef.md). Both scripts ship in the migration-console image.

The third operation — **inspecting an already-loaded topic in dump mode** — runs through the traffic_replayer image directly, with no separate console script. `dump-raw` decodes each `TrafficStream` and prints one summary line per record. No traffic is sent to any target — useful for verifying a topic is well-formed before pointing a real replayer at it.

```shell
kubectl --context kind-ma -n ma run rt-dumpraw \
    --rm -i \
    --image=localhost:5002/migrations/traffic_replayer:latest \
    --image-pull-policy=Always \
    --restart=Never \
    --overrides='{"spec":{"serviceAccountName":"argo-workflow-executor"}}' \
    -- \
    --mode dump-raw \
    --kafkaBrokers default-kafka-bootstrap:9092 \
    --kafkaTopic dump
```

Each record streams as one line:
```
[<minEpoch>-<maxEpoch>]  <relTime>s  <duration>s  p:<partition> o:<offset>  ncs:<channelId>  W[<bytes>]: <preview>...  R[<bytes>]: <preview>...  EOM
```

Other modes: `dump-http` (reconstruct request/response pairs without sending), `dump-both` (raw + http on the same record).
