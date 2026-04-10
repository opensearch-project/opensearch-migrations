# Kafka User Guide

This document explains the intended Kafka user experience for migration
workflows that use Strimzi-managed Kafka resources.

It is written for a user who understands Kafka and Strimzi and wants to
configure Kafka in Strimzi terms, without having to manually reconfigure proxy,
replayer, or console components around those resources.

## What You Should Expect

The intended user experience is:

1. You provide partial Kafka configuration using a Strimzi-shaped model.
2. The workflow fills in sane defaults for the Kafka settings you did not
   specify.
3. The merged Kafka config is validated against a unified JSON Schema.
4. The workflow creates or references Kafka resources on your behalf.
5. The workflow discovers how its own components should connect to Kafka.
6. Proxy, replayer, and console consume that resolved connection profile.

The key point is that you should not need to:

- type every Kafka setting just to get a usable cluster
- repeat Kafka configuration in a second migration-specific format
- manually wire Kafka listener/auth details into each migration application

## Baseline Model

The workflow should provide a baseline Kafka model built around Strimzi `0.50`.

That baseline model exists for two reasons:

- to give users defaults for common Kafka settings
- to provide a stable implementation contract for the workflow

The project should treat Strimzi `0.50` as:

- the baseline schema/defaulting model baked into code
- the minimum supported version

Later Strimzi versions may still work, but validation should ultimately be
driven by the live CRDs deployed in the target environment.

## Current Status

The workflow-managed Kafka path now works for both:

- unauthenticated internal Kafka
- SCRAM-protected internal Kafka backed by Strimzi-managed `KafkaUser`
  credentials

Implemented today:

- workflow-owned `autoCreate.auth` selection for managed clusters
- explicit auth config for existing Kafka clusters
- unified schema generation and validation for Strimzi-derived Kafka sections
- runtime listener/bootstrap/auth discovery for workflow-managed clusters
- normalized internal Kafka profile fields carried through orchestration
- workflow-managed `KafkaUser` creation for auto-created SCRAM clusters
- secret-backed SCRAM client configuration for proxy and replayer
- console SCRAM support for workflow-managed Kafka, with the current console
  workflow-config reader deriving the managed bootstrap endpoint
  deterministically from the workflow config
- end-to-end minikube validation of:
  - `proxyWithoutTls.wf.yaml`
  - `proxyWithoutTlsScram.wf.yaml`

Still incomplete:

- initialization now materializes the baseline auto-created Kafka defaults, but
  the rendering path is still being cleaned up so those merged values become
  the single source of truth end to end
- the deployment success condition currently uses `status.readyReplicas > 0`
  for workflow-managed proxy and replayer Deployments; that is correct for the
  current single-replica flows but is intentionally conservative rather than a
  full multi-replica readiness contract

## What You Configure

For an auto-created Kafka cluster, the user-facing Kafka configuration lives
under:

`kafkaClusterConfiguration.<name>.autoCreate`

The intended primary settings are:

- `auth`
- `clusterSpecOverrides`
- `nodePoolSpecOverrides`
- `topicSpecOverrides`

These correspond to Strimzi resources:

- `clusterSpecOverrides` -> `Kafka.spec`
- `nodePoolSpecOverrides` -> `KafkaNodePool.spec`
- `topicSpecOverrides` -> `KafkaTopic.spec`

`auth` is intentionally workflow-owned rather than raw Strimzi passthrough. It
defines the migration application's managed listener/auth contract for an
auto-created cluster.

Currently supported managed values:

- `type: none`
- `type: scram-sha-512`

## Defaults And Overrides

The intended UX is not “raw opaque passthrough.”

Instead, the workflow should behave more like the existing compound resource
settings in migration config:

- the workflow provides sensible defaults
- the user overrides only the portions they care about
- the final result is a deep merge of defaults plus user input

Examples of Kafka settings that should have workflow defaults rather than being
required from the user every time:

- node pool roles
- basic storage shape
- replication defaults
- min in-sync replica settings
- entity operator enablement
- workflow-required listener/auth structure

That means a Kafka/Strimzi-savvy user should be able to say:

- “I want a JBOD node pool with these volumes”
- “I want a different broker config value”
- “I want SCRAM auth”

without also having to restate every default that the workflow already knows
how to supply.

## Example User Configs

### Minimal auto-created Kafka config

The intended minimal experience is:

```yaml
kafkaClusterConfiguration:
  default:
    autoCreate: {}
```

That should be enough for the workflow to create a usable Kafka cluster using
the baseline defaults.

### Auto-created Kafka with a few targeted overrides

The intended override experience is:

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
      clusterSpecOverrides:
        kafka:
          config:
            min.insync.replicas: 2
            message.max.bytes: 2097152
      topicSpecOverrides:
        partitions: 12
        config:
          cleanup.policy: compact
