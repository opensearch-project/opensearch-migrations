# Changes to local minikube install flow

## 1. `fillLocalRegistry.sh` — remove `-PexcludeESCustomTestImages`

**Problem:** The Gradle build excluded all custom Elasticsearch images when
`-PexcludeESCustomTestImages` was passed.  `elasticsearchWithSearchGuard` (in
`buildKitProjects`) has `requiredDependencies: ["buildKit_customElasticsearch710"]`.
`registerBuildKitBakeAggregateTask` resolves that dependency by looking up
`buildKit_customElasticsearch710` in `allProjectsByTaskName`, which is built from
`buildKitProjects + testBuildKitProjects`.  With the flag set, `customElasticsearch710`
was absent from `testBuildKitProjects`, so `allProjectsByTaskName` returned `null`, the
dependency was silently skipped, and the bake tried to pull
`docker-registry:5000/migrations/custom-elasticsearch:7.10.2` as a base image — an image
that was never pushed.  The build then failed with:

```
ERROR: target elasticsearchWithSearchGuard: failed to solve:
docker-registry:5000/migrations/custom-elasticsearch:7.10.2: not found for docker runtime
```

The script appeared to succeed under Colima because the `registry-data` Docker volume
persists inside Colima's Lima VM between sessions; the image had been pushed during an
earlier full build (without the exclusion flag) and was still present in the registry.
A fresh Docker install had an empty registry, so the failure was reproducible there.

**Fix:** Remove `-PexcludeESCustomTestImages` from the Gradle invocation.  Without it,
`customElasticsearch710` is included in `testBuildKitProjects` and therefore present in
`allProjectsByTaskName`.  `collectWithDependencies` then correctly places it in bake
phase 0 and `elasticsearchWithSearchGuard` in phase 1, so the base image is pushed
before it is needed.

## 2. `fillLocalRegistry.sh` — wait for buildkitd to be ready

**Problem:** `docker run -d` returns as soon as the container is started, before
buildkitd's TCP listener is accepting connections.  On Docker on Linux there is no VM
boot overhead between the host and the daemon, so the race is more pronounced: subsequent
`docker buildx create` or bake calls could fail or time-out sporadically.

**Fix:** Add a `nc -z localhost 1234` readiness loop (60 s timeout) after the `docker
run` block and before `docker buildx create` / any build invocation.

## 3. `startMinikube.sh` — set `HOST_IP_FROM_WITHIN_MINIKUBE` when minikube is already running

**Problem:** `HOST_IP_FROM_WITHIN_MINIKUBE` was only set inside the `else` branch (fresh
minikube start).  When minikube was already running the `if` branch printed "minikube
already running" and left the variable unset.  The caller
(`startMinikubeAndDeployCharts.sh`) then set:

```bash
export imageRegistryPrefix="${HOST_IP_FROM_WITHIN_MINIKUBE}:5001/"
```

which resolved to `:5001/` — an invalid registry prefix — causing `ImagePullBackOff` on
every pod that tried to pull from the local registry.

**Fix:** Query the gateway IP via `minikube ssh` in the `if` branch as well, so
`HOST_IP_FROM_WITHIN_MINIKUBE` is always populated before the caller uses it.

## 4. Helm hook delete-policy: `before-hook-creation` hang on fresh install

### Root cause

`helm.sh/hook-delete-policy: before-hook-creation` instructs Helm to delete any
pre-existing instance of a hook resource before creating the new one.  Helm implements
this by calling `Delete` on the resource and then setting up a Kubernetes watch waiting
for a `DELETED` event.

On a **fresh install** where the resource has never been created, `Delete` returns
`404 Not Found` (logged as "ignoring delete failure"), but Helm still registers the
watch.  Because the resource was never created, no `DELETED` event is ever emitted and
Helm blocks indefinitely — until `--timeout` (20 m) expires:

```
level=DEBUG msg="starting delete resource"  name=ma-helm-installer kind=ClusterRole
level=DEBUG msg="ignoring delete failure"   name=ma-helm-installer kind=ClusterRole
                                            error="... not found"
level=DEBUG msg="waiting for resources to be deleted" count=1 timeout=20m0s
           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ hangs here
```

The Helm default delete policy for any hook resource with no explicit
`helm.sh/hook-delete-policy` annotation is also `before-hook-creation`.  This means
every pre-install hook resource lacking the annotation is subject to the same hang.

This cascades through every affected hook in execution-weight order:

