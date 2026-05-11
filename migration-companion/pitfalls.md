# Pitfalls

Non-discoverable facts. The kind of thing that wastes 30 minutes
before you realize what's happening. Skim once before Phase 4 on
MIGRATE / POC. Don't memorize.

## P1. `vm.max_map_count` on the host

Both Elasticsearch and OpenSearch refuse to start if
`/proc/sys/vm/max_map_count` is too low. Symptom: container exits
during bootstrap with "max virtual memory areas vm.max_map_count
[65530] is too low".

Fix on Linux host: `sudo sysctl -w vm.max_map_count=262144`.

The POC compose files in this repo set the in-container ulimits, but
the host setting is not something docker can override — that's on
the user.

## P2. `hits.total.value` capping (both ES 7+ and AOSS)

`hits.total.value` caps at 10000 unless you pass `track_total_hits: true`
in the request body. This is true on:

- Elasticsearch 7.x and 8.x sources (default behavior, not a bug).
- OpenSearch when configured similarly.
- AOSS targets (always — no opt-out for "real" precision until you
  pass the flag).

Always pass `track_total_hits: true` in Phase 5 parity queries. Phase 5
doc count parity uses `_count` (not affected) but query-level parity
checks must include this flag or top-N matches but `total` looks broken.

## P3. AWS snapshot repo registration needs SigV4

`PUT /_snapshot/<repo>` against an AOS domain MUST be a SigV4-signed
request. Plain `curl -u user:pass` returns a confusing AccessDenied
even with correct IAM. Use `awscurl`, `requests-aws4auth`, or the MA
orchestrator.

## P4. ES 8 → OpenSearch is RFS-only

There is no "OS compatibility layer" for ES 8. Any answer that
involves "register the snapshot repo and restore" for an ES 8 source
is wrong. Plan for RFS from the start.

## P5. `include_global_state` defaults differ in restore

`POST /_snapshot/<repo>/<snap>/_restore` with no body restores
`include_global_state: true` by default — this overwrites the
target's templates and cluster settings. Always set
`include_global_state: false` unless the user explicitly wants
state migrated.

## P6. RFS image needs network paths to BOTH clusters

When source is on-prem and target is AOS-in-VPC (or AOSS), the RFS
container needs a route to both. Common gotcha: running RFS on the
user's laptop with VPN to source but no path to AOS endpoint.

Fix: run RFS in the same VPC as the target (Fargate task, EC2
instance), or peer the networks.

## P7. Self-signed TLS

Elasticsearch's default docker stack ships with self-signed certs
since 8.x. The OpenSearch security plugin also uses demo certs by
default. RFS will refuse to connect unless you pass
`SOURCE_INSECURE=true` / `TARGET_INSECURE=true` (or supply CA bundles).

This is fine for POC. Not fine for real migrations — pin a CA bundle.

## P8. AOSS rejects `_all` mapping field

If migrating from ES 5.x or pre-7.x indices that still carry an
`_all` field declaration in their mapping (even if disabled), AOSS
will reject the index create. Strip `_all` from the mapping before
RFS runs the create.

## P9. Demo passwords on OpenSearch 2.12+

Since 2.12, OpenSearch requires `OPENSEARCH_INITIAL_ADMIN_PASSWORD`
to be set on first start. POC compose files in this repo set it.
Real installs need to pick something better.

## P10. Default heap is 1g

The OS / ES docker images default to 1g heap. Anything past a 5k-doc
POC needs more. Add `-e OPENSEARCH_JAVA_OPTS="-Xms4g -Xmx4g"` (or ES
equivalent) and bump the container memory ceiling to match.

## P11. Snapshot status polling cadence

`GET /_snapshot/<repo>/<snap>` is fine for "in progress / done".
For per-shard progress on a large snapshot, use
`GET /_snapshot/<repo>/<snap>/_status`. Don't poll either more often
than every 5-10s — it's not free.

## P12. `_index_template` vs `_template`

Composable index templates (`_index_template`) and legacy templates
(`_template`) both exist. ES 7.8+ and OS 1+ have both. Restoring
templates from a snapshot only restores the kind that's stored.
Migrate both explicitly if the source uses both.

