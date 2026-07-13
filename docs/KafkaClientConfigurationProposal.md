# Kafka Client Configuration Proposal

## Context

Traffic capture and replay use Kafka in two modes:

- Workflow-managed Kafka: the migration workflow creates a Strimzi Kafka cluster, topics, users, secrets, and application deployments.
- External Kafka: the customer provides an existing Kafka cluster and the workflow only configures migration applications to connect to it.

These modes should expose different configuration surfaces.

For workflow-managed Kafka, the workflow owns enough of the stack to derive consistent broker, topic, producer, and consumer settings. Users should generally configure broker and topic shape through Strimzi-oriented workflow fields, or through future high-level workflow fields that derive related client settings.

For external Kafka, the workflow cannot inspect or enforce broker settings. Customers need a controlled way to pass Kafka producer and consumer client properties into the capture proxy and traffic replayer.

## Initial Scope

Implement external-cluster Kafka client properties first.

The initial implementation should:

- Add structured external Kafka `clientProperties.producer` and `clientProperties.consumer` maps.
- Validate those maps before the Argo workflow runs.
- Reject workflow-owned and application-contract keys.
- Render static Java `.properties` file content during config processing.
- Mount that rendered file through the existing workflow-owned ConfigMap path.
- Use the existing Java application property-file arguments:
  - Capture proxy: `--kafkaPropertyFile`
  - Traffic replayer: `--kafkaTrafficPropertyFile`

The initial implementation should not:

- Add arbitrary client properties to workflow-managed Strimzi Kafka.
- Require Java capture proxy or replayer changes.
- Require Argo-time `sprig.merge`, line concatenation, or property-file deduplication.
- Require customers to build custom images or mutate pods to mount property files.
- Introduce a Kafka client property allowlist.

This is enough for bring-your-own Kafka clusters while avoiding a broader managed-Strimzi product surface before there is a high-level setting that needs it.

## Current Behavior

The workflow currently handles Kafka configuration in three separate places.

### Managed Cluster and Topic Configuration

For workflow-managed Kafka, users can provide Strimzi-shaped overrides:

```yaml
kafkaClusterConfiguration:
  managed:
    autoCreate:
      clusterSpecOverrides:
        kafka:
          config:
            message.max.bytes: 8388608
      nodePoolSpecOverrides:
        replicas: 3
      topicSpecOverrides:
        partitions: 24
        config:
          max.message.bytes: 8388608
```

The workflow uses these values to create Strimzi `Kafka`, `KafkaNodePool`, and `KafkaTopic` resources. This is sufficient for broker and topic settings when the workflow owns Kafka.

These Strimzi values do not flow into proxy or replayer Kafka client properties. For example, setting `message.max.bytes` under `clusterSpecOverrides.kafka.config` changes only the generated Strimzi `Kafka` resource. It does not automatically set:

- Proxy producer `max.request.size`
- Replayer consumer `fetch.max.bytes`
- Replayer consumer `max.partition.fetch.bytes`

For workflow-managed Kafka, that gap should be closed through future derived workflow settings rather than arbitrary raw client properties.

### External Connection and Auth Configuration

For external Kafka, users can configure connection, topic, and auth shape:

```yaml
kafkaClusterConfiguration:
  external:
    existing:
      kafkaConnection: broker1:9092,broker2:9092
      kafkaTopic: logging-traffic-topic
      auth:
        type: none
```

For SCRAM, they can also provide secret and CA references:

```yaml
auth:
  type: scram-sha-512
  secretName: external-kafka-user
  caSecretName: external-kafka-ca
  kafkaUserName: migration-app
```

The workflow resolves those fields into application arguments and environment variables, including broker addresses, topic, listener/auth type, Kubernetes secret names, usernames, and password environment variables.

The workflow-generated Kafka settings passed to the capture proxy are limited to:

