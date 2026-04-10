# Kafka Auth Type Wiring Plan

## Current State

New CLI flags have been added to both `TrafficReplayer.Parameters` and `KafkaConfig.KafkaParameters`:
- `kafkaAuthType` / `kafkaTrafficAuthType` — values: `none`, `msk-iam`, `scram-sha-512`
- `kafkaListenerName` / `kafkaTrafficListenerName` — orchestration metadata
- `kafkaSecretName` / `kafkaTrafficSecretName` — orchestration metadata
- `kafkaUserName` / `kafkaTrafficUserName` — SCRAM username

**Problem:** Everything downstream still passes a `boolean enableMSKAuth` — the `scram-sha-512` auth type is accepted at the CLI but silently ignored when building Kafka client properties.

## Goal

Wire the auth type string through the entire call chain so that `scram-sha-512` (and any future types) actually configure the Kafka client. Internal signatures change freely; the CLI interface is already stable.

---

## Changes

### 1. `ArgNameConstants` — add kafka password to censored/credential patterns

Add kafka password arg names and update `POSSIBLE_CREDENTIALS_ARG_FLAG_NAMES` and `CENSORED_ARGS` so the password is redacted in logs and can be picked up from env vars without prefix.

**File:** `coreUtilities/src/main/java/org/opensearch/migrations/arguments/ArgNameConstants.java`

---

### 2. `KafkaConfig.KafkaParameters` — add password field

Add `kafkaPassword` `@Parameter` field. `EnvVarParameterPuller` will automatically map `CAPTURE_PROXY_KAFKA_PASSWORD` from the environment (K8s secret mounted as env var).

**File:** `TrafficCapture/captureKafkaOffloader/src/main/java/org/opensearch/migrations/trafficcapture/kafkaoffloader/KafkaConfig.java`

---

### 3. `KafkaConfig.buildKafkaProperties` — accept auth type string, build SCRAM JAAS

Change the 4-arg overload from `boolean mskAuthEnabled` → `String authType, String kafkaUserName, String kafkaPassword`. Add SCRAM-SHA-512 handling that constructs the JAAS config from username + password.

Update the convenience `buildKafkaProperties(KafkaParameters)` overload to pass `getEffectiveKafkaAuthType()`, `kafkaUserName`, `kafkaPassword`.

Merge order:
1. Hardcoded defaults (serializers, timeouts)
2. Property file (if provided) — overrides defaults
3. CLI/env-derived properties (brokers, client ID, auth config) — wins over property file

**File:** `TrafficCapture/captureKafkaOffloader/src/main/java/org/opensearch/migrations/trafficcapture/kafkaoffloader/KafkaConfig.java`

---

### 4. `TrafficReplayer.Parameters` — add password field and `getEffectiveKafkaAuthType()`

Add `kafkaTrafficPassword` `@Parameter` field. `EnvVarParameterPuller` maps `TRAFFIC_REPLAYER_KAFKA_TRAFFIC_PASSWORD`.

Add `getEffectiveKafkaAuthType()`:
```java
String getEffectiveKafkaAuthType() {
    validateKafkaAuthFlags();
    if (kafkaTrafficAuthType != null && !kafkaTrafficAuthType.isBlank()) {
        return kafkaTrafficAuthType;
    }
    return Boolean.TRUE.equals(kafkaTrafficEnableMSKAuth) ? KAFKA_AUTH_TYPE_MSK_IAM : KAFKA_AUTH_TYPE_NONE;
}
```

Simplify `isKafkaTrafficEnableMSKAuth()` to delegate to it.

**File:** `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/TrafficReplayer.java`

---

### 5. `KafkaTrafficCaptureSource.buildKafkaProperties` — accept auth type string, build SCRAM JAAS

Change signature from `boolean enableMSKAuth` → `String authType, String kafkaUserName, String kafkaPassword`. Same SCRAM-SHA-512 JAAS construction. Same merge order (property file as defaults, CLI wins).

**File:** `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/kafka/KafkaTrafficCaptureSource.java`

---

### 6. `KafkaTrafficCaptureSource.buildKafkaSource` — accept auth type string + credentials

Change `boolean enableMSKAuth` → `String authType, String kafkaUserName, String kafkaPassword` and pass through.

**File:** `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/kafka/KafkaTrafficCaptureSource.java`

---

### 7. `TrafficCaptureSourceFactory` — pass auth type + credentials

```java
return KafkaTrafficCaptureSource.buildKafkaSource(
    ctx, appParams.kafkaTrafficBrokers, appParams.kafkaTrafficTopic,
    appParams.kafkaTrafficGroupId,
    appParams.getEffectiveKafkaAuthType(),
    appParams.kafkaTrafficUserName,
    appParams.kafkaTrafficPassword,
    appParams.kafkaTrafficPropertyFile,
    Clock.systemUTC(), new KafkaBehavioralPolicy()
);
```

**File:** `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/TrafficCaptureSourceFactory.java`

---

### 8. `KafkaTopicDumper.runDumpFromKafka` — accept auth type + credentials

Change `boolean mskAuth` → `String authType, String kafkaUserName, String kafkaPassword` and pass through to `buildKafkaProperties`.

**File:** `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/kafka/KafkaTopicDumper.java`

---

### 9. `TrafficReplayer.runDumpMode` — pass auth type + credentials

```java
runner.runDumpFromKafka(params.mode, params.kafkaTrafficBrokers, params.kafkaTrafficTopic,
    params.getEffectiveKafkaAuthType(), params.kafkaTrafficUserName, params.kafkaTrafficPassword,
    params.kafkaTrafficPropertyFile, ...);
```

**File:** `TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/TrafficReplayer.java`

---

### 10. Test updates

Update all test call sites that use the old `boolean` signatures:

| Test File | Change |
|-----------|--------|
| `CaptureProxySetupTest.java` | No change — calls `buildKafkaProperties(KafkaParameters)` |
| `KafkaTrafficCaptureSourceTest.java` | `false` → `"none", null, null`; `true` → `"msk-iam", null, null` |
| `KafkaCommitsWorkBetweenLongPollsTest.java` | `false` → `"none", null, null` |
| `KafkaKeepAliveTests.java` | `false` → `"none", null, null` |
| `KafkaTrafficCaptureSourceLongTermTest.java` | `false` → `"none", null, null` |
| `KafkaRestartingTrafficReplayerTest.java` | `false` → `"none", null, null` |

---

## Auth Type Behavior Summary

| `authType` | `security.protocol` | `sasl.mechanism` | JAAS config source |
|------------|--------------------|-----------------|--------------------|
| `none` | *(not set)* | *(not set)* | N/A |
| `msk-iam` | `SASL_SSL` | `AWS_MSK_IAM` | Hardcoded IAM module |
| `scram-sha-512` | `SASL_SSL` | `SCRAM-SHA-512` | Built from `kafkaUserName` + `kafkaPassword` params/env |

## Credential Flow

The SCRAM password is injected as an environment variable by K8s (from the secret named by `--kafkaSecretName`). `EnvVarParameterPuller` maps it into the `@Parameter` field automatically:
- Replayer: `TRAFFIC_REPLAYER_KAFKA_TRAFFIC_PASSWORD` → `kafkaTrafficPassword`
- Proxy: `CAPTURE_PROXY_KAFKA_PASSWORD` → `kafkaPassword`

The JAAS config is constructed programmatically:
```
org.apache.kafka.common.security.scram.ScramLoginModule required username="<user>" password="<pass>";
```

No `--kafkaPropertyFile` required. If one is provided, it supplies defaults that CLI/env params override.