## P13. AOS major-version-skip

AOS refuses to restore a snapshot from a cluster more than 1 major
version older than the domain. ES 6 → AOS OS 2 is rejected. Bridge
via OS 1, or use RFS.

## P14. The "this can't be right" doc count drift

If counts differ by exactly 0 or by exactly N for some round N, it's
a scope bug (allowlist excluded something). If they differ by a small
fraction, it's almost always P2 (AOSS) or `track_total_hits`. If they
differ by a large unpredictable amount, you have a real problem —
investigate before reporting "migrated cleanly".

## P15. Reindex setting renamed: whitelist → allowlist

OpenSearch (1.x onward) renamed `reindex.remote.whitelist` (the ES
key) to `reindex.remote.allowlist`. Setting the old name on an OS
target is not a deprecation — it is a hard startup failure with
"unknown setting [reindex.remote.whitelist]". This bites anyone
copying a docker-compose, helm chart, or terraform module from an ES
era. Fix: use `reindex.remote.allowlist`.

## P16. The MA release `.tgz` IS the aggregate, not a leaf chart

The artifact at `migration-assistant-<version>.tgz` from the MA
releases page is the `migrationAssistantWithArgo` aggregate chart
renamed to `migration-assistant`. It pulls in cert-manager, Argo
Workflows, Strimzi/Kafka, and so on through an in-cluster
`helm-installer` Job. There is no separately published "leaf MA
chart". Every piece of the stack comes from this one tarball.

Implication: a 5-pod MA install isn't a thing. Expect ~30 pods
across ~5 namespaces after `helm install` settles.

## P17. `valuesForLocalK8s.yaml` assumes a localhost:5000 build

The repo's `valuesForLocalK8s.yaml` (under
`deployment/k8s/charts/aggregates/migrationAssistantWithArgo/`)
overrides image refs to `localhost:5000/migrations/<name>` —
because the MA dev workflow Gradle-builds and pushes to a local
registry. If you `helm install` with only this values file you'll
get `ImagePullBackOff` on every MA pod.

When pointing a user at the helm chart for an install that doesn't
involve a local Gradle build (i.e. most users), they need to either:

  - Use the chart's default `values.yaml` (which references whatever
    registry the release line publishes to), or
  - Layer their own image overlay on top of `valuesForLocalK8s.yaml`
    pointing at a registry they can pull from.

Confirm the image-publication policy of the current release by
checking the chart's `README.md` and `Chart.yaml` before assuming
`public.ecr.aws/opensearchproject/...` resolves.

## P18. Only 3 of 5 logical MA roles have published public ECR images

The MA chart references 5 image roles: `migrationConsole`,
`installer`, `reindexFromSnapshot`, `trafficReplayer`, `captureProxy`.
Three are published to `public.ecr.aws/opensearchproject/`:

  - `opensearch-migrations-console`
  - `opensearch-migrations-reindex-from-snapshot`
  - `opensearch-migrations-traffic-replayer`

The other two:

  - **installer**: the chart's default already maps `installer` to
    the `migration_console` image (the installer Job is just the
    console image running a helm-install script). No separate image
    is published; rebinding the console image is enough.
  - **capture-proxy**: not published anywhere public. There's no
    workaround. capture-and-replay is unavailable on a public-ECR-only
    install. RFS-only POCs are fine; disable the capture-proxy and
    traffic-replayer Argo-workflow steps in values.

## P19. MA snapshot path needs cross-pod shared storage

RFS reads documents from a snapshot repo path that BOTH source ES
and the migration-console pod can see. With S3 this is trivial.
With a filesystem repo on kind, you need a shared PVC mounted at
the same path in both namespaces — and that's not what the
default chart values set up. For a kind POC, prefer running source
ES in the same namespace as MA, or use S3 via localstack.

When walking the user through a kind-based POC, prefer running source
ES in the same namespace as MA (so they share a PVC trivially), or
use S3 via localstack. Cross-namespace shared filesystem repos are
the bit that's environment-specific.
