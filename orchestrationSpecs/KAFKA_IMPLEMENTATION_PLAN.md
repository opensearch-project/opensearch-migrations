# Kafka Implementation Plan

This document describes the intended Kafka setup and connectivity model for
`orchestrationSpecs`, and the implementation work needed to reach that state.

It is written as an implementation reference for developers and agents.

## End-State Contract

The intended end state is:

1. Users configure Kafka resources using a Strimzi-shaped model.
2. The workflow provides sane defaults based on a baseline Strimzi `0.50`
   contract.
3. User overrides are deep-merged into those defaults.
4. The merged Kafka config is validated against a unified JSON Schema.
5. The preferred unified schema is built from live Strimzi CRDs in the target
   environment.
6. The workflow creates or references Kafka resources.
7. The workflow discovers Kafka connectivity from deployed Strimzi resources.
8. Proxy, replayer, and console are configured from a normalized connection
   profile derived from those resources.

The workflow should still own resource names and migration-specific wiring, but
users should not need to duplicate Kafka connectivity or auth settings in a
second migration-specific format.

## Baseline Versioning Model

The code should use Strimzi `0.50` as its baseline model.

That means:

- workflow defaults are authored against the Strimzi `0.50` shape
- the typed/defaulted config model in code is based on that baseline
- `0.50` is the minimum supported version

Higher Strimzi versions may still work, but validation should ultimately be
driven by the live CRDs deployed in the target environment.

This gives the workflow a concrete baseline for:

- defaults
- deep-merge behavior
- workflow-owned invariant paths

without pretending that the code statically models every future Strimzi field.

## User-Facing Kafka Contract

The primary user-facing Kafka inputs for auto-created clusters should be:

- `kafkaClusterConfiguration.<name>.autoCreate.auth`
- `kafkaClusterConfiguration.<name>.autoCreate.clusterSpecOverrides`
- `kafkaClusterConfiguration.<name>.autoCreate.nodePoolSpecOverrides`
- `kafkaClusterConfiguration.<name>.autoCreate.topicSpecOverrides`

These correspond to:

- `Kafka.spec`
- `KafkaNodePool.spec`
- `KafkaTopic.spec`

`autoCreate.auth` is workflow-owned rather than raw Strimzi passthrough. It
defines the managed auth contract for migration applications and currently
supports `none` and `scram-sha-512`.

The important implementation goal is that these inputs should behave like other
compound resource settings in the repo:

- the workflow provides defaults
- users provide partial overrides
- the final object is deep-merged

This is preferable to treating Kafka config as an opaque black box.

## Defaults Model

The intended UX is that users should not need to spell out every Kafka setting
just to get a reasonable cluster.

Kafka settings that should be defaulted by the workflow include, at minimum:

- node pool roles
- basic storage shape
- replication defaults
- min in-sync replica settings
- entity operator enablement
- workflow-required listener/auth structure

These defaults should be merged with user overrides before validation and
rendering.

The workflow should only own defaults for fields whose paths and semantics it is
willing to support across compatible Strimzi versions.

### Illustrative defaults shape

The exact implementation may evolve, but the intended Zod-side defaults model
should look roughly like this:

```ts
const DEFAULT_AUTO_CREATE_KAFKA = {
  auth: {
    type: "none",
  },
  clusterSpecOverrides: {
    kafka: {
      config: {
        "auto.create.topics.enable": false,
        "offsets.topic.replication.factor": 1,
        "transaction.state.log.replication.factor": 1,
        "transaction.state.log.min.isr": 1,
        "default.replication.factor": 1,
        "min.insync.replicas": 1,
      },
    },
    entityOperator: {
      topicOperator: {},
      userOperator: {},
    },
  },
  nodePoolSpecOverrides: {
    replicas: 1,
    roles: ["controller", "broker"],
    storage: {
      type: "persistent-claim",
      size: "1Gi",
      deleteClaim: true,
    },
  },
  topicSpecOverrides: {
    partitions: 1,
    replicas: 1,
    config: {
      "retention.ms": 604800000,
      "segment.bytes": 1073741824,
    },
  },
};

const KAFKA_CLUSTER_CREATION_CONFIG = z.preprocess(
  value => deepmerge(DEFAULT_AUTO_CREATE_KAFKA, value ?? {}),
  z.object({
    auth: KAFKA_AUTO_CREATE_AUTH_CONFIG,
    clusterSpecOverrides: STRIMZI_KAFKA_SPEC_PARTIAL,
    nodePoolSpecOverrides: STRIMZI_KAFKA_NODE_POOL_SPEC_PARTIAL,
    topicSpecOverrides: STRIMZI_KAFKA_TOPIC_SPEC_PARTIAL,
  })
);
```

The important point is:

- defaults live in code against the Strimzi `0.50` baseline
- users provide partial overrides
- the merged result is what gets validated and rendered