- `kafkaConnection`
- `kafkaTopic`
- `kafkaListenerName`
- `kafkaAuthType`
- `kafkaSecretName`
- `kafkaUserName`
- `kafkaPropertyFile`, only when SCRAM auth causes the workflow to mount `/config/kafka-auth/client.properties`
- `CAPTURE_PROXY_KAFKA_PASSWORD`, from the configured Kubernetes secret when SCRAM is used

The workflow-generated Kafka settings passed to the traffic replayer are limited to:

- `kafkaTrafficBrokers`
- `kafkaTrafficTopic`
- `kafkaTrafficGroupId`
- `kafkaTrafficListenerName`
- `kafkaTrafficAuthType`
- `kafkaTrafficSecretName`
- `kafkaTrafficUserName`
- `kafkaTrafficPropertyFile`, either from user `replayerConfig.kafkaTrafficPropertyFile` or from the workflow when SCRAM auth causes the workflow to mount `/config/kafka-auth/client.properties`
- `TRAFFIC_REPLAYER_KAFKA_TRAFFIC_PASSWORD`, from the configured Kubernetes secret when SCRAM is used

### Existing Client Properties Files

The capture proxy and replayer applications both support Java properties files:

- Capture proxy: `--kafkaPropertyFile`
- Replayer: `--kafkaTrafficPropertyFile`

The Kubernetes workflow already creates a `client.properties` ConfigMap for SCRAM auth and mounts it at:

```text
/config/kafka-auth/client.properties
```

Today the complete workflow-generated file content is:

```properties
ssl.truststore.type=PEM
ssl.truststore.location=/config/kafka-ca/ca.crt
```

No broker, topic, producer tuning, or consumer tuning settings are copied into this file.

The replayer user schema exposes `kafkaTrafficPropertyFile`, but the workflow does not mount an arbitrary user-provided file. It only works if the file already exists in the container, for example through a custom image or external pod mutation. The capture proxy application supports a property-file argument, but the user workflow schema does not expose a clean equivalent with a workflow-managed mount.

## Gap

External Kafka users can configure how to connect to Kafka, but they cannot cleanly configure ordinary Kafka producer and consumer tuning through the workflow.

Examples that are not supported cleanly today:

```properties
# capture proxy producer
max.request.size=8388608
compression.type=lz4

# traffic replayer consumer
fetch.max.bytes=8388608
max.partition.fetch.bytes=8388608
```

The existing property-file flags are not enough because Kubernetes workflows must also create and mount the file. Requiring a custom image or an external pod mutation for common external-cluster tuning is too heavy and bypasses the migration workflow's configuration model.

Using Strimzi overrides is also not enough because those apply only when the workflow creates Kafka. They do nothing for bring-your-own Kafka clusters.

Finally, one raw property bag for both applications would be imprecise. The capture proxy is a Kafka producer and the replayer is a Kafka consumer. Their useful client properties differ.

## Proposed User Configuration

Add structured Kafka client property maps to external Kafka configuration, split by application role:

```yaml
kafkaClusterConfiguration:
  external:
    existing:
      kafkaConnection: broker1:9092,broker2:9092
      kafkaTopic: logging-traffic-topic
      auth:
        type: none
      clientProperties:
        producer:
          max.request.size: 8388608
        consumer:
          fetch.max.bytes: 8388608
          max.partition.fetch.bytes: 8388608
```

The proxy receives `producer` properties. The replayer receives `consumer` properties. This avoids applying consumer-only properties to producers or producer-only properties to consumers.

Use a flat scalar map. Do not use `GENERIC_JSON_OBJECT` for client properties. Kafka client properties are flat Java properties, and nested JSON values are ambiguous to serialize into a `.properties` file.

Suggested schema shape:

