# Kafka Implementation Plan

This document describes the intended end state for Kafka setup and connectivity
in `orchestrationSpecs`, and the current implementation gaps that need to be
closed to get there.

It is written as an implementation reference for developers and agents.

## End-State Contract

The intended end state is:

1. Users configure Kafka resources using Strimzi-shaped `spec` fragments.
2. The migration configuration is validated against one unified JSON Schema.
3. The unified schema is built from live Strimzi CRDs when available.
4. The workflow creates or references Kafka resources.
5. The workflow discovers Kafka connectivity from deployed Strimzi resources.
6. Proxy, replayer, and console are configured from a normalized connection
   profile derived from those resources.

The workflow should still own resource names and migration-specific wiring, but
it should not rely on fixed bootstrap strings or duplicated user-entered Kafka
client settings.

## User-Facing Kafka Contract

The primary user-facing Kafka inputs for auto-created clusters should be:

- `kafkaClusterConfiguration.<name>.autoCreate.clusterSpecOverrides`
- `kafkaClusterConfiguration.<name>.autoCreate.nodePoolSpecOverrides`
- `kafkaClusterConfiguration.<name>.autoCreate.topicSpecOverrides`

These map to:

- `Kafka.spec`
- `KafkaNodePool.spec`
- `KafkaTopic.spec`

These should remain the main user-controlled Kafka configuration surface.

## Schema Work

### End state

The unified JSON Schema should include:

- orchestration config definitions
- Strimzi-derived definitions for the Kafka pass-through fields

The schema should be generated from live Strimzi CRDs for release/deployment,
with a checked-in fallback for development.

### Current implementation

The current unified schema builder already exists in:

- [packages/schemas/src/unifiedSchemaBuilder.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/schemas/src/unifiedSchemaBuilder.ts)

It currently:

- builds the base migration schema
- injects Strimzi-derived definitions for Kafka `spec` fragments when provided
  with a Strimzi OpenAPI file
- falls back to a generic checked-in schema artifact when no Strimzi schema is
  available

### Remaining work

- wire release/deployment automation to fetch live CRDs and build the unified
  schema artifact
- publish that schema into the target cluster in a ConfigMap
- make runtime tools prefer the live/release-built schema artifact
- treat the checked-in fallback as a development-only path

## Connectivity Discovery Work

### End state

The workflow should discover Kafka connectivity from deployed Strimzi
resources.

The normalized connection profile should include at least:

- selected listener name
- bootstrap servers
- transport mode
- auth mode
- credential secret reference
- trust material reference, if required

That normalized profile should be what proxy, replayer, and console consume.

### Current implementation

There are hard-coded assumptions that need to be removed:

- [packages/config-processor/src/migrationConfigTransformer.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/config-processor/src/migrationConfigTransformer.ts)
  assumes auto-created Kafka is reachable at `<cluster>-kafka-bootstrap:9092`
- [packages/migration-workflow-templates/src/workflowTemplates/setupKafka.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/migration-workflow-templates/src/workflowTemplates/setupKafka.ts)
  reads bootstrap output from `status.listeners[?(@.name=='plain')]`
- [packages/migration-workflow-templates/src/workflowTemplates/setupKafka.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/migration-workflow-templates/src/workflowTemplates/setupKafka.ts)
  forces workflow-owned listener values into the rendered `Kafka` resource

Those shortcuts prevent the workflow from fully respecting discovered Strimzi
listener configuration.

### Required changes

1. Stop generating a fixed bootstrap string in the config transformer for
   auto-created Kafka clusters.
2. Ensure Kafka workflow templates export enough listener/status information to
   support connection-profile resolution.
3. Build a connection-profile resolver that reads the deployed Kafka resource
   status/spec and produces the app-facing client configuration.
4. Replace direct `:9092` assumptions in proxy/replayer/console wiring with the
   resolved connection profile.

## Listener Selection Policy

### End state

When multiple listeners are available, the workflow should pick one
deterministically.

The current intended selection rule is:

- choose the first usable listener

