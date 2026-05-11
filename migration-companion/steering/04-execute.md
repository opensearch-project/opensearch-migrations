# Phase 4 — Execute

**Goal:** run the plan from Phase 3.

Run on paths: POC, MIGRATE. (ANALYZE skips this.)

This skill does not ship its own POC stack. Pick the existing
opensearch-migrations artifact that matches the chosen approach and
drive it from there.

## Pick the right backend

| Approach (from Phase 3)         | Use this from the repo                                    |
| ------------------------------- | --------------------------------------------------------- |
| MA RFS / metadata / snapshot    | `deployment/k8s/charts/aggregates/migrationAssistantWithArgo/` |
| MA capture-and-replay           | `deployment/k8s/...` + `TrafficCapture/`                  |
| Local sandbox (RFS dev loop)    | `TrafficCapture/dockerSolution/` — see its README         |
| Solr → OS specifically          | `solrMigrationDevSandbox/` (and the AIAdvisor solr skill) |
| `_reindex` from remote          | Hand-rolled — no infra needed; see below                  |
| Snapshot/restore (no MA needed) | `awscurl` / `aws cli`, source/target tools directly       |

## POC path — Migration Assistant helm chart on a local k8s

This is the "real" Migration Assistant on a kind/minikube/k3d cluster.

  1. Create a local k8s cluster (kind is fine for ~16 GB hosts).
  2. Install the chart from
     `deployment/k8s/charts/aggregates/migrationAssistantWithArgo/`.
     **Always pass an image overlay** — the chart's bare `values.yaml`
     uses unprefixed image refs that don't resolve. See "Local-build
     vs released-image testing" below.
  3. Drive the migration from the `migration-console` pod:

     ```
     kubectl -n ma exec -it migration-console-0 -- /bin/bash
     console clusters connection-check
     console snapshot create
     console metadata migrate
     console backfill start
     console backfill scale 1
     console backfill status        # poll until completed
     ```