```ts
const KAFKA_CLIENT_PROPERTY_VALUE = z.union([
  z.string(),
  z.number(),
  z.boolean(),
]);

const KAFKA_CLIENT_PROPERTIES = z.record(
  z.string().min(1),
  KAFKA_CLIENT_PROPERTY_VALUE
).default({}).optional();

const KAFKA_CLIENT_PROPERTIES_CONFIG = z.object({
  producer: KAFKA_CLIENT_PROPERTIES,
  consumer: KAFKA_CLIENT_PROPERTIES,
}).default({}).optional();
```

Add `clientProperties` to external Kafka configuration first:

```ts
export const KAFKA_EXISTING_CLUSTER_CONFIG = z.object({
  // existing fields...
  clientProperties: KAFKA_CLIENT_PROPERTIES_CONFIG,
});
```

Do not add an `allowedProducerDefaults`, `allowedConsumerDefaults`, or equivalent switch. The workflow should always reject protected keys and allow non-protected flat scalar properties. Example allowed keys in this document are examples, not a schema allowlist.

If workflow-managed Kafka later needs explicit expert client properties, add the same field to `KAFKA_CLUSTER_CREATION_CONFIG`, but keep it secondary to derived workflow-owned settings.

## Config Processing Design

External-cluster client properties are fully known before workflow submission. The config processor should resolve, validate, merge, and render the final file body before the Argo workflow is submitted.

Recommended config processor flow:

1. Parse `clientProperties.producer` and `clientProperties.consumer` as flat scalar maps.
2. Resolve the external Kafka config into the existing concrete `kafkaConfig` model.
3. Compute protected producer and consumer keys from the resolved auth and connection model.
4. Reject any user property whose key is protected for that role.
5. Build the generated workflow-owned properties needed for the selected auth mode.
6. Merge generated workflow-owned properties and user properties into one final map per role.
7. Render each final map to Java `.properties` text with each key present exactly once.
8. Pass the rendered static property-file text to the workflow templates.

For capture proxy:

```ts
finalProducerProperties = mergeKafkaClientProperties(
  generatedAuthProperties,
  kafkaConfig.clientProperties.producer,
);
```

For traffic replayer:

```ts
finalConsumerProperties = mergeKafkaClientProperties(
  generatedAuthProperties,
  kafkaConfig.clientProperties.consumer,
);
```

`mergeKafkaClientProperties` should run during config preprocessing. It should reject protected generated keys from the user map and return a map with one value per key.

## Protected Keys and Validation

Validate client-property conflicts during config preprocessing, before the Argo workflow runs. The base Zod schema should validate map shape, but the transformer has the context needed to classify workflow-owned keys.

External Kafka preprocessing already resolves fields such as:

- `kafkaConnection`
- `kafkaTopic`
- `authType`
- `secretName`
- `caSecretName`
- `kafkaUserName`
- `managedByWorkflow`

Use that resolved config to compute protected keys for each application role.

The transformer should report errors with the role and exact key path, for example:

```text
kafkaClusterConfiguration.external.existing.clientProperties.consumer.bootstrap.servers
```

The message should explain that the key is workflow-owned and identify the high-level field that controls it, such as `kafkaConnection` for `bootstrap.servers`.

### Capture Proxy Producer Keys

The capture proxy currently builds Kafka producer properties in this order:

1. App defaults:
   - `key.serializer`
   - `value.serializer`
   - `delivery.timeout.ms`
   - `request.timeout.ms`
   - `max.block.ms`
2. Optional property file.
3. Workflow/app-owned values:
   - `bootstrap.servers`
   - `client.id`
4. Auth values derived from `authType`.

For `clientProperties.producer`, reject these keys:

- `bootstrap.servers`
- `client.id`
- `key.serializer`
- `value.serializer`

Reject these keys whenever auth is represented by workflow config and `auth.type` is not `none`:

- `security.protocol`
- `sasl.mechanism`
- `sasl.jaas.config`

Reject this key for MSK IAM:

- `sasl.client.callback.handler.class`

Reject these keys for SCRAM/TLS when the workflow mounts the Kafka CA:

