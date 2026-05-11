# Target pack: self-managed OpenSearch (3.x)

The user runs the cluster. Docker, Kubernetes, EC2, bare metal — all
the same from the migration's perspective.

## Capability profile

| Capability         | Supported | Notes                                      |
| ------------------ | --------- | ------------------------------------------ |
| snapshot_restore   | yes       | All snapshot repo types.                   |
| snapshot_repo_s3   | yes       | `repository-s3` plugin (bundled).          |
| snapshot_repo_fs   | yes       | Shared filesystem.                         |
| reindex_from_remote| yes       | RFS works.                                 |
| painless_scripts   | yes       | Bundled.                                   |
| custom_analyzers   | yes       | Bundled + plugin-installable (icu, kuromoji, smartcn, phonetic). |
| security_plugin    | yes       | Bundled. Demo certs by default — replace before prod. |
| ism                | yes       | Bundled.                                   |
| alerting           | yes       | Bundled.                                   |
| knn                | yes       | Bundled `opensearch-knn`.                  |
| ml_commons         | yes       | Bundled.                                   |

## What you can do

Anything the source could do, basically. This is the most permissive
target. Snapshot/restore is the default execution strategy unless
the source is ES 8 (RFS required regardless of target).

## Snapshot repo registration (S3)

```
PUT /_snapshot/<repo-name>
{
  "type": "s3",
  "settings": {
    "bucket": "<bucket>",
    "region": "<region>",
    "base_path": "<prefix>"
  }
}
```

Credentials: keystore-based. On the OS 3.x docker image, run inside
the container:

```
opensearch-keystore add s3.client.default.access_key
opensearch-keystore add s3.client.default.secret_key
```

Then `POST /_nodes/reload_secure_settings`.

## Restore

```
POST /_snapshot/<repo>/<snap>/_restore
{
  "indices": "logs-*,products,users",
  "ignore_unavailable": true,
  "include_global_state": false,
  "rename_pattern": "(.+)",
  "rename_replacement": "$1"
}
```

`include_global_state: false` is the safe default. Setting it true
overwrites your target's templates and cluster settings.

## RFS (when snapshot/restore won't work)

The `opensearch-migrations-rfs` image reads from a source over HTTP
and writes docs to the target. Minimal invocation:

```
docker run --rm \
  -e SOURCE_HOST=https://source:9200 \
  -e SOURCE_USER=admin -e SOURCE_PASSWORD=admin \
  -e TARGET_HOST=https://target:9200 \
  -e TARGET_USER=admin -e TARGET_PASSWORD=admin \
  -e SOURCE_INSECURE=true -e TARGET_INSECURE=true \
  opensearchproject/opensearch-migrations-rfs:latest \
  --index-allowlist 'logs-*,products'
```

## Pitfalls

- Default heap on the docker image is 1g. For anything beyond a POC,
  set `-e OPENSEARCH_JAVA_OPTS="-Xms4g -Xmx4g"` and ensure
  `vm.max_map_count >= 262144` on the host.
- Demo certs: `OPENSEARCH_INITIAL_ADMIN_PASSWORD` is required since
  2.12. POC compose files in this repo set it.