If a step takes more than ~30s, tell the user what you're waiting on
("waiting for MA helm release to be ready", "polling backfill
status") instead of going silent.

## Local-build vs released-image testing

The helm chart can be driven from two very different image sources.
Pick deliberately — they're not interchangeable.

**Released images (public ECR).** Use this when iterating on the
*skill* or running a POC against a real release. All five MA
images are published to `public.ecr.aws/opensearchproject/`:
`opensearch-migrations-{console, traffic-replayer,
reindex-from-snapshot, traffic-capture-proxy, transformation-shim}`.
For a kind POC pinned to a release tag, layer this overlay on top
of the chart's `values.yaml`:

```yaml
# public-ecr-overlay.yaml
images:
  migrationConsole:
    repository: public.ecr.aws/opensearchproject/opensearch-migrations-console
    tag: <release-version>      # e.g. 2.5.0; "latest" tracks main.
  installer:
    repository: public.ecr.aws/opensearchproject/opensearch-migrations-console
    tag: <release-version>
  reindexFromSnapshot:
    repository: public.ecr.aws/opensearchproject/opensearch-migrations-reindex-from-snapshot
    tag: <release-version>
  trafficReplayer:
    repository: public.ecr.aws/opensearchproject/opensearch-migrations-traffic-replayer
    tag: <release-version>
  captureProxy:
    repository: public.ecr.aws/opensearchproject/opensearch-migrations-traffic-capture-proxy
    tag: <release-version>
```

Install: `helm install ma <chart> -f public-ecr-overlay.yaml`.
On EKS, `deployment/k8s/aws/aws-bootstrap.sh` does the same overrides
inline via `--set images.<role>.repository=...` flags.

**Locally-built images (this repo's branch).** Use this when iterating
on MA *source* — when you've changed code in `TrafficCapture/`,
`migrationConsole/`, etc. and need to test that change end-to-end.
The repo-supplied path is:

  1. `./gradlew buildImagesToRegistry` — Gradle builds each image
     (Jib) and pushes to `localhost:5001` (default registry) plus
     `localhost:5000` (the in-cluster `docker-registry`).
  2. `helm install ma <chart> -f valuesForLocalK8s.yaml` — that
     overlay points all five image refs at `localhost:5000/migrations/<name>`,
     which the kind cluster pulls from the in-cluster registry.

If you `helm install` with `valuesForLocalK8s.yaml` *without* the
Gradle build first, every pod is `ImagePullBackOff`. If you
`helm install` with neither overlay, every pod is `ErrImagePull`
because `migrations/<name>:latest` resolves to docker.io.

When walking a user through a POC: pick the released-image path
unless they've explicitly said they're iterating on MA source.

## POC path — TrafficCapture docker-compose dev sandbox

The fastest way to see RFS move data end-to-end without standing up
k8s. See `TrafficCapture/dockerSolution/README.md` for the canonical
walkthrough — bring up the source/target/console containers, seed
data, and the `migration-console` container exposes the same
`console …` CLI as the helm install.

This is the right POC if the user just wants to *see* data move from
one cluster to another in a few minutes.

## POC path — `_reindex` from remote (lightweight alternative)

If the user can't run kind or compose and just wants the simplest
possible demo, they can drive `_reindex` from-remote against any two
clusters they already have — no MA, no helm, no compose. This skill
doesn't supply a stack for it, but the steps are well-defined; see
the MIGRATE path below for the curl shape.

## MIGRATE path — snapshot + restore

If Phase 3 picked snapshot/restore:

  1. Register the snapshot repository on source (S3 typically; for
     AOS source, IAM passrole; for self-managed, an `s3` plugin or
     shared filesystem).
  2. Take the snapshot. Wait for `SUCCESS` — poll
     `GET /_snapshot/<repo>/<snapshot>/_status` every 10-30 s.
  3. Register the same repo on target. For AOS, this means
     `repository-s3` with an assume-role; pull the exact shape from
     `packs/target-aws-managed.md`.
  4. Restore: `POST /_snapshot/<repo>/<snap>/_restore` with the index
     filter from the plan. Poll `GET /_recovery` until done.

Ask before each step that mutates state. Don't chain.

## MIGRATE path — RFS via Migration Assistant

If Phase 3 picked RFS and the user has MA deployed (or you're
deploying it for them via the helm chart above):

  - All operational driving happens through the `migration-console`
    pod. Use the `console …` CLI; don't hand-roll the underlying
    HTTP calls. Source for the CLI lives in `migrationConsole/`.
  - Connection settings live in `/etc/migration_services.yaml` inside
    the console pod. The exact shape is in `packs/target-*.md` for
    the chosen target.
  - Worker fleet size: start with `console backfill scale 1`. Only
    scale up after the first worker completes a few shards cleanly.
  - The Argo workflow templates that drive backfill / metadata /
    snapshot live in `orchestrationSpecs/`.

If the user does NOT have MA deployed and the migration is one-shot
at modest scale, prefer raw `_reindex` from remote (below) over
spinning up MA from scratch.

## MIGRATE path — `_reindex` from remote

If Phase 3 picked `_reindex` from remote:

```bash
# 1. Add source URL to target's reindex.remote.allowlist (static
#    setting — needs target restart on most managed services).
# 2. Run reindex on the target:
curl -X POST "$TARGET/_reindex?wait_for_completion=false" \
  -H 'Content-Type: application/json' -d '{
    "source": {
      "remote": {"host": "'"$SOURCE"'"},
      "index":  "<pattern>"
    },
    "dest":   {"index": "<pattern>"}
  }'
# 3. Poll GET /_tasks/<task_id> until "completed": true.
```

## Logging + artifacts

Tell the user where logs are landing.
  - kind+MA: `kubectl -n ma logs <pod>`; console output streams to
    the user's terminal directly.
  - Compose sandbox: `docker compose logs <svc>`.
  - Raw `_reindex`: capture the task response and stream stdout +
    stderr to `migration-<timestamp>.log`.

## Exit criteria

Source data is on the target. Don't trust this yet — Phase 5
verifies. Move to Phase 5.