There may later be an explicit escape hatch for a user to provide a literal
Kafka client override string when automatic selection is not sufficient.

### Remaining work

- define "usable" concretely
- codify that rule in the connection-profile resolver
- expose the override only as an escape hatch, not the primary path

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

The initial simplification is:

- one Kubernetes-backed Kafka principal is acceptable initially

That allows the workflow to:

1. create a `KafkaUser`
2. let Strimzi generate the credential secret
3. resolve the resulting secret reference
4. pass the secret to proxy, replayer, and console

### What this avoids

- users manually duplicating Kafka credentials in migration config
- separate auth configuration for each migration application
- drift between deployed Kafka auth and client-side config

### Secret federation strategy

For auto-created Kafka clusters:

- the workflow should choose deterministic `KafkaUser` names
- the workflow should treat the resulting secret name as part of the resolved
  connection profile
- applications should consume the resolved secret reference rather than each
  application independently inferring the secret name

Recommended initial naming pattern:

- shared principal: `<cluster>-migration-app`

Possible future expansion:

- `<cluster>-proxy`
- `<cluster>-replayer`
- `<cluster>-console`

For existing Kafka clusters:

- the workflow should not infer the Kafka user or secret name
- the user should provide the explicit secret reference for the desired
  principal

This keeps workflow-managed clusters deterministic and externally managed
clusters explicit.

The recommended existing-cluster config shape is:

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

This should be treated as explicit user intent. No discovery or naming
convention should override it for existing clusters.

### Normalized connection profile

The implementation should introduce a normalized internal connection profile
shape that all migration applications consume.

Recommended initial shape:

```json
{
  "cluster": "default",
  "listenerName": "tls",
  "bootstrapServers": "default-kafka-bootstrap:9093",
  "authType": "scram-sha-512",
  "kafkaUserName": "default-migration-app",
  "secretName": "default-migration-app"
}
```

Required semantics:

- `cluster`: workflow Kafka cluster label
- `listenerName`: selected listener after discovery/policy resolution
- `bootstrapServers`: final client bootstrap string for applications
- `authType`: auth mode the application must use
- `kafkaUserName`: chosen Kafka principal for workflow-managed clusters, or the
  explicit principal if provided for an existing cluster
- `secretName`: Kubernetes secret holding the client auth material

Proxy, replayer, and console wiring should all consume this normalized object
instead of reading raw cluster config or independently reconstructing Strimzi
resource naming.

### Remaining work

- decide where `KafkaUser` creation lives in the workflow
- add deterministic `KafkaUser` naming rules for auto-created clusters
- define how the chosen principal and resolved secret name are represented in
  the normalized connection profile
- update consumers to read credentials from the resolved secret reference
- define the explicit user-provided secret path for existing Kafka clusters

## Workflow-Owned Values

Even after the refactor, the workflow should continue to own:

- resource names
- required labels and annotations
- migration topic naming
- app-specific config translation from the normalized Kafka connection profile

The workflow should avoid owning deeper Strimzi internals unless those values
are needed to preserve the migration stack's connectivity contract.

## Recommended Implementation Sequence

1. Finish the unified-schema release/deployment path around live Strimzi CRDs.
2. Remove fixed bootstrap assumptions from config transformation.
3. Expand `setupKafka` outputs so that connection discovery can be based on
   deployed resources instead of hard-coded listener names.
4. Introduce a normalized Kafka connection profile object.
5. Update proxy, replayer, and console wiring to consume that normalized
   profile.
6. Add or integrate Kubernetes-backed Kafka user/auth handling.
7. Reduce workflow-owned Kafka overrides to the smallest set still needed for
   migration correctness.

## Exit Criteria

This work is done when all of the following are true:

- a user can configure Kafka in Strimzi terms through the migration config
- that config is validated against a unified schema built from the target
  Strimzi version
- the workflow does not assume a fixed bootstrap service/port for auto-created
  Kafka
- migration applications derive their Kafka client settings from resolved
  Strimzi resource state
- users do not need to duplicate Kafka connectivity/auth settings in a second
  migration-specific configuration path
