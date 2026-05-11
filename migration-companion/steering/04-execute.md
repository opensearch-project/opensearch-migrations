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

## POC: pick the stack first

Two POC stacks ship in this repo. Pick deliberately — they teach
different things, and they have different resource costs.

| Stack                                  | What it teaches                              | Time-to-up | Disk + RAM budget       | When to use                                                                 |
| -------------------------------------- | -------------------------------------------- | ---------- | ----------------------- | --------------------------------------------------------------------------- |
| `TrafficCapture/dockerSolution/`       | RFS end-to-end + console CLI ergonomics      | ~3 min     | ~6 GB RAM, ~10 GB disk  | **Default for "show me how it works"**. Laptop-friendly. No k8s required.  |
| `deployment/k8s/charts/.../migrationAssistantWithArgo/` (kind) | Real MA shape (helm + Argo + console pod)    | ~10 min    | **~16 GB RAM**, ~30 GB disk | User wants to see the production deployment shape; has the headroom.        |

If unsure which the user wants, ask once: "fast end-to-end demo
(docker compose) or production-shape (kind + helm)?" Default to
docker compose if they don't pick.

If the host has < 16 GB RAM free, refuse the kind path and route to
docker compose. Don't pretend a kind+helm POC will work on an 8 GB
machine — it'll OOM mid-install.

## POC path — TrafficCapture docker-compose dev sandbox (default)

The fastest way to see RFS move data end-to-end. See
`TrafficCapture/dockerSolution/README.md` for the canonical
walkthrough. You'll get a source ES container, a target OS
container, and a `migration-console` container with the same
`console …` CLI as the helm install.

Steps the agent should narrate:

  1. `cd TrafficCapture/dockerSolution && ./gradlew :composeUp`
     (or follow the README's current invocation — it changes).
  2. Tell the user the seeded endpoints: source on
     `http://localhost:9200` (or whatever the compose file maps),
     target on `http://localhost:9201`, console at
     `docker compose exec migration-console bash`.
  3. From inside the console container, run the same
     `console clusters connection-check / snapshot create /
     metadata migrate / backfill start / backfill status`
     sequence as the helm path.

**Tear-down (record this for Phase 6):**
`./gradlew :composeDown` (or `docker compose down -v` from the
sandbox dir, which also removes volumes).

## POC path — Migration Assistant helm chart on a local k8s

Use this only when the user has explicitly asked for the
production shape and has ≥16 GB RAM free.

  1. Create a local k8s cluster (kind is fine for ~16 GB hosts;
     k3d / minikube also work). Confirm `docker stats` shows
     headroom before installing the chart.
  2. Install the chart from
     `deployment/k8s/charts/aggregates/migrationAssistantWithArgo/`.
     **Always pass an image overlay** — the chart's bare `values.yaml`
     uses unprefixed image refs that don't resolve. See "Local-build
     vs released-image testing" below.
  3. Tell the user the seeded endpoints: source/target services
     resolve in-cluster as `<release>-source` / `<release>-target`;
     the console pod is `migration-console-0` in the install
     namespace.
  4. Drive the migration from the `migration-console` pod:

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

**Tear-down (record this for Phase 6):**
`helm -n ma uninstall ma && kind delete cluster` (or your local
cluster's equivalent: `k3d cluster delete`, `minikube delete`).

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

## POC path — `_reindex` from remote (lightweight alternative)

If the user can't run kind or compose and just wants the simplest
possible demo, they can drive `_reindex` from-remote against any two
clusters they already have — no MA, no helm, no compose. This skill
doesn't supply a stack for it, but the steps are well-defined; see
the MIGRATE path below for the curl shape.

## MIGRATE: deploying MA for production

Phase 4 for MIGRATE assumes the user is *not* on a laptop. If they
don't already have MA running, point them at the right deployment
artifact for their environment — don't reuse the kind path, which is
explicitly POC-shaped.

| Environment                 | Deploy via                                          |
| --------------------------- | --------------------------------------------------- |
| Existing EKS cluster        | `deployment/k8s/charts/.../migrationAssistantWithArgo/` with the public-ECR overlay above |
| Greenfield AWS account      | `deployment/k8s/aws/aws-bootstrap.sh` (CDK + EKS bring-up + chart install in one) |
| Self-hosted on-prem k8s     | Same chart, with whatever image registry the cluster can pull from (use `generatePrivateEcrValues` pattern from P17 in `pitfalls.md`, or mirror the public images into the org registry) |
| Existing OpenShift / GKE    | Same chart; verify SCC / pod-security-admission compatibility before installing |

If the user wants to *try* MA before committing to deploying it for
real, route them back to the POC path (compose stack) and explicitly
say so — don't let a MIGRATE user stand up a kind cluster and call
it production.

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

## When a step fails

Migrations fail mid-flight. Don't retry blindly — capture state
first, then retry the smallest unit that failed.

  - **`console snapshot create` hung or errored**: read
    `console snapshot status` (and `--json` for the raw response).
    On k8s, also `kubectl logs <snapshot-pod>`. Check the source's
    `_snapshot/_status` directly. Common causes: snapshot repo not
    registered on source, S3 IAM passrole missing, source disk full.
  - **`console metadata migrate` errored**: rerun with
    `--detailed` or read the migration-console log
    (`kubectl logs migration-console-0 -c migration-console`).
    Mapping conflicts on the target are the usual cause; surface
    them and ask the user how to resolve before retrying.
  - **`console backfill status` shows workers `Failed`**:
    `kubectl logs <rfs-worker-pod>` for the worker, plus
    `argo get <workflow>` if Argo is driving the run. Look for
    target `_bulk` rejections (queue full → scale target;
    mapping mismatch → fix mapping, requeue the failed shard).
  - **`_reindex` from remote task hung**:
    `GET /_tasks/<task_id>` on target, then
    `POST /_tasks/<task_id>/_cancel` if needed. Restart by
    re-issuing reindex with a `query` filter that excludes the
    docs already in target.

Tell the user *what* you captured before suggesting *what* to try
next. Don't roll up errors into "something went wrong, retrying".

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