- `ssl.truststore.type`
- `ssl.truststore.location`

Allow producer tuning keys such as:

- `max.request.size`
- `compression.type`
- `linger.ms`
- `batch.size`
- `buffer.memory`
- `delivery.timeout.ms`
- `request.timeout.ms`
- `max.block.ms`

These allowed keys are examples, not a whitelist. The first implementation should use a protected-key denylist rather than maintain a full Kafka-client allowlist.

### Traffic Replayer Consumer Keys

The replayer currently builds Kafka consumer properties in this order:

1. App defaults:
   - `key.deserializer`
   - `value.deserializer`
   - `enable.auto.commit`
   - `auto.offset.reset`
2. Optional property file.
3. Auth values derived from `authType`.
4. Workflow/app-owned values:
   - `bootstrap.servers`
   - `group.id`
5. Rebalance strategy default, only if absent:
   - `partition.assignment.strategy`

For `clientProperties.consumer`, reject these keys:

- `bootstrap.servers`
- `group.id`
- `key.deserializer`
- `value.deserializer`
- `enable.auto.commit`
- `partition.assignment.strategy`

Reject these keys whenever auth is represented by workflow config and `auth.type` is not `none`:

- `security.protocol`
- `sasl.mechanism`
- `sasl.jaas.config`

Reject this key for MSK IAM:

- `sasl.client.callback.handler.class`

Reject these keys for SCRAM/TLS when the workflow mounts the Kafka CA:

- `ssl.truststore.type`
- `ssl.truststore.location`

`auto.offset.reset` is currently an app default that a property file can override. It may remain allowed if the product wants an expert offset behavior knob. If allowed, the user value should replace the app default in the final rendered map rather than appearing as a duplicate line. If not, add it to the protected set.

Allow consumer tuning keys such as:

- `fetch.max.bytes`
- `max.partition.fetch.bytes`
- `max.poll.records`
- `session.timeout.ms`
- `heartbeat.interval.ms`

Be cautious with consumer group and rebalance settings. The replayer has offset and partition-revocation behavior coupled to the consumer group model, so the first implementation should reject `partition.assignment.strategy`.

### Suggested Validation Helpers

```ts
function validateKafkaClientProperties(kafkaConfig, clientProperties) {
  validateProtectedKeys(
    "producer",
    clientProperties.producer,
    protectedProducerKeys(kafkaConfig),
  );
  validateProtectedKeys(
    "consumer",
    clientProperties.consumer,
    protectedConsumerKeys(kafkaConfig),
  );
}

function protectedProducerKeys(kafkaConfig) {
  const keys = new Set([
    "bootstrap.servers",
    "client.id",
    "key.serializer",
    "value.serializer",
  ]);
  addAuthProtectedKeys(keys, kafkaConfig);
  return keys;
}

function protectedConsumerKeys(kafkaConfig) {
  const keys = new Set([
    "bootstrap.servers",
    "group.id",
    "key.deserializer",
    "value.deserializer",
    "enable.auto.commit",
    "partition.assignment.strategy",
  ]);
  addAuthProtectedKeys(keys, kafkaConfig);
  return keys;
}
```

## Property Precedence

Do not rely on duplicate Java property keys in the rendered file to express precedence.

`java.util.Properties.load(...)` processes a file sequentially and a later duplicate key overwrites an earlier value. For example, a file containing `foo=1` followed later by `foo=2` results in `foo=2`; it does not reject the duplicate.

That behavior is useful to understand, but it should not be the workflow contract. It makes precedence depend on text order and makes generated ConfigMaps harder to audit.

The workflow should resolve precedence at the map level before rendering:

1. Generated workflow-owned properties are controlled by the workflow and cannot be replaced through `clientProperties`.
2. User properties may set any non-protected Kafka client key.
3. App defaults that are already overrideable through the existing Java property-file load order remain effectively tunable when they are not in the protected set.
4. The rendered ConfigMap contains each final key exactly once.

