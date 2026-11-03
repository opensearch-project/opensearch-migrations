# Turning up logging

The Java components (RFS document backfill, metadata migration, capture proxy, and traffic replayer) read their
log levels from environment variables. There are three ways to change them, from most to least preferred.

## 1. The workflow setting

Set `logLevels` on the component's options and resubmit the workflow. The change is safe: it applies to a
running migration without approval. It rolls the component's pods, because the environment of the Deployment (or
the metadata migration pod) changes.

```yaml
documentBackfillConfig:
  logLevels:
    root: info               # MIGRATIONS_ROOT_LOG_LEVEL, default info
    bulkRequestTrace: trace  # BULK_REQUEST_TRACE_LOG_LEVEL, default off
    failedRequests: warn     # FAILED_REQUESTS_LOGGER_LEVEL, default warn
```

`metadataMigrationConfig`, the proxy options, and the replayer options accept `logLevels.root`. Levels are `off`,
`fatal`, `error`, `warn`, `info`, `debug`, `trace`, and `all`.

| Setting                         | Variable                       | What it covers                                                         |
|---------------------------------|--------------------------------|------------------------------------------------------------------------|
| `root`                          | `MIGRATIONS_ROOT_LOG_LEVEL`    | Every logger without its own setting, including Netty and the AWS SDK. |
| `bulkRequestTrace` (RFS)        | `BULK_REQUEST_TRACE_LOG_LEVEL` | Every bulk request and response, at `trace`.                           |
| `failedRequests` (RFS)          | `FAILED_REQUESTS_LOGGER_LEVEL` | The pod-local file of bulk requests that ran out of retries.           |

Prefer `bulkRequestTrace: trace` to `root: trace` when debugging bulk writes. The root logger at `trace` also
traces Netty and the AWS SDK, which buries the bulk traffic. **The bulk trace contains document contents**, so
turn it on only where the logs may hold the migrated data, and turn it off again afterward.

## 2. `kubectl set env` (temporary)

For a quick look without resubmitting, set the variable on the running Deployment:

```
kubectl -n ma set env deployment/<session>-rfs BULK_REQUEST_TRACE_LOG_LEVEL=trace
```

This rolls the pods too. The workflow owns the Deployment, so its next apply puts the configured levels back.

## 3. A full log4j configuration

For control over individual loggers, appenders, or layouts, put a complete `log4j2.properties` in a ConfigMap
and name it in the component's `loggingConfigurationOverrideConfigMap`. It replaces the image's built-in
configuration entirely, so start from the component's own `log4j2.properties` (for RFS,
`RFS/src/main/resources/log4j2.properties`). `jvmArgs` can't change levels: the built-in configurations read
environment variables, not system properties.