| Weight | Hook | Resource | Source of policy |
|--------|------|----------|-----------------|
| −5 | pre-install | `ServiceAccount`, `ClusterRole` in `permissions.yaml` | explicit |
| −4 | pre-install | `ClusterRoleBinding` in `permissions.yaml` | explicit |
| −2 | pre-install | `logs-pvc` PVC in `logsPvc.yaml` | **Helm default** |
| −1 | pre-install | `log-aggregation-config`, `fluentbit-lua-scripts` ConfigMaps in `fluentConfig.yaml` | **Helm default** |
| −1 | pre-install | `argo-artifact-s3-creds` Secret in `argoArtifactRepository.yaml` | **Helm default** |
| −1 | pre-install | `ma-kyverno-zero-resources-policy` ConfigMap in `kyvernoZeroResourceRequests.yaml` | explicit |
| 0 | pre-install | `ma-dependency-installer` Job in `installJob.yaml` | explicit |
| 1 | post-install | `create-otel-service-monitor` Job | explicit |
| 1 | post-install | `create-s3-bucket-ma` Job | explicit |
| 5 | post-install | `ma-install-migrations-resources` Job | explicit |
| 50 | post-install | `create-migrations-ca` Job | explicit |

Hooks in the table above that are gated on flags disabled by default in the local values
(`aws.configureAwsEksResources`, `cluster.dedicatedKarpenterNodePoolForMigrationConsole`,
`kyvernoPolicies.mountLocalAwsCreds`) are not rendered and therefore do not hang.

### Design decisions

The affected resources fall into two distinct categories with different lifecycle
requirements, so two different strategies are used.

#### Why not just replace `before-hook-creation` with `hook-failed` everywhere?

`hook-failed` removes the hang on fresh installs but breaks upgrade retries for Jobs.
A Helm process can be killed (timeout, Ctrl-C, network loss) while a hook Job is still
running.  In that case neither `hook-succeeded` nor `hook-failed` ever fires, so the Job
remains in the cluster.  On the next `helm install` or `helm upgrade`, Helm would try to
create a new Job with the same name and hit "already exists", because Kubernetes Jobs are
largely immutable — the spec cannot be updated in place.

`before-hook-creation` was originally there to handle exactly this scenario: delete the
stale Job unconditionally before creating a fresh one.  Removing it entirely would trade
one failure mode (hang on first install) for another (collision on retry).

#### Why not use `lookup` everywhere (including persistent resources)?

The `lookup` approach (see below) directly addresses the root cause and is the correct
fix for Jobs.  However it is unnecessary for persistent resources (RBAC, ConfigMaps,
PVC, Secret) because:

1. **Server-side apply handles upgrades correctly.** Helm applies all resources — hooks
   included — via server-side apply, which patches the existing resource with the new
   declared state.  For ConfigMaps, ClusterRoles, and similar resources, this means the
   content is fully replaced on upgrade.  There is no need to delete-and-recreate.
2. **These resources should not be deleted between phases.** The ServiceAccount and
   ClusterRole created in the pre-install phase are also consumed by the post-install
   phase.  Deleting them before creation would create a gap where the post-install job
   has no permissions.  `hook-failed` is the semantically correct policy: persist
   throughout the install, clean up only if the creation itself fails.
3. **Jobs are immutable; other resources are not.** The "already exists" collision that
   forces `before-hook-creation` on Jobs does not apply to patachable resources.
   Server-side apply on a ConfigMap or ClusterRole that already exists is a no-op if
   nothing changed and a patch if something did — both outcomes are correct.

#### Summary of strategy per resource category

| Category | Policy chosen | Reasoning |
|----------|---------------|-----------|
| Persistent (RBAC, ConfigMaps, PVC, Secret) | `hook-failed` | Must survive across install phases; server-side apply handles upgrades; `hook-failed` is semantically correct, not a workaround |
| Ephemeral Jobs | `lookup`-conditional `before-hook-creation` + `hook-succeeded,hook-failed` | Jobs are immutable so stale ones from killed upgrades must be explicitly deleted; `lookup` makes `before-hook-creation` conditional on whether the Job actually exists, eliminating the hang on fresh installs while preserving stale-cleanup on retries |

### Fix — persistent hook resources that must survive the install phase

Changed to `hook-failed` (clean up only if resource creation itself fails):

| File | Resources changed |
|------|------------------|
| `permissions.yaml` | `ServiceAccount`, `ClusterRole`, `ClusterRoleBinding` |
| `logsPvc.yaml` | `logs-pvc` PVC (also carries `resource-policy: keep`) |
| `fluentConfig.yaml` | `log-aggregation-config`, `fluentbit-lua-scripts` ConfigMaps |
| `argoArtifactRepository.yaml` | `argo-artifact-s3-creds` Secret |
| `kyvernoZeroResourceRequests.yaml` | `ma-kyverno-zero-resources-policy` ConfigMap |

Effect:
- No deletion is attempted before creation on a fresh install (removes the hang).
- On `helm upgrade`, Helm applies them via server-side apply, patching any changes.
- Resources persist across the install/upgrade lifecycle as required.
- If resource creation fails they are cleaned up so a retry starts clean.

### Fix — ephemeral Jobs (lookup-based conditional policy)

The fix uses Helm's `lookup` function, which queries the Kubernetes API at
template-render time, to make `before-hook-creation` conditional:

```yaml
{{- $existingJob := lookup "batch/v1" "Job" .Release.Namespace "job-name" }}
"helm.sh/hook-delete-policy": {{ if $existingJob }}before-hook-creation,{{ end }}hook-succeeded,hook-failed
```