## Schema Work

### End state

The unified JSON Schema should include:

- orchestration config definitions
- Strimzi-derived definitions for the Kafka sections

Validation should follow this order:

1. baseline typed/defaulted config model in code
2. deep merge of workflow defaults plus user overrides
3. validation of the merged result against the unified JSON Schema

The preferred schema should be generated from live Strimzi CRDs for
release/deployment. A generated local fallback may still be used when the
fallback path is explicitly enabled.

### Current implementation

The current unified schema builder exists in:

- [packages/schemas/src/unifiedSchemaBuilder.ts](packages/schemas/src/unifiedSchemaBuilder.ts)

It currently:

- builds the base migration schema
- injects Strimzi-derived definitions for Kafka sections when provided with a
  Strimzi OpenAPI file
- can optionally use a generated fallback artifact when
  `MIGRATION_ALLOW_FALLBACK_UNIFIED_SCHEMA=true` is set

### Remaining work

- move the Kafka config model from broad “opaque passthrough” semantics toward a
  baseline typed/defaulted partial-overrides model
- ensure workflow defaults are merged before schema validation
- wire release/deployment automation to fetch live CRDs and build the unified
  schema artifact
- make runtime tools prefer the live/release-built schema artifact
- keep any generated fallback aligned with the supported baseline schema source

## Templating Model

### End state

Kafka manifests should be rendered from:

- a workflow-owned baseline shape
- user partial overrides
- workflow-owned invariant patches/final overlays

The workflow should avoid injecting one giant dynamic `Kafka.spec` object into
Argo when a more static manifest shape can be preserved.

### Current implementation

The current `Kafka` render path in:

- [packages/migration-workflow-templates/src/workflowTemplates/setupKafka.ts](packages/migration-workflow-templates/src/workflowTemplates/setupKafka.ts)

still builds a highly dynamic merged `Kafka.spec` object and injects it through
Argo resource templating.

This previously caused a live rendering bug where `Kafka.spec` was serialized
as a string instead of an object. That specific bug is now fixed by moving the
workflow-managed cluster render path back to explicit object-shaped `spec`
rendering.

### Remaining work

- continue reducing the dynamic surface of the `Kafka` manifest
- prefer a mostly static manifest shape with smaller injected subtrees
- or use apply-then-patch resource templates so workflow-owned overrides are
  layered after the user/baseline resource is applied
- keep regression tests that assert `Kafka.spec` is rendered as an object-valued
  manifest field, not a quoted string expression

## Connectivity Discovery Work

### End state

The workflow should discover Kafka connectivity from deployed Strimzi
resources.

The normalized connection profile should include at least:

- selected listener name
- bootstrap servers
- auth mode
- credential secret reference
- trust material reference, if required

That normalized profile should be what proxy, replayer, and console consume.

### Current implementation

What is already in place:

- the normalized Kafka profile in orchestration carries:
  - `managedByWorkflow`
  - `listenerName`
  - `authType`
  - `secretName`
  - `kafkaUserName`
- existing Kafka clusters use an explicit auth block instead of relying on the
  workflow to infer secret references
- auto-created Kafka clusters expose an explicit workflow-owned
  `autoCreate.auth` block for managed auth selection
- workflow-managed Kafka clusters are re-read at workflow runtime before proxy
  and replayer setup
- proxy and replayer setup prefer runtime-resolved bootstrap/listener/auth
  values over the transformer's placeholder values
- proxy and replayer now consume runtime-resolved SCRAM secret and CA material
  via generated Kafka client property files
- initialization now materializes the baseline `autoCreate` Kafka defaults
  before workflow generation

What is still transitional:

- [packages/config-processor/src/migrationConfigTransformer.ts](packages/config-processor/src/migrationConfigTransformer.ts)
  still emits placeholder bootstrap values for workflow-managed clusters so the
  pre-runtime config shape remains complete
- `migrationConsole` currently supports managed SCRAM Kafka, but its workflow
  config reader still derives the managed bootstrap endpoint deterministically
  from the transformed config rather than consuming the workflow runtime's
  `readKafkaConnectionProfile` output
- [packages/migration-workflow-templates/src/workflowTemplates/setupKafka.ts](packages/migration-workflow-templates/src/workflowTemplates/setupKafka.ts)
  is still being simplified so it relies entirely on the already-merged
  initialization-time Kafka defaults instead of retaining overlapping fallback
  behavior
- [packages/migration-workflow-templates/src/workflowTemplates/resourceManagement.ts](packages/migration-workflow-templates/src/workflowTemplates/resourceManagement.ts)
  currently selects the first listener exposed by the `Kafka` resource

### Remaining work

- remove the transformer's placeholder bootstrap values once all consumers use
  runtime-resolved Kafka profiles exclusively
