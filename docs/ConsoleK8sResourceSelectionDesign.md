# Console Kubernetes Resource Selection Design

## Problem

The `console` command currently treats Kubernetes deployments as a special way
to synthesize the legacy `migration_services.yaml` model. In
`console_link.cli.Context`, Kubernetes availability selects
`Environment.from_workflow_config()`, which loads the workflow ConfigMap through
`WorkflowConfigStore`. `Environment.from_workflow_config()` then applies Python
mapping rules over the raw user workflow config:

- first source cluster from `sourceClusters`
- first target cluster from `targetClusters`
- zero or one Kafka cluster inferred from `kafkaClusterConfiguration` and
  `traffic.proxies`
- a single source proxy inferred from `traffic.proxies`

That shape does not work well once workflows support multiple source clusters,
target clusters, Kafka clusters, proxies, and top-level migration CRs. It also
duplicates transformation knowledge that now belongs in
`orchestrationSpecs/packages/config-processor`.

## Goals

- In Kubernetes mode, stop converting workflow config into a singleton
  `services.yaml` shape in Python.
- Use config-processor output as the authoritative config-to-resource
  transformation path.
- Build a console resource catalog from both desired config and deployed
  top-level migration CRs.
- Make each console command declare the resource kinds it needs.
- Auto-select a resource only when exactly one candidate exists for that role.
- Return clear errors and empty shell completions when no configured resource
  can satisfy a command.
- Require an explicit selector when multiple resources can satisfy a command.
- Keep the legacy `migration_services.yaml` path working for non-Kubernetes
  usage.

## Non-Goals

- Replace the existing Python `Cluster` and `Kafka` runtime clients.
- Change the workflow user config schema.
- Change top-level migration CRD ownership or lifecycle.
- Make every legacy command multi-resource-aware in the first patch. The first
  implementation should focus on `console clusters ...` and `console kafka ...`.

## Current Integration Points

- `migrationConsole/lib/console_link/console_link/cli.py`
  - detects Kubernetes mode
  - wires Click commands to `Environment`
  - currently resolves singleton `ctx.env.source_cluster`, `target_cluster`,
    `proxy`, and `kafka`
- `migrationConsole/lib/console_link/console_link/environment.py`
  - contains the current workflow-config-to-console-model conversion logic
- `migrationConsole/lib/console_link/console_link/workflow/services/script_runner.py`
  - already runs bundled config-processor commands with
    `/root/configProcessor/index.js`
- `orchestrationSpecs/packages/config-processor/src/resolveMigrationResources.ts`
  - emits `ResolvedMigrationResources` from user config or transformed workflow
    config
- `orchestrationSpecs/packages/config-processor/src/resolvedMigrationResources.ts`
  - emits top-level resource parameters for `KafkaCluster`, `CapturedTraffic`,
    `CaptureProxy`, `DataSnapshot`, `SnapshotMigration`, and `TrafficReplay`
- `MigrationRun.spec.resolvedConfig`
  - records the effective workflow config and resolved resource parameters for
    a deployed run

## Proposed Architecture

### 1. Add A Config-Processor Console Projection

Add a small config-processor command, tentatively:

```bash
index.js resolveConsoleResources --user-config <file>
index.js resolveConsoleResources --transformed-config <file>
index.js resolveConsoleResources --resolved-config <file>
```

The command should build on `MigrationConfigTransformer` and
`buildResolvedMigrationResources()`, then emit a console-facing catalog. This
keeps the workflow-config-to-console-resource rules in TypeScript.

Suggested output shape:

```json
{
  "formatVersion": 1,
  "workflowName": "migration",
  "sources": [
    {
      "refName": "source1",
      "clientConfig": {
        "endpoint": "https://source:9200",
        "allow_insecure": true,
        "version": "ES 7.10",
        "basic_auth": {"k8s_secret_name": "source-creds"}
      },
      "proxy": {
        "refName": "capture-proxy",
        "k8sName": "capture-proxy",
        "clientConfig": {
          "endpoint": "https://capture-proxy:9201",
          "allow_insecure": true,
          "basic_auth": {"k8s_secret_name": "source-creds"}
        }
      }
    }
  ],
  "targets": [
    {
      "refName": "target1",
      "clientConfig": {
        "endpoint": "https://target:9200",
        "allow_insecure": true,
        "basic_auth": {"k8s_secret_name": "target-creds"}
      }
    }
  ],
  "kafkas": [
    {
      "refName": "default",
      "k8sName": "default",
      "aliases": [
        "kafkacluster.default",
        "kafkaclusters.migrations.opensearch.org/default"
      ],
      "runtime": {
        "type": "strimzi",
        "clusterName": "default",
        "authType": "scram-sha-512",
        "listenerName": "tls",
        "usernameSecret": "default-migration-app",
        "caSecret": "default-cluster-ca-cert"
      }
    }
  ],
  "consumerGroups": [
    {
      "name": "replayer-target1",
      "targetRef": "target1",
      "kafkaRef": "default",
      "replayRef": "capture-proxy-target1-replay"
    }
  ]
}
```