Behaviour:
- **Fresh install** (`lookup` returns nil): policy resolves to `hook-succeeded,hook-failed`.
  No deletion is attempted, no watch is registered, no hang.
- **Upgrade / retry with stale job** (`lookup` returns the job object): policy resolves to
  `before-hook-creation,hook-succeeded,hook-failed`.  Helm deletes the stale job (which
  actually exists), the watch fires immediately on the real `DELETED` event, and a fresh
  job is created.

| File | Job name |
|------|----------|
| `installJob.yaml` | `{{ .Release.Name }}-dependency-installer` |
| `migrationsResources.yaml` | `{{ .Release.Name }}-install-migrations-resources` (post-install) |
| `otel/simpleCollectorAsIndependentDaemonset.yaml` | `create-otel-service-monitor` |
| `s3/createS3Bucket.yaml` | `create-s3-bucket-{{ .Release.Name }}` |
| `selfSignedClusterIssuer.yaml` | `create-migrations-ca` |

### Why `--timeout` is necessary and how to size it

`--wait` tells Helm to block until all resources are ready and all hooks have completed.
Without `--timeout`, a single stuck resource (crash-loop pod, failed image pull, hung job)
would cause the `helm install` call to block forever with no way to detect the failure
programmatically.  `--timeout` is therefore always required when `--wait` is used.

The timeout must cover the **entire sequential critical path** that `--wait` tracks:

```
pre-install hooks → chart resource readiness → post-install hooks
```

The relevant job-level deadlines in this chart are:

| Phase | Resource | `activeDeadlineSeconds` |
|-------|----------|------------------------|
| Pre-install weight=0 | `installJob` (installs all child charts) | 
| Post-install weight=1 | `create-s3-bucket` | 
| Post-install weight=5 | `ma-install-migrations-resources` | 
| Post-install weight=50 | `create-migrations-ca` |

Because these phases are **sequential**, the Helm timeout must be larger than their sum,
not their maximum. Sum depends on the machine, so you might need to adjust it (20m seems reasonable).

The `before-hook-creation` bug masked this: when Helm hung immediately on the first hook
deletion the install always failed fast, so the inadequate timeout was never reached.
With the hang fixed, the timeout now applies to real work and the too-small value becomes
visible.


## 5. `valuesForLocalK8sWithEnvSubst.yaml` — wait for Strimzi before declaring installJob complete

**Problem:** The `migration-console` StatefulSet's init container polls
`/openapi/v3/apis/kafka.strimzi.io/v1` every 2 seconds for up to 180 attempts (6 minutes),
waiting for Strimzi CRDs to be registered with the Kubernetes API server:

```bash
for attempt in $(seq 1 180); do
  if kubectl get --raw /openapi/v3/apis/kafka.strimzi.io/v1 > "$OPENAPI_PATH" 2>/dev/null; then
    break
  fi
  sleep 2
done
```

The `installJob` installs all child charts in parallel background processes.  With
`strimzi-kafka-operator.waitForInstallation: false` (the previous local setting), the
background process for strimzi runs `helm install strimzi-kafka-operator` **without
`--wait`**, which returns immediately after submitting resources to the API server.  The
installJob therefore completes before the Strimzi CRDs are established.

Helm then applies the main chart resources, including the `migration-console` StatefulSet.
The init container starts immediately and begins polling for Strimzi CRDs that may not
yet be registered, causing a visible stall (migration-console pod stays in `Init:0/1`
while all other pods are `Running`).  If Strimzi is still pulling images or the API
server is under load, the init container can exhaust its 6-minute budget and fail.

**Why only strimzi?**  Strimzi is the only chart whose readiness directly blocks a main
chart resource (the migration-console StatefulSet via its init container).  All other
charts either:
- have internal retry logic that tolerates delayed readiness (e.g.
  `selfSignedClusterIssuer` retries cert-manager apply for 10 minutes), or
- run only in post-install hooks, which execute after main chart resources are ready —
  by then several minutes have passed and the charts are already running.

**Fix:** Change `strimzi-kafka-operator.waitForInstallation` from `false` to `true` in
`valuesForLocalK8sWithEnvSubst.yaml`.  With `true`, the installJob's background process
runs `helm install strimzi-kafka-operator ... --wait`, which blocks until the Strimzi
operator pod is ready and CRDs are established.  Only then does the background process
signal completion.  The installJob waits for all background PIDs before exiting, so Helm
will not apply main chart resources until Strimzi is confirmed ready.

### Not fixed (not on the install path)

The following still carry `before-hook-creation` but only execute during
`helm uninstall` (`pre-delete`/`post-delete` hooks) and do not affect `helm install`:

- `migrationsResources.yaml` — pre-delete job (`ma-remove-argo-migration-templates`)
- `childrenChartInstaller/uninstallJob.yaml` — pre-delete and post-delete jobs
- `s3/deleteS3Bucket.yaml` — pre-delete job