- refine the “first usable listener” rule into something more explicit if the
  workflow needs to distinguish between multiple valid listeners
- keep the console workflow schema aligned with the shared `KAFKA_CLIENT_CONFIG`
  contract as Kafka client fields evolve
- decide whether console should continue deriving workflow-managed bootstrap
  endpoints deterministically or move to a runtime-resolved Kafka connection
  profile source

## Authentication And Credentials

### End state

The workflow should manage Kafka client credentials through Kubernetes-native
resources where possible.

The intended identity strategy is:

1. the workflow owns the logical application principal model
2. the workflow creates deterministic `KafkaUser` resource names for
   auto-created Kafka clusters
3. Strimzi generates the corresponding credential `Secret`
4. the workflow resolves and passes that secret reference to migration
   applications

### Current implementation

What is already in place:

- deterministic managed principal naming:
  - `<cluster>-migration-app`
- that naming is reflected in the normalized Kafka profile
- the workflow now creates the matching `KafkaUser` resource for managed SCRAM
  clusters
- proxy and replayer now mount the generated user password secret and cluster
  CA secret and use them for SCRAM client configuration

### Remaining work

- extend `migrationConsole` so standard Kafka can operate with secret-backed
  SCRAM config instead of only unauthenticated standard mode or MSK IAM
- remove remaining placeholder/no-auth assumptions once all consumers use
  resolved auth material end-to-end

## Existing Kafka Clusters

Existing Kafka clusters should remain explicit.

For existing clusters:

- the workflow should not infer Kafka user names
- the workflow should not infer secret names
- the user should provide the explicit secret reference for the desired
  principal

Conceptually:

```yaml
kafkaClusterConfiguration:
  default:
    existing:
      kafkaConnection: broker.example.org:9093
      kafkaTopic: logging-traffic-topic
      auth:
        type: scram-sha-512
        secretName: existing-kafka-user-secret
```

This should be treated as explicit user intent.

## Example User Configs

### Minimal auto-created Kafka config

This is the intended “I just want a working Kafka cluster” experience:

```yaml
kafkaClusterConfiguration:
  default:
    autoCreate: {}
```

The workflow should fill in the baseline defaults for:

- auth mode
- node pool roles and storage
- broker replication defaults
- entity operator
- topic defaults

### Auto-created Kafka with targeted overrides

This is the intended “use the defaults, but change a few meaningful things”
experience:

```yaml
kafkaClusterConfiguration:
  default:
    autoCreate:
      auth:
        type: scram-sha-512
      nodePoolSpecOverrides:
        replicas: 3
        storage:
          type: jbod
          volumes:
            - id: 0
              type: persistent-claim
              size: 100Gi
            - id: 1
              type: persistent-claim
              size: 100Gi
      clusterSpecOverrides:
        kafka:
          config:
            min.insync.replicas: 2
            message.max.bytes: 2097152
          template:
            pod:
              metadata:
                labels:
                  workload-tier: migration
      topicSpecOverrides:
        partitions: 12
        config:
          cleanup.policy: compact
```

In that example:

- the user does not need to restate the baseline listeners
- the user does not need to restate the entity operator block
- the user does not need to restate every replication default
- only the meaningful deviations from the baseline are provided

## Workflow-Owned Values

Even after the refactor, the workflow should continue to own:

- resource names
- required labels and annotations
- migration topic naming
- listener/auth invariants required by migration applications
- app-specific config translation from the normalized Kafka connection profile

The workflow should avoid owning deeper Strimzi internals unless those values
are needed to preserve the migration stack's connectivity contract.

## Recommended Implementation Sequence

1. Move Kafka config handling to a baseline-defaults-plus-partial-overrides
   model based on Strimzi `0.50`.
2. Fix the `Kafka` manifest render path so workflow runs are no longer masked by
   pre-existing Strimzi resources.
3. Finish the unified-schema release/deployment path around live Strimzi CRDs.
4. Extend `migrationConsole` to understand the same resolved secret-backed
   Kafka auth mode.
5. Remove placeholder bootstrap/auth defaults from the transformer once all
   consumers use runtime-resolved connection profiles.
6. Reduce workflow-owned Kafka overrides to the smallest set still needed for
   migration correctness.

## Exit Criteria

This work is done when all of the following are true:

- a user can configure Kafka in Strimzi terms through the migration config
  without restating every default
- the workflow deep-merges baseline defaults with user Kafka overrides
- that merged config is validated against a unified schema built from the
  target Strimzi version
- the workflow does not rely on a giant dynamic `Kafka.spec` injection that can
  serialize incorrectly in Argo
- migration applications derive their Kafka client settings from resolved
  Strimzi resource state
- users do not need to duplicate Kafka connectivity/auth settings in a second
  migration-specific configuration path