This means user overrides are allowed for keys that are deliberately not protected, but the override happens before rendering. The ConfigMap should not contain duplicate entries for the same key.

Workflow-owned and app-contract keys should not be overrideable through `clientProperties`:

- `bootstrap.servers`
- `client.id`
- `group.id`
- serializers and deserializers
- auth and TLS settings generated from the workflow auth model
- replayer consumer group behavior that the application depends on

User-provided properties should tune Kafka client behavior, not silently disconnect the app from the workflow-selected Kafka cluster or change application-level invariants.

If a future use case requires fully custom auth properties, that should be a separate expert mode because it changes the connection contract.

## Rendering Java Properties Safely

The workflow should render Kafka client properties through a dedicated Java-properties escaping function, not by interpolating raw `key=value` strings.

Use the Java `.properties` file format rules expected by `java.util.Properties.load(...)`:

- Escape backslash as `\\`.
- Escape newline as `\n`, carriage return as `\r`, and tab as `\t`.
- Escape form feed as `\f` if it appears.
- Escape leading spaces in keys and values as `\ ` so they are preserved.
- Escape key separators in keys: `=`, `:`, space, tab, carriage return, newline, and form feed.
- Escape comment introducers `#` and `!` when they are the first non-escaped character in a key.
- Escape non-ASCII characters with `\uXXXX` if the renderer writes ISO-8859-1-compatible `.properties` output. If the runtime can guarantee Java 9+ UTF-8 behavior for `Properties.load(InputStream)`, this can be relaxed, but ASCII escaping is the safest cross-runtime contract.

For values, `=` and `:` do not need to be escaped after the separator, but escaping them is valid. The renderer may choose a conservative implementation that escapes `=`, `:`, `#`, and `!` in all keys and values if round-trip tests verify `Properties.load(...)` returns the original strings.

Recommended rendering flow:

1. Validate the property map shape: flat string keys and scalar string/number/boolean values.
2. Convert number and boolean values to strings using stable JSON-like forms, for example `true`, `false`, and base-10 numbers.
3. Reject empty keys and protected keys before rendering.
4. Sort keys for deterministic output.
5. Render each entry as `escapePropertyKey(key) + "=" + escapePropertyValue(value)`.
6. Join rendered entries with `\n` and include a final trailing newline.
7. Pass the complete rendered file body to the workflow as static ConfigMap data.

Example:

```ts
function escapeJavaPropertiesPart(input: string, isKey: boolean): string {
  let out = "";
  for (let i = 0; i < input.length; i++) {
    const ch = input[i];
    const code = ch.charCodeAt(0);
    const isLeadingSpace = i === 0 && ch === " ";
    if (ch === "\\") out += "\\\\";
    else if (ch === "\n") out += "\\n";
    else if (ch === "\r") out += "\\r";
    else if (ch === "\t") out += "\\t";
    else if (ch === "\f") out += "\\f";
    else if (isLeadingSpace) out += "\\ ";
    else if (isKey && (ch === "=" || ch === ":" || ch === " ")) out += "\\" + ch;
    else if ((i === 0 || isKey) && (ch === "#" || ch === "!")) out += "\\" + ch;
    else if (code < 0x20 || code > 0x7e) out += "\\u" + code.toString(16).padStart(4, "0");
    else out += ch;
  }
  return out;
}
```

The exact implementation can be shared by proxy and replayer workflow rendering. It should have unit tests that render a static ConfigMap, parse the YAML, load the resulting file with `java.util.Properties`, and assert the original key/value pairs are recovered.

## Workflow Changes

Extend the existing `createKafkaClientPropertiesConfigMap` templates in:

- `orchestrationSpecs/packages/migration-workflow-templates/src/workflowTemplates/setupCapture.ts`
- `orchestrationSpecs/packages/migration-workflow-templates/src/workflowTemplates/replayer.ts`