Notes:

- `clientConfig` intentionally matches the existing Python `Cluster` model
  config. That keeps Python from knowing the workflow user schema.
- For Strimzi-managed Kafka, TypeScript should emit runtime lookup metadata, not
  the resolved broker password. Python will continue to read the Strimzi Kafka
  status and Secret at command runtime.
- Existing Kafka configs can emit a direct Kafka client config, such as
  `{"broker_endpoints": "...", "msk": null}` or
  `{"broker_endpoints": "...", "standard": null}`.

### 2. Add A Python `ConsoleResourceCatalog`

Add a new Python module, tentatively
`console_link/k8s_resource_catalog.py`, with these responsibilities:

- load the desired workflow config from the ConfigMap, when present
- call `resolveConsoleResources --user-config` for the desired config
- list live top-level migration CRs in the current namespace:
  - `kafkaclusters`
  - `capturedtraffics`
  - `captureproxies`
  - `datasnapshots`
  - `snapshotmigrations`
  - `trafficreplays`
  - `migrationruns`
- for each `MigrationRun.spec.resolvedConfig`, call
  `resolveConsoleResources --resolved-config`
- merge desired and deployed resources into one catalog
- preserve origin metadata: `configured`, `deployed`, or both
- overlay live CR status and Kubernetes names onto catalog entries

Live top-level CRs should be treated as authoritative for deployed identity and
phase. `MigrationRun.spec.resolvedConfig` or the current ConfigMap should remain
the source for source/target connection details, because source and target
clusters are not themselves top-level migration CRs.

### 3. Merge Rules

Catalog entries should be keyed by resource role and reference name:

- source cluster: `source:<refName>`
- target cluster: `target:<refName>`
- proxy cluster: `proxy:<refName>`
- Kafka cluster: `kafka:<refName>`

Each entry may also have Kubernetes aliases:

- `kafkacluster.<name>`
- `captureproxy.<name>`
- `trafficreplay.<name>`
- `<plural>.migrations.opensearch.org/<name>`

When desired config and deployed config define the same key:

- keep one entry
- mark origin as both `configured` and `deployed`
- attach the live phase/resource name from the deployed CR
- prefer deployed resource data when pulling effective setting values
- use the current ConfigMap only for config-only resources that have not been
  deployed yet

Deployed-resource precedence is only a value-source rule. It must not hide
selector ambiguity. If multiple deployed items can be referenced by the same
name, the command must fail and require a selector that uniquely identifies the
intended resource.

If two deployed resources map to the same role/ref but different workflows or
run numbers, keep them as ambiguous candidates. The selector error should ask
for a Kubernetes alias.

When loading historical resolved config from `MigrationRun` resources, use the
latest `MigrationRun` instance by default. Latest should be determined by the
highest `spec.runNumber`, falling back to newest `spec.timestamp` or Kubernetes
creation timestamp if run number is unavailable.

## Command Resource Requirements

Each command should declare the resources it needs and let shared helpers handle
selection, completion, and error messages.

Suggested helper concepts:

```python
class ResourceRole(Enum):
    SOURCE = "source"
    TARGET = "target"
    PROXY = "proxy"
    KAFKA = "kafka"

def resolve_required(ctx, role: ResourceRole, selector: str | None):
    ...
```

Selection behavior:

- 0 candidates: error with an actionable message
- 1 candidate: auto-select
- more than 1 candidate and no selector: error listing valid selectors
- selector provided: match by config ref name, short alias, or full Kubernetes
  resource alias
- selector matches multiple candidates: error and request a more specific alias

Completion behavior:

- completion functions return no values when there are zero candidates
- completion functions return ref names and Kubernetes aliases for matching
  candidates
- command groups still return invocation-time errors, because Click does not
  make dynamic command hiding cheap or reliable

## Initial Command Matrix

| Command | Resource requirements | Selector behavior |
| --- | --- | --- |
| `console clusters cat-indices` | source cluster and target cluster | Auto-select each role when exactly one exists. If multiple sources or targets exist, require `--source` and/or `--target`. |
| `console clusters connection-check` | source cluster and target cluster | Same as `cat-indices`. |
| `console clusters curl` | one source, target, or proxy cluster | Keep the existing positional role for compatibility, but require a ref selector when that role has multiple candidates. |
| `console clusters generate-data` | one source, target, or proxy cluster | Same as `curl`. |
| `console clusters run-test-benchmarks` | one source, target, or proxy cluster | Require a selector for the chosen role when ambiguous. |
| `console clusters run-aoss-test-benchmarks` | one source cluster | Require `--source` when ambiguous. |
| `console clusters clear-indices` | one source or target cluster | Require `--source` or `--target` when ambiguous. |
| `console kafka ...` | one Kafka cluster | Add `--kafka <ref-or-k8s-resource>` to every Kafka subcommand. Auto-select only when exactly one Kafka is configured. |