```

In that example, the user changes only the settings they care about. The
workflow still supplies the baseline defaults for the rest.

## Validation Model

The intended validation model is hybrid:

1. Code provides a baseline typed/defaulted model based on Strimzi `0.50`.
2. User overrides are merged into that baseline.
3. The merged result is validated against the unified JSON Schema.
4. The preferred unified schema is built from the live Strimzi CRDs deployed in
   the target environment.

This gives you both:

- a good defaulting UX
- version-aware validation against the actual deployed operator

## Live Schema Versus Fallback Schema

The preferred schema used for validation is built from the actual Strimzi CRDs
deployed in the target environment.

That avoids validating against the wrong operator version.

The intended release/deployment flow is:

1. deploy Strimzi
2. fetch the live Strimzi CRD/OpenAPI schema
3. build the unified migration schema from that live schema
4. publish that schema as a release artifact
5. use that same schema artifact for validation in the environments that target
   that Strimzi deployment

For local development and bootstrap scenarios, a generated local fallback
unified schema may still be used, but only when the fallback path is explicitly
enabled.

That fallback should be treated as:

- a development convenience
- a bootstrap artifact

It should not be treated as equivalent to live-CRD validation.

## What The Workflow Still Owns

Even with Strimzi-shaped configuration, the workflow still owns the values that
tie Kafka into the migration stack:

- resource names
- workflow-required labels and annotations
- generation of migration-specific topics
- listener/auth contract required by migration applications
- how proxy, replayer, and console discover their connection settings
- which credentials/secrets are mounted into those applications

The workflow owns those values so that migration applications can be wired
consistently without forcing users to manually configure each application's
Kafka client settings.

## How Connectivity Should Work

Kafka connectivity for workflow-managed clusters should be derived from the
deployed Strimzi resources.

The workflow should inspect those resources and resolve an application-facing
connection profile from them.

Examples of information that should be discovered this way:

- available listeners
- bootstrap server addresses
- whether a listener uses TLS
- whether a listener requires SASL
- which Kubernetes secret contains generated client credentials
- which trust material a client needs

From a user perspective, that means you should not need to separately tell the
proxy, replayer, and console:

- which bootstrap server to use
- which port to use
- whether Kafka auth is enabled
- where the Kafka credentials are stored

The workflow should infer those details and apply them.

## Authentication

The intended model is that authentication is driven by Kubernetes-managed
materials rather than by asking the user to duplicate credentials in migration
config.

For auto-created Kafka clusters, the workflow should own the logical
application identity model and let Strimzi own credential generation.

The intended strategy is:

1. The workflow defines the Kafka principal that a migration application should
   use.
2. The workflow creates the corresponding `KafkaUser` resource with a
   deterministic name.
3. Strimzi generates the credential `Secret` for that user.
4. The workflow passes the resolved secret reference to proxy, replayer, and
   console tooling.

The default initial model is one shared logical principal:

- `migration-app`

That principal should be rendered into a deterministic `KafkaUser` resource
name:

- `<cluster>-migration-app`

Today, the deterministic managed principal naming is already represented in the
resolved Kafka profile for workflow-managed clusters, and the workflow now
creates the matching `KafkaUser` resource for managed SCRAM clusters. For
proxy and replayer, the resulting password secret and cluster CA secret are now
mounted into the containers and used to build Kafka client properties
automatically.

## Existing Kafka Clusters

Existing Kafka clusters should be treated differently from auto-created ones.

For existing clusters:

- the workflow should not guess Kafka user names
- the workflow should not guess secret names
- the user should provide the explicit Kafka client secret reference that the
  migration applications must use

That split keeps workflow-managed resources deterministic and externally
managed resources explicit.

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

The contract should remain:

- existing Kafka auth is explicit
- secret references are user-provided
- the workflow does not guess which secret to use

## Kafka Identity Resolution

The workflow should resolve Kafka connection information into a normalized
application-facing connection profile.

That resolved profile should include:

- cluster name
- selected listener
- bootstrap servers
- auth type
- Kafka user name
- secret name containing credentials
- CA secret name for trust material when required

A normalized internal profile should look roughly like this:

```json
{
  "cluster": "default",
  "listenerName": "tls",
  "bootstrapServers": "default-kafka-bootstrap:9093",
  "authType": "scram-sha-512",
  "kafkaUserName": "default-migration-app",
  "secretName": "default-migration-app",
  "caSecretName": "default-cluster-ca-cert"
}
```

Migration applications should consume this resolved connection profile rather
than reading raw cluster config or inferring secret names themselves.

## What A User Should Not Need To Do

The intended end state is that a Kafka/Strimzi-savvy user should not need to:

- manually translate Strimzi settings into a second migration-specific Kafka
  format
- type every default Kafka setting for every workflow
- hard-code bootstrap addresses for migration applications
- manually wire auth secrets into every migration component
- understand internal migration workflow implementation details in order to use
  Kafka correctly

If a user knows how they want Kafka configured in Strimzi terms, that should be
enough for the workflow to do the rest.