Those templates already create a ConfigMap mounted at `/config/kafka-auth/client.properties`. Instead of hardcoding only TLS truststore values, render the static file content supplied by the config processor.

Set the application property-file argument when either auth requires generated properties or user properties are present:

- Proxy: `kafkaPropertyFile: /config/kafka-auth/client.properties`
- Replayer: `kafkaTrafficPropertyFile: /config/kafka-auth/client.properties`

For this initial implementation, the workflow should not perform `sprig.merge` or build properties-file text dynamically. The final property-file content is predetermined during config processing.

If a future workflow needs dynamic properties-file content, use a single dynamic YAML string scalar for the complete file body. Recent workflow-builder YAML hardening wraps dynamic string scalars in resource manifests with `toJSON(...)`, which keeps newline-containing and YAML-sensitive strings valid after Argo substitution. Avoid rendering the properties file through `makeDirectTypeProxy` or through unescaped string fragments.

Simple Argo string concatenation can produce valid file contents when it is rendered as a YAML-safe string scalar, but it should be reserved for future dynamic cases. It should concatenate already-resolved, conflict-checked final lines rather than relying on duplicate keys for override semantics. The line renderer must still escape Java `.properties` keys and values correctly; YAML-safe rendering only protects the Kubernetes manifest, not the file format consumed by `Properties.load(...)`.

## Managed Strimzi Guidance

For auto-created Strimzi clusters, avoid asking users to coordinate raw broker, topic, producer, and consumer size values manually. The workflow owns the full stack and should keep related limits consistent.

A future high-level setting could look like:

```yaml
kafkaClusterConfiguration:
  managed:
    autoCreate:
      trafficRecordMaxBytes: 8388608
      topicSpecOverrides:
        partitions: 24
```

The workflow would derive at least these values:

- Broker/topic limits that can accept the largest record.
- Producer `max.request.size`.
- Consumer `fetch.max.bytes` and `max.partition.fetch.bytes`.
- Optional capture proxy `maxTrafficBufferSize`, if the intent is larger Kafka records rather than normal 1 MiB fragmentation.

Use `>=` as the validation relationship for these settings. Kafka properties do not all measure the same payload unit: producer requests, broker batches, consumer fetch responses, and captured protobuf records include different overheads.

Do not copy `message.max.bytes` from the auto-created Strimzi cluster configuration into the client properties file. That field configures the broker. If the workflow wants to size related client properties, it should do that from an explicit workflow-owned setting such as `trafficRecordMaxBytes`.

## External Cluster Example

```yaml
kafkaClusterConfiguration:
  external:
    existing:
      kafkaConnection: broker1.example.com:9092,broker2.example.com:9092
      kafkaTopic: logging-traffic-topic
      auth:
        type: scram-sha-512
        secretName: external-kafka-user
        caSecretName: external-kafka-ca
        kafkaUserName: migration-app
      clientProperties:
        producer:
          max.request.size: 8388608
          delivery.timeout.ms: 30000
        consumer:
          fetch.max.bytes: 8388608
          max.partition.fetch.bytes: 8388608
```

This keeps the normal workflow auth model while allowing customer-specific client tuning required by the external broker.

## Testing Strategy

Add coverage at three levels.

### Config Processor Unit Tests

Add schema and transformer tests for the new `clientProperties` fields:

- Accept flat string/number/boolean property values.
- Reject nested objects, arrays, and empty keys.
- Reject protected producer keys such as `bootstrap.servers`, `client.id`, `key.serializer`, and auth/TLS keys when auth is workflow-managed.
- Reject protected consumer keys such as `bootstrap.servers`, `group.id`, `key.deserializer`, `enable.auto.commit`, `partition.assignment.strategy`, and auth/TLS keys when auth is workflow-managed.
- Allow ordinary producer tuning keys such as `max.request.size`.
- Allow ordinary consumer tuning keys such as `fetch.max.bytes` and `max.partition.fetch.bytes`.
- Verify the rendered Java-properties file has one line per key and round-trips through `java.util.Properties.load(...)`.

