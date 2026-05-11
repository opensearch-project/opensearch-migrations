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

For self-managed OpenSearch targets, RFS is delivered via the
Migration Assistant helm chart, not as a standalone `docker run`.
The chart deploys the migration-console pod plus Argo workflows that
spin up RFS workers as needed. The published image (used by the
chart) is
`public.ecr.aws/opensearchproject/opensearch-migrations-reindex-from-snapshot`.

For a kind / minikube / on-prem k8s install, see
`steering/04-execute.md` "Local-build vs released-image testing"
for the full helm-install command and image overlay. Once installed,
drive the migration with:

```
kubectl -n ma exec -it migration-console-0 -- /bin/bash
console snapshot create
console metadata migrate
console backfill start
console backfill scale 1     # then bump as workers prove out
```

Self-managed-to-self-managed sources where you don't already run
k8s and the dataset is modest are usually better served by
`_reindex` from remote (no MA install needed).

## Pitfalls

- Default heap on the OS / ES container is 1g. For anything beyond
  a POC, set `-e OPENSEARCH_JAVA_OPTS="-Xms4g -Xmx4g"` and ensure
  `vm.max_map_count >= 262144` on the host (P1, P10).
- Demo certs: `OPENSEARCH_INITIAL_ADMIN_PASSWORD` is required since
  2.12. POC compose files in this repo set it (P9).