Kafka topics and consumer groups are runtime Kafka objects, not config
resources. Topic and consumer-group completions should run against the selected
Kafka cluster. If multiple Kafka clusters exist and `--kafka` is omitted,
completion should return no topic/group values and invocation should error with
the available Kafka selectors.

## User-Facing Examples

One source and one target:

```bash
console clusters cat-indices
```

Multiple sources and one target:

```bash
console clusters cat-indices --source source1
```

Multiple Kafka clusters:

```bash
console kafka list-topics --kafka default
console kafka describe-consumer-group --kafka kafkacluster.default replayer-target1
```

No Kafka configured:

```text
Error: No Kafka cluster is configured for this deployment.
Configure kafkaClusterConfiguration and traffic.proxies, or deploy a KafkaCluster resource first.
```

Ambiguous source:

```text
Error: Multiple source clusters are configured: sourceA, sourceB.
Re-run with --source <name>. Valid selectors: sourceA, sourceB.
```

## Environment Integration

In Kubernetes mode:

```python
class Context:
    if can_use_k8s_config_store() and not force_use_config_file:
        self.env = Environment.from_k8s_resource_catalog(...)
```

`Environment.from_k8s_resource_catalog()` should:

- build `ConsoleResourceCatalog`
- attach it as `env.resources`
- populate legacy singleton attributes only when unambiguous:
  - `source_cluster`
  - `target_cluster`
  - `proxy`
  - `kafka`
  - `kafka_consumer_groups`

This allows old commands to keep working while migrated commands use
`env.resources`.

In services.yaml mode:

- `Environment(config_file=...)` should keep the current behavior
- optionally synthesize a single-entry `ConsoleResourceCatalog` from the legacy
  attributes so command helpers can be shared

## Runtime Kafka Resolution

For Strimzi-managed Kafka, the catalog should contain the KafkaCluster ref name
and auth mode. Python runtime resolution should stay in Python because it reads
live Kubernetes status and Secrets:

- SCRAM:
  - read bootstrap server from `Kafka.status.listeners[*].bootstrapServers`
  - read password from `<cluster>-migration-app`
  - read CA cert from `<cluster>-cluster-ca-cert`
  - build `ScramKafka`
- Plain:
  - read `plain` listener bootstrap
  - build `StandardKafka`
- Existing/MSK:
  - use config-processor-emitted client config directly

## Test Plan

TypeScript:

- `resolveConsoleResources` emits source, target, proxy, Kafka, aliases, and
  consumer groups for:
  - one source/target
  - multiple sources/targets
  - existing Kafka
  - auto-created Kafka with SCRAM
  - auto-created Kafka with auth `none`
- `resolveConsoleResources --resolved-config` accepts
  `MigrationRun.spec.resolvedConfig`

Python unit tests:

- catalog merge from ConfigMap-only resources
- catalog merge from MigrationRun-only resources
- live CR overlay adds aliases and phase
- zero/one/multiple selection behavior
- selector matching by ref name, short alias, and full Kubernetes resource name
- Kafka command errors when no Kafka is configured
- Kafka command requires `--kafka` when multiple Kafka clusters are configured
- `clusters cat-indices` requires only the ambiguous side selector

CLI tests:

- shell completion returns no Kafka topic/group values when Kafka selection is
  ambiguous or absent
- shell completion returns resource selector values for `--source`, `--target`,
  and `--kafka`
- legacy services.yaml tests still pass

## Implementation Sequence

1. Add `resolveConsoleResources` in config-processor and tests.
2. Add Python `ConsoleResourceCatalog` and selection helpers.
3. Wire Kubernetes `Environment` construction through the catalog while keeping
   legacy singleton attributes for unambiguous cases.
4. Migrate `console kafka` commands to `--kafka`.
5. Migrate `console clusters cat-indices` and `connection-check` to
   `--source` and `--target`.
6. Migrate the remaining `console clusters` commands that accept cluster roles.
7. Remove or quarantine obsolete workflow-config-to-services mapping helpers
   after command coverage is complete.

## Resolved Design Decisions

- Deployed resources take precedence over ConfigMap-derived desired config when
  pulling effective setting values.
- Precedence does not collapse ambiguity. If multiple items share a selector
  name, the user must choose one explicitly with a unique selector.
- The catalog uses the latest `MigrationRun` instance by default when reading
  deployed resolved config.
- Keep both the existing `--cluster source|target|proxy` style and the new
  role-specific selectors for now. The story is simpler even when the command is
  slightly longer to type.