These tests should live with the existing config-processor and schema tests:

- `orchestrationSpecs/packages/schemas/tests`
- `orchestrationSpecs/packages/config-processor/tests`

### Workflow Template Snapshot Tests

Update workflow-template snapshot tests to prove the generated proxy and replayer deployments mount the generated Kafka client ConfigMaps and pass the correct property-file arguments when user client properties are present.

The snapshots should verify:

- Capture proxy receives `kafkaPropertyFile: /config/kafka-auth/client.properties` when producer properties are configured.
- Replayer receives `kafkaTrafficPropertyFile: /config/kafka-auth/client.properties` when consumer properties are configured.
- Generated ConfigMap data includes the expected static Java-properties content.
- No duplicate property keys are rendered.

These should land in the existing workflow-template test package:

- `orchestrationSpecs/packages/migration-workflow-templates/tests`

### Kubernetes Integration Test

Add a new explicit CDC integration test that uses a Strimzi Kafka cluster created outside the migration workflow. This is the right end-to-end shape because it gives the test a real Kafka broker, user, CA, and topic while making the migration workflow consume it as an external cluster.

Recommended flow:

1. In the test setup, create a Strimzi `Kafka`, `KafkaNodePool`, `KafkaTopic`, and optionally `KafkaUser` directly in the test namespace.
2. Wait for Strimzi to report the Kafka cluster ready.
3. Build a migration config that uses:

   ```yaml
   kafkaClusterConfiguration:
     external:
       existing:
         kafkaConnection: external-kafka-kafka-bootstrap:9093
         kafkaTopic: logging-traffic-topic
         auth:
           type: scram-sha-512
           secretName: external-kafka-migration-app
           caSecretName: external-kafka-cluster-ca-cert
           kafkaUserName: external-kafka-migration-app
         clientProperties:
           producer:
             max.request.size: 8388608
           consumer:
             fetch.max.bytes: 8388608
             max.partition.fetch.bytes: 8388608
   ```

4. Run the existing CDC-only live traffic flow through the capture proxy.
5. Verify documents replay to the target.
6. Verify the generated proxy and replayer Kafka client ConfigMaps contain the configured properties.
7. Verify the migration workflow did not create its own managed Kafka cluster for this traffic path.

This should be a new explicit test case rather than part of the default CDC set. A good landing spot is a new CDC test class under:

- `migrationConsole/lib/integ_test/integ_test/test_cases/cdc_external_kafka_tests.py`

Use the next open CDC test id in the `0031-0039` range, for example `Test0036CdcExternalKafkaClientProperties`. That range is already reserved for CDC variants.

For Jenkins, add a new k8s-local job wrapper rather than adding this to the broad version matrix. The test provisions another Kafka cluster and will be slower than a normal CDC smoke test. A focused job keeps failures easier to triage and avoids inflating every `elasticsearch8xK8sLocalTest` run.

Suggested Jenkins placement:

- `vars/externalKafkaK8sLocalTest.groovy`
- `jenkins/migrationIntegPipelines/externalKafkaK8sLocalTestCover.groovy`

The wrapper can call `k8sLocalDeployment` with a single modern version pair and only the new test id:

```groovy
def call(Map config = [:]) {
    k8sLocalDeployment(
        jobName: config.jobName ?: 'external-kafka-k8s-local-test',
        sourceVersion: 'ES_8.19',
        targetVersion: 'OS_3.1',
        testIds: '0036'
    )
}
```

If Jenkins capacity is a concern, this can initially be a periodic or manually triggered job. Once stable, it can be added to PR checks only for changes under `orchestrationSpecs`, `deployment/k8s`, `migrationConsole/lib/integ_test`, or traffic capture/replay Kafka code.
