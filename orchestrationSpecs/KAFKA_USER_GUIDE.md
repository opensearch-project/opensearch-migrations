# Kafka User Guide

This document explains how Kafka is meant to work for migration workflows that
use Strimzi-managed Kafka resources.

It is written for a user who understands Kafka and Strimzi, and wants to
control Kafka configuration directly, without having to learn a large amount of
migration-internal wiring.

## What You Should Expect

If you configure Kafka through `orchestrationSpecs`, the intended user
experience is:

1. You describe Kafka resources using Strimzi-shaped configuration.
2. Your config is validated before the workflow starts creating Kafka
   resources.
3. The workflow creates or references Kafka resources on your behalf.
4. The workflow discovers how its own components should connect to Kafka.
5. Proxy, replayer, and console tooling consume that discovered connection
   profile automatically.

The important point is that you should not need to configure Kafka once in
Strimzi terms and then re-express the same settings in a second migration-only
format just so the migration components can connect.

## What You Configure

For an auto-created Kafka cluster, the user-facing Kafka configuration lives
under:

`kafkaClusterConfiguration.<name>.autoCreate`

The primary Kafka settings are:

- `clusterSpecOverrides`
- `nodePoolSpecOverrides`
- `topicSpecOverrides`

These map directly to Strimzi resource `spec` sections:

- `clusterSpecOverrides` -> `Kafka.spec`
- `nodePoolSpecOverrides` -> `KafkaNodePool.spec`
- `topicSpecOverrides` -> `KafkaTopic.spec`

This is intentionally Strimzi-shaped. If you know how to configure Strimzi, you
should be able to apply that knowledge here directly.

## What The Workflow Still Owns

The workflow still owns some fields even when Kafka is configured in
Strimzi-shaped form.

Those workflow-owned fields are the parts that tie Kafka into the rest of the
migration stack:

- resource names
- workflow-required labels and annotations
- generation of migration-specific topics
- how proxy, replayer, and console discover their connection settings
- which credentials/secrets are mounted into those applications

The workflow owns those values so that migration applications can be wired
consistently without making every user manually configure each application's
Kafka client settings.

## How Connectivity Should Work

The intended model is that Kafka connectivity is derived from the deployed
Strimzi resources.

That means the workflow should inspect the resources after creation and resolve
the application connection profile from them.

Examples of information that should be discovered this way:

- available listeners
- bootstrap server addresses
- whether a listener uses TLS
- whether a listener requires SASL or other auth
- which Kubernetes secret contains generated client credentials
- which trust material a client needs

From a user perspective, that means you should not need to separately tell the
proxy, replayer, and console:

- which bootstrap server to use
- which port to use
- whether Kafka auth is enabled
- where the Kafka credentials are stored

The workflow should infer those details and apply them.

## Listener Selection

If a Kafka cluster exposes multiple valid listeners, the workflow needs a
selection policy.

The intended default is:

- choose the first usable listener

If a specific deployment needs a different choice, the workflow may support an
escape hatch that lets a user provide an explicit Kafka client override.

The default path should still be automatic discovery.

## Authentication

The intended model is that authentication is driven by Kubernetes-managed
materials rather than by asking the user to manually duplicate credentials into
migration config.

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

If the workflow later needs separate principals for proxy, replayer, or console,
that can expand into names such as:

- `<cluster>-proxy`
- `<cluster>-replayer`
- `<cluster>-console`

The key point is that users should not need to guess or manually replicate the
resulting secret names for workflow-managed Kafka users.

This keeps Kafka auth aligned with the deployed Strimzi resources while keeping
the migration applications on a stable, workflow-owned identity contract.

## Existing Kafka Clusters

Existing Kafka clusters should be treated differently from auto-created ones.

For existing clusters:

- the workflow should not guess Kafka user names
- the workflow should not guess secret names
- the user should provide the explicit Kafka client secret reference that the
  migration applications must use

That split keeps managed resources deterministic and externally managed
resources explicit.

The intended user-facing shape for an existing Kafka cluster should be explicit
about auth material. Conceptually:

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

The exact field names may evolve, but the contract should remain the same:

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

In other words, migration applications should consume a resolved connection
profile, not raw Strimzi resource naming conventions.

A normalized internal profile should look roughly like this:

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

That resolved profile is the contract migration applications should consume.
They should not each independently infer listener selection, user naming, or
secret naming from raw Strimzi resources.

## Validation

Kafka-related user config should be validated before any resources are created.

The validation contract is one unified JSON Schema that includes:

- the migration workflow config shape
- selected Strimzi schema fragments for Kafka pass-through sections

This is important because it allows:

- pre-flight config validation
- editor integration
- release-time schema artifacts
- agent/tooling assistance from one schema instead of multiple ad hoc rules

## Live Strimzi Schema

The preferred schema used for validation is built from the actual Strimzi CRDs
deployed in the target environment.

That avoids baking in assumptions about a different Strimzi version.

The intended release/deployment flow is:

1. deploy Strimzi
2. fetch the live Strimzi CRD/OpenAPI schema
3. build the unified migration schema from that live schema
4. publish that schema as a release artifact
5. store the same schema in the cluster for runtime validation

## Fallback Schema

For local development, bootstrapping, or environments where live Strimzi schema
is not available yet, a checked-in fallback unified schema may be used.

That fallback exists to make development possible, but it should not be treated
as equivalent to a schema built from live CRDs.

The expected production path is still validation against the schema derived from
the Strimzi version actually running in the environment.

## What A User Should Not Need To Do

The intended end state is that a Kafka/Strimzi-savvy user should not need to:

- manually translate Strimzi settings into a second migration-specific Kafka
  format
- hard-code bootstrap addresses for migration applications
- manually wire auth secrets into every migration component
- understand internal migration workflow implementation details in order to use
  Kafka correctly

If a user knows how they want Kafka configured in Strimzi terms, that should be
enough for the workflow to do the rest.
