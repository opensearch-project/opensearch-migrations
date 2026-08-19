# Load Test Traffic Generator

Sends controlled HTTP traffic at the **Capture Proxy** to load-test the capture-and-replay pipeline.
A run is an **Argo Workflow** that creates a **k6-operator `TestRun`** and waits for it, driven from
the migration console (`workflow loadtest …`), a thin shell helper, or plain `kubectl`.

The load test itself — the scenario scripts, their libs, the document schemas and the load-profile
presets — lives **in this directory** and reaches the cluster as a ~25 KB **`FROM scratch` data
image** (`migrations/k6_scripts`), mounted read-only at `/scripts` with a Kubernetes `image:` volume
on pods running **stock `grafana/k6`**. That is the same mechanism as the migration's
[mountable transforms](../../docs/MountableTransformsDesign.md), and it is why this chart requires
**Kubernetes ≥ 1.35**. The cluster-side pieces (the operator, the per-scenario WorkflowTemplates,
the RBAC) ship in one **standalone, opt-in** chart:
`deployment/k8s/charts/components/k6LoadTest`. The chart holds no scenario content, so a run is
specified by two names — a scenario (a script path under `/scripts`) and a preset (`K6_PRESET`).

> **Deliberately separate from the migration.** The chart is **not** a dependency of any migration
> aggregate, so a normal migration deployment contains no operator, no run templates, no RBAC, and
> the `workflow loadtest` commands are hidden/inert. Load testing only becomes possible after you
> explicitly install this chart — nothing (and no agent) can trigger a load test by accident.

> **Assumption:** k6 does **not** stand up Kafka / a source cluster / the Capture Proxy. It targets
> a reachable **Capture Proxy URL** and pushes metrics to the in-cluster **otel-collector**.

**Contents**

- [How it fits together](#how-it-fits-together)
- [Install the load-test chart (opt-in)](#install-the-load-test-chart-opt-in)
- [Updating scenarios, presets & other resources](#updating-scenarios-presets--other-resources)
- [Find the Capture Proxy endpoint](#find-the-capture-proxy-endpoint)
- [Authentication](#authentication)
- [Running a load test](#running-a-load-test)
- [CLI / run-input reference](#cli--run-input-reference)
- [Scenarios](#scenarios)
- [Document schemas](#document-schemas)
- [Configuration reference](#configuration-reference)
- [Thresholds vs Checks](#thresholds-vs-checks)
- [Observe & metrics](#observe--metrics)
- [Tear down](#tear-down)
- [Integration test](#integration-test)
- [Design decisions](#design-decisions)

---

## How it fits together

```text
BUILD  (the load test itself — this directory)
──────────────────────────────────────────────────────────────
  TrafficCapture/trafficLoadTest [1] ──Dockerfile──► migrations/k6_scripts [2]  (FROM scratch)
    scenarios/*.js   lib/**   k6-config/*.env             →   the whole image root

INSTALL  (opt-in, separate from the migration)
──────────────────────────────────────────────────────────────
  deployment/k8s/charts/components/k6LoadTest [3]     (deployment resources only — no scenarios)
    ├── Chart dependency: grafana/k6-operator [4]  ──►  TestRun CRD + controller
    ├── WorkflowTemplate/k6-<scenario> [5]  (one per scenario — the whole run definition)
    └── Role/RoleBinding [6]                (console + Argo executor rights on testruns.k6.io)

USAGE  (console optional — the WorkflowTemplate is the definition)
──────────────────────────────────────────────────────────────
  kubectl create (Workflow stub) │ ./k6-run.sh [7] │ workflow loadtest run [8] │ TUI [9]
        └──────────────────┬───────────────┴──────────────────┘
                           ▼  name k6-<scenario>, pass only the parameters that differ
   Workflow (argoproj.io/v1alpha1)   labels: app=k6-load-test
     parameters ◄─ runnerEnv (K6_PRESET=<config> + overrides) · parallelism · arguments
                           ▼  (one resource task: create + wait on the TestRun's stage)
   TestRun (k6.io/v1alpha1)   name = the workflow's, owned by it
     spec.parallelism · script.localFile=/scripts/scenarios/<scenario>.js
     initializer+runner volumes ◄─ image: migrations/k6_scripts  →  mounted at /scripts
                           ▼  (operator: initializer → N runner pods)
     k6 runner pods  (stock grafana/k6 + the scripts image mounted at /scripts)
        ┌───────────────┴────────────────┐
   HTTPS│                                │ OTLP :4317  (K6_OUT=opentelemetry)
        ▼                                ▼
  Capture Proxy                    otel-collector ─► Prometheus ─► Grafana [10]
        │                                             (k6-load-test dashboard)
        ▼
  Kafka ─► replayer ─► target      (migration capture-and-replay pipeline)

  observe / manage:   kubectl get/delete wf -l app=k6-load-test   (or workflow loadtest list/stop)
```

| # | Piece | Source |
|---|---|---|
| 1 | Scenarios, libs, schemas, presets | `TrafficCapture/trafficLoadTest/{scenarios,lib,data,k6-config}` |
| 2 | Scripts image (the data store) | `Dockerfile` here, built by `buildImages/build.gradle` as `migrations/k6_scripts` |
| 3 | Standalone chart | `deployment/k8s/charts/components/k6LoadTest/` |
| 4 | k6-operator subchart (TestRun CRD) | `Chart.yaml` dependency → `grafana/k6-operator` |
| 5 | Per-scenario WorkflowTemplates (the run definition) | `templates/k6-workflowtemplates.yaml` |
| 6 | Console RBAC on `testruns.k6.io` | `templates/rbac.yaml` |
| 7 | `k6-run.sh` (console-independent submit) | `scripts/k6-run.sh` |
| 8 | `workflow loadtest` CLI (optional) | `migrationConsole/lib/console_link/console_link/workflow/commands/loadtest.py` |
| 9 | Load-test TUI (`workflow loadtest`) | `.../workflow/tui/loadtest_app.py` + `.../tui/loadtest_launch_modal.py` |
| 10 | Grafana dashboard ConfigMap | `templates/grafanaDashboard.yaml` |

A run is specified by a `scenario` (a script under `/scripts`) and a `config` (a `k6-config/*.env`
preset from the same mount, passed as `K6_PRESET`). Every value is overridable per run — the scenarios read
real environment variables over the preset file — and load is spread across `--parallelism` runner
pods by k6 execution segments.

---

## Install the load-test chart (opt-in)

Build and push the **scripts image** first — it is what carries the scenarios and presets into the
cluster (locally, `deployment/k8s/fillLocalRegistry.sh` puts it in the dev registry along with the
other `migrations/*` images):

```bash
./gradlew :buildImages:buildImagesToRegistry     # builds migrations/k6_scripts with everything else
```

> **`migrations/k6_scripts` is not a released artifact.** Load testing is a development/testing
> capability, so the image is deliberately **not** published to `public.ecr.aws`/Docker Hub. How the
> image reaches a remote cluster therefore depends on which `aws-bootstrap.sh` path you use:
>
> - **`--build` (build from source) already pushes it** to your private ECR with the other images,
>   as `migrations_k6_scripts_latest`. It is a normal build target, not a test-only one, so
>   `--skip-test-images` keeps it. This is how the EKS integration test gets the image — no extra
>   step is needed.
> - **The released-artifact path does not.** That path mirrors only the four migration images from
>   `public.ecr.aws` (`capture_proxy`, `traffic_replayer`, `reindex_from_snapshot`,
>   `migration_console`). The scripts image is absent from that list because there is no public copy
>   to mirror, so build and push the ~25 KB image yourself.
>
> Either way the k6 chart is a separate opt-in: nothing in the EKS bootstrap installs it, so a
> cluster has no operator, no WorkflowTemplates and no RBAC until you install the chart — which is
> why publishing the image alone would enable nothing. The *runner* image is unaffected: it is stock
> `grafana/k6`, an upstream artifact that mirrors normally.
>
> To push the scripts image somewhere a remote cluster can pull from:
> ```bash
> # ECR flattens images into one repo, tagged per image:
> ./gradlew :buildImages:buildImagesToRegistry -PregistryEndpoint=<acct>.dkr.ecr.<region>.amazonaws.com/<repo>
> helm upgrade --install k6-load-test "$CHART" -n ma \
>   --set scriptsImage.repository=<acct>.dkr.ecr.<region>.amazonaws.com/<repo> \
>   --set scriptsImage.tag=migrations_k6_scripts_latest
> ```
> The test runner does exactly this for you when given `--k6-scripts-image` (or, as a fallback,
> `--registry-prefix`) — see [Integration test](#integration-test).

The chart depends on the `k6-operator` subchart, so vendor that once, then install into the
migration namespace (`ma`), pointing `scriptsImage.repository` at wherever the image landed and
`image.repository` at a `grafana/k6` mirror:

```bash
CHART=deployment/k8s/charts/components/k6LoadTest
helm repo add grafana https://grafana.github.io/helm-charts
helm dependency build "$CHART"          # vendors grafana/k6-operator into charts/

helm upgrade --install k6-load-test "$CHART" -n ma \
  --set image.repository=mirror.gcr.io/grafana/k6 --set image.tag=latest \
  --set scriptsImage.repository=<registry>/migrations/k6_scripts --set scriptsImage.tag=latest
```

> **Requires Kubernetes ≥ 1.35.** The examples mount the scenarios with an `image:` volume source
> (ImageVolume), enabled by default from 1.35 — the chart declares this in `kubeVersion`, so an
> older cluster fails the install rather than starting runners with an empty `/scripts`.

Or let the CDC deploy script do it for you. It submits a capture-and-replay migration workflow from
a config and installs this chart alongside it, so the proxy, Kafka and replayer come up the same way
they do for a real migration:

```bash
./deployment/k8s/deployCdcWorkflow.sh up      # CDC pipeline + k6 chart
```

Summing up, there are three ways the chart lands on a cluster - pick by context:

| Route | Command | Use when |
|---|---|---|
| **Manual (any cluster)** | `helm upgrade --install k6-load-test deployment/k8s/charts/components/k6LoadTest …` (full flags below) | A standalone / already-running cluster, incl. EKS. Run this **before** any `k6-run.sh` / `workflow loadtest` command. |
| **Local CDC pipeline** | `./deployment/k8s/deployCdcWorkflow.sh up` | Local kind/minikube dev — submits a capture-and-replay workflow and installs k6 alongside it. |
| **Integration tests** | `pipenv run app --test-ids 0080 …` (the test runner) | Only to run a `008x` load-test case. It wraps the same `helm upgrade --install` after the migration stack is healthy. |

All three end in the same `helm upgrade --install k6-load-test <chart-path>`; the manual route is the
general one and the reference for the others.

Verify:
```bash
kubectl get crd testruns.k6.io
kubectl -n ma get pods -l app.kubernetes.io/name=k6-operator
kubectl -n ma get workflowtemplates -l app=k6-load-test    # one per launchable scenario
# both images the run will pull — the k6 runtime and the scenarios:
kubectl -n ma get workflowtemplate k6-ingest \
  -o "jsonpath={range .spec.arguments.parameters[?(@.name=='runnerImage')]}{.value}{end} {range .spec.arguments.parameters[?(@.name=='scriptsRef')]}{.value}{end}"
```

On EKS the operator image, the `grafana/k6` runner image and the operator chart are mirrored to ECR
via `deployment/k8s/charts/components/k6LoadTest/infra/mirror/k6-ecr-manifest.yaml` (opt-in —
nothing mirrors it automatically). `migrations/k6_scripts` is **not** in that manifest: mirroring
copies upstream artifacts, and it has no upstream — you build and push it yourself, as above.

---

## Updating scenarios, presets & other resources

What k6 runs is **in the scripts image**; what Kubernetes needs to start a run is **in the chart**.
Which side you edit decides whether you rebuild an image or run a `helm upgrade`.

| Edit this | Source path | Lands in | How to apply |
|---|---|---|---|
| Scenario / lib / generator / schema JS | `scenarios/*.js`, `lib/**` | `migrations/k6_scripts`, mounted at `/scripts` | rebuild + push the image |
| Preset load-shape/config | `k6-config/*.env` | `migrations/k6_scripts`, mounted at `/scripts/k6-config` | rebuild + push the image |
| Grafana dashboard | chart `files/grafana/load-test.json` | `k6-load-test-dashboard` ConfigMap (sidecar auto-import) | `helm upgrade` |
| Run distribution defaults (`parallelism`/`separate`) | chart `values.yaml` (`testRun.*`) | each `k6-<scenario>` WorkflowTemplate's parameter defaults | `helm upgrade` |
| The TestRun manifest itself (mount shape, `K6_OUT` trio, labels) | chart `templates/k6-workflowtemplates.yaml` | the templates' `resource.manifest` | `helm upgrade` |
| Runner image / tag | chart `values.yaml` (`image.*`) | the `runnerImage` parameter default | `helm upgrade` |
| Scripts image / tag / digest | chart `values.yaml` (`scriptsImage.*`) | the `scriptsRef` parameter default | `helm upgrade` |

**Apply a scenario or preset edit** — rebuild the image, then submit a fresh run:

```bash
./gradlew :buildImages:buildImagesToRegistry            # rebuild + push migrations/k6_scripts
./scripts/k6-run.sh ingest --config ingest-steady        # the new run pulls the new image
```

**Apply a chart edit** — re-render into the same release (namespace `ma`):

```bash
CHART=deployment/k8s/charts/components/k6LoadTest
helm upgrade k6-load-test "$CHART" -n ma \
  --set image.repository=mirror.gcr.io/grafana/k6 --set image.tag=latest \
  --set scriptsImage.repository=<registry>/migrations/k6_scripts --set scriptsImage.pullPolicy=Always
```

Notes:
- **Pull policy matters when the tag doesn't change.** Rebuilding `migrations/k6_scripts:latest`
  only reaches new pods if they actually re-pull it — use `scriptsImage.pullPolicy=Always` while
  iterating (what `deployCdcWorkflow.sh` and the test runner set), or pin the exact content
  with `--set scriptsImage.digest=sha256:<hex>`, which wins over the tag.
- **In-flight runs are not affected.** The image is pulled when the runner/initializer pods start, so
  a run already going keeps the old scripts and presets. (This is also why editing is safe mid-test.)
- **Adding a preset** means adding the `.env` file *and* registering it in `lib/config.js` — presets
  are opened by literal path so `k6 archive` bundles all of them (see
  [Design decisions](#design-decisions) §3). Also add the name to `CONFIG_PRESETS` in
  `console_link/workflow/commands/loadtest.py`; a unit test fails if those two drift apart.
- **Re-supply the image values** on a `helm upgrade` (as shown). Do **not** use `--reuse-values` on
  this release — it predates the `testRun` block and errors with a nil-pointer; pass the `--set
  image.*` overrides explicitly instead.
- **No `helm dependency build` needed** for file edits — that step only re-vendors the k6-operator
  subchart, which is unchanged when you edit scenarios or presets.
- **Grafana dashboard** edits are imported by the kube-prometheus-stack Grafana sidecar a few seconds
  after the ConfigMap updates — no pod restart required.

Keeping the load test out of ConfigMaps is deliberate; see [Design decisions](#design-decisions) (§3).

---

## Find the Capture Proxy endpoint

The proxy publishes its endpoint on the CaptureProxy CR, so read it from there rather than assuming
a port — `listenPort` is whatever the migration config asked for:

```bash
PROXY="https://$(kubectl -n ma get captureproxy capture-proxy \
  -o jsonpath='{.status.serviceEndpoint}')"
# → https://capture-proxy.ma.svc.cluster.local:9201 with the default config

# deployCdcWorkflow.sh prints the same value, and `status` re-prints it later:
./deployment/k8s/deployCdcWorkflow.sh status
```
(k6 uses `insecureSkipTLSVerify`, matching the self-signed proxy cert.)

> **The proxy forwards to the source cluster, so requests need the source's credentials.** Against
> the testClusters defaults (HTTPS + basic auth) an unauthenticated request returns 401 from the
> source, not from the proxy. See [Authentication](#authentication) below.

---

## Authentication

The capture proxy is a transparent forwarder: it does not add credentials, so whatever the **source
cluster** requires, the client must send. This matters because the default local stack now runs the
testClusters chart, which serves HTTPS with basic auth (`admin:admin`).

Pass HTTP Basic credentials with `AUTH_USERNAME` / `AUTH_PASSWORD`, like any other run input:

```bash
workflow loadtest run --scenario ingest --config ingest-steady --target "$PROXY" \
  -e AUTH_USERNAME=admin -e AUTH_PASSWORD=admin
```

`deployCdcWorkflow.sh up` prints exactly this command with the flags already filled in when it
detects an auth-enabled source, so the common path is copy-paste.

| Key | Default | Meaning |
|---|---|---|
| `AUTH_USERNAME` | *(unset)* | HTTP Basic username. **Setting it is what turns auth on.** |
| `AUTH_PASSWORD` | *(empty)* | HTTP Basic password. |
| `AUTH_MODE` | `basic` when `AUTH_USERNAME` is set, else `none` | Force credentials off with `none` without unsetting the username. |

Credentials are deliberately **not** baked into any `k6-config/*.env` preset — presets ship inside
the scripts image, and secrets do not belong in an image. They are per-run inputs only.

**How it works.** Scenarios import `lib/http-client.js` instead of `k6/http`. That wrapper merges an
`Authorization` header into the params of every cluster-bound request, so auth applies uniformly to
load traffic *and* to the setup calls (index creation, mapping checks) that do not go through
`pinned()`/`spread()`. `lib/id-registry.js` and `lib/control.js` keep the raw `k6/http` client on
purpose — they talk to Webdis, not the cluster, and must never receive cluster credentials.

**Running without auth** is unchanged: leave `AUTH_USERNAME` unset. To take auth off the clusters
themselves, deploy testClusters with the no-auth preset (plain HTTP, security disabled both sides):

```bash
helm upgrade --install tc deployment/k8s/charts/aggregates/testClusters \
  -f deployment/k8s/charts/aggregates/testClusters/values.yaml \
  -f deployment/k8s/charts/aggregates/testClusters/valuesNoAuth.yaml -n ma
```
`deployCdcWorkflow.sh` detects that automatically and emits a config with no `authConfig`.

The validation scripts authenticate separately — `check_proxy_ready` and `os_query` send
`CLUSTER_USERNAME`/`CLUSTER_PASSWORD` (default `admin`/`admin`), so `run_test.sh`'s assertions work
against either cluster configuration.

---

## Running a load test

Three ways, all producing the same Workflow. **None requires the migration console** — it's
optional convenience. The chart renders one `k6-<scenario>` WorkflowTemplate per scenario, carrying
the runner image, the scripts image mounted at `/scripts`, `K6_OUT` metrics, and a default
`K6_PRESET` as parameter defaults. A submission overrides only what it changes — the scenarios read
**real environment variables over the preset file**.

### Distributing the run: `parallelism` & `separate`

Both come from chart values as WorkflowTemplate parameter defaults
(`templates/k6-workflowtemplates.yaml`):

| Chart value | Default | Workflow parameter → TestRun field | Effect |
|---|---|---|---|
| `testRun.parallelism` | `1` | `parallelism` → `spec.parallelism` | Runner pods the load is split across (k6 execution segments). `--rate`/`--vus` are **global totals** divided among them. |
| `testRun.separate` | `false` | `separate` → `spec.separate` | Operator shorthand for **required** node anti-affinity — forces each runner pod onto a distinct node. |

> **`separate: true` needs at least `parallelism` schedulable nodes.** It uses
> `requiredDuringSchedulingIgnoredDuringExecution`, so if nodes < parallelism the surplus runner
> pods sit **`Pending` forever**. Keep it `false` on single-node clusters (local minikube). Enable
> it only on a real multi-node cluster to stop runners crowding one node:
> ```bash
> helm upgrade --install k6-load-test "$CHART" -n ma \
>   --set testRun.separate=true --set testRun.parallelism=<= node count>
> ```
> `separate` is valid on the vendored **k6-operator chart 4.5.0 / operator v1.5.0** TestRun CRD.

**Overriding per run — behavior differs by submission path:**
- **kubectl** / **`k6-run.sh`**: inherit the template's `parallelism` and `separate` unless you pass
  `--parallelism` (add a `separate` parameter by hand).
- **`workflow loadtest`**: **always** sends `parallelism` (its own default is `1`), so it overrides
  the template unless you pass `--parallelism`. Neither CLI exposes `--separate`; that always comes
  from the template default.

> **Stopping a run means deleting its Workflow.** The TestRun carries an owner reference to it, so
> the CR and the operator's pods go with it. There is no graceful pause.

### 1. kubectl (no console, no extra tooling)

A submission names the template and overrides only what it changes — everything else stays with the
template's defaults, so there is nothing to fetch and patch:

```bash
# Defaults straight from the template:
kubectl -n ma create -f - <<'EOF'
apiVersion: argoproj.io/v1alpha1
kind: Workflow
metadata:
  generateName: k6-ingest-
  labels: {app: k6-load-test, k6-scenario: ingest}
spec:
  workflowTemplateRef: {name: k6-ingest}
EOF

# With overrides: more runner pods, a different preset, and an env override. `runnerEnv` carries the
# WHOLE env list, so start from the template's default rather than restating K6_OUT and the OTel vars:
env=$(kubectl -n ma get workflowtemplate k6-ingest \
        -o "jsonpath={.spec.arguments.parameters[?(@.name=='runnerEnv')].value}" \
      | jq -c 'map(select(.name != "K6_PRESET"))
               + [{"name":"K6_PRESET","value":"ingest-burst"},
                  {"name":"INGEST_RATE","value":"120"}]')
kubectl -n ma create -f - <<EOF
apiVersion: argoproj.io/v1alpha1
kind: Workflow
metadata:
  generateName: k6-ingest-
  labels: {app: k6-load-test, k6-scenario: ingest}
spec:
  workflowTemplateRef: {name: k6-ingest}
  arguments:
    parameters:
      - {name: parallelism, value: "4"}
      - {name: runnerEnv, value: '${env}'}
EOF
```
Use `kubectl create` (not `apply`) — submissions use `generateName`.

### 2. `k6-run.sh` (thin helper, still no console)

```bash
./scripts/k6-run.sh ingest --config ingest-burst --parallelism 4 -e INGEST_RATE=120
```
Builds that Workflow for you: reads the template's `runnerEnv` default, applies `--config` /
`--parallelism` / `--target` / `-e KEY=VAL`, creates it, prints the run name. `CONTEXT` / `NAMESPACE`
env-overridable.

### 3. `workflow loadtest` (console convenience, when it's up)

Nicer flags + `list`/`stop`/`logs` + the TUI. **Hidden/inert unless the chart's WorkflowTemplates are present.**
```bash
workflow loadtest                 # TUI: run table + launch / stop / logs
workflow loadtest run --scenario ingest --config ingest-burst --parallelism 4 -e INGEST_RATE=120
workflow loadtest run --scenario search --config search-deep-paging --rate 100 --duration 10m --wait
workflow loadtest list                 # NAME / SCENARIO / STAGE / PARALLEL / AGE
workflow loadtest logs <run-name> -f
workflow loadtest stop <run-name>   |  --scenario mixed  |  --all
```
`--config` sets `K6_PRESET`; `--rate`/`--vus` fan out to the ingest+search vars; `-e KEY=VAL` and
`--target` add `runner.env` overrides. Bare **`workflow loadtest`** opens the TUI: a live table of
runs, with `n` launch, `s`/`S` stop, `l`/`f` logs. k6 runs are standalone TestRuns, so one never
affects a migration workflow — and the TUI is separate from `workflow manage` for the same reason.

> **`--parallelism` splits the load.** `--rate`/`--vus` are **global totals** k6 divides across the
> runner pods via execution segments — `--rate 100 --parallelism 4` ≈ 25 req/s per pod.

### Variants (only the preset / env vars change)

| Variant | How |
|---|---|
| steady / ramp / burst | preset `<scenario>-{steady,ramp,burst}` |
| document type | `-e SCHEMA=logs_data` (default `nyc_taxis`) |
| search deep paging | preset `search-deep-paging` (or `-e DEEP_PAGING_ENABLED=true -e PAGING_MODE=search_after`) |
| stateful sequences | `-e SEQUENCE_FRACTION=0.15 -e CONNECTION_MODE=pinned` |
| mixed consistency | `mixed` scenario + `REGISTRY_ENABLED=true` — **needs the chart installed with `registry.enabled=true`** (Redis+Webdis) |
| chaos control | `-e CONTROL_ENABLED=true`, then drive via Webdis — also needs `registry.enabled=true` |
| ignore thresholds | `--extra-args --no-thresholds` |

The `k6-config/*.env` files are the source of truth: they are baked into the image and read at init
time by `lib/config.js`, which merges the selected preset under the real environment variables.
(Metrics use `K6_OUT=opentelemetry`, not `--out` — see [Design decisions](#design-decisions).)

---

## CLI / run-input reference

Flags accepted by `k6-run.sh` / `workflow loadtest run` (the `-e KEY=VALUE` overrides map to the env vars
in the [Configuration reference](#configuration-reference)):

| Input | Default | Meaning |
|---|---|---|
| `--scenario` | `ingest` | `ingest` \| `search` \| `mixed` (script at `/scripts/scenarios/<scenario>.js`) |
| `--config` | `<scenario>-steady` | any `k6-config/*.env` preset name (without `.env`), passed as `K6_PRESET` |
| `--parallelism` | `1` (`workflow loadtest`); example's `4` if omitted via kubectl/`k6-run.sh` | runner pods; k6 splits `--rate`/`--vus` across them. Node anti-affinity is a separate `spec.separate` knob (chart value `testRun.separate`, default off) |
| `--target` | preset's `CAPTURE_PROXY_URL` | Capture Proxy endpoint |
| `--rate` | keep preset | request rate (sets `INGEST_RATE`+`SEARCH_RATE`) |
| `--duration` | keep preset | `DURATION` (e.g. `30s`, `10m`) |
| `--vus` | keep preset | pre-allocated VUs (`INGEST_VUS`+`SEARCH_VUS`) |
| `-e KEY=VALUE` | — | extra env override, applied last (wins over the preset); repeatable |
| `--extra-args` | — | extra flags for `k6 run` (e.g. `--no-thresholds`) |
| `--registry-enabled` | keep preset | mixed consistency ring buffer (needs `registry.enabled=true` on the chart) |
| `--control-enabled` | keep preset | chaos pause/resume/set-rate control bus |

**Document type** (`nyc_taxis` default, or `logs_data`) is a separate axis from `--scenario` (the
script). Switch it via the overrides bag: `-e SCHEMA=logs_data`.

For independent ingest/search rates in `mixed`, use `-e INGEST_RATE=…  -e SEARCH_RATE=…` rather
than the single `--rate` convenience option.

---

## Scenarios

Three scenario scripts, selected with `--scenario`:

| `--scenario` | Script | What it does |
|---|---|---|
| `ingest` | `scenarios/ingest.js` | `_bulk` + single-doc writes at a constant/ramping rate; optional stateful create→update→query→delete sequences |
| `search` | `scenarios/search.js` | flat `_search`, aggregations, partial updates, optional deep paging (scroll / search_after) |
| `mixed` | `scenarios/mixed.js` | ingest + search streams concurrently, with a write-then-read consistency check via a Redis ring buffer (Webdis) |

Each scenario reads its load shape from a `k6-config/*.env` **preset** (selected with `--config`,
default `<scenario>-steady`). Presets describe load shape only — no document-schema settings — so
any preset works with any scenario. Available presets: `ingest-steady`, `ingest-ramp`,
`ingest-burst`, `search-steady`, `search-deep-paging`, `search-ramp`, `search-burst`,
`mixed-steady`, `mixed-ramp`, `mixed-burst`.

### Load shapes (ramp / burst)

| Preset | Executor | Description |
|---|---|---|
| `*-steady` | `constant-arrival-rate` | hold a fixed rate for `DURATION` |
| `*-ramp` | `ramping-arrival-rate` | 0→peak over minutes, hold, ramp down |
| `*-burst` | `ramping-arrival-rate` | warm-up → short spike (designed to saturate the proxy) → recover |

> **Burst saturates the proxy on purpose.** k6 exits non-zero when latency/error thresholds breach
> during the spike — that's the *finding* (the saturation point), not a broken test. Add
> `--extra-args --no-thresholds` to keep the run from being marked failed.

---

## Document schemas

All scripts select a document schema via the `SCHEMA` env var — a **separate axis** from
`--scenario` (which picks the script). Override it with `-e SCHEMA=<type>`:

| `SCHEMA` | Index (default) | Document type |
|---|---|---|
| `nyc_taxis` (default) | `nyc_taxis` | NYC taxi trip records — geo_point, scaled_float, date |
| `logs_data` | `logs_data` | Structured log events — keyword, integer, text, date |

`INDEX_NAME` defaults to the `SCHEMA` value; override with `-e INDEX_NAME=my-index`. The
NYC Taxis schema mirrors `DataGenerator/NycTaxis.java` exactly (same date format, constants and
geo-point array format); the index is `dynamic: strict`, so any mismatch rejects documents.

---

## Configuration reference

Set via preset (default) or per-run override. `-e KEY=VALUE` is applied last and wins.

### Common

| Variable | Default | Meaning |
|---|---|---|
| `SCHEMA` | `nyc_taxis` | document schema (`nyc_taxis` or `logs_data`) |
| `CAPTURE_PROXY_URL` | preset | proxy endpoint (also set by `--target`) |
| `INDEX_NAME` | value of `SCHEMA` | target index |
| `DURATION` | `5m` | scenario run time (`--duration`) |
| `EXECUTOR` | `constant-arrival-rate` | set to `ramping-arrival-rate` for ramp/burst |
| `RAMP_STAGES` | single hold stage | JSON array of k6 stages, e.g. `[{"duration":"2m","target":150}]` |

### Ingest

| Variable | Default | Meaning |
|---|---|---|
| `INGEST_RATE` | `50` | target requests/second (`--rate`) |
| `INGEST_VUS` | `20` | pre-allocated VUs (`--vus`) |
| `INGEST_MAX_VUS` | `100` | max VUs k6 may spin up |
| `BULK_BATCH_SIZE` | `20` | documents per `_bulk` call |
| `SEQUENCE_FRACTION` | `0.15` | share of iterations run as create→update→query→delete |
| `BULK_FRACTION` | `0.70` | share of non-sequence iterations sent as `_bulk` |
| `CONNECTION_MODE` | `pinned` | `pinned` = keep-alive; `spread` = `Connection: close` per request |
| `NO_CONNECTION_REUSE` | _(unset)_ | `true` forces a new TCP connection per request client-side |
| `SEED_DOC_COUNT` | `100000` | expected seed doc count (informational; set `0` to skip the wait) |

### Search

| Variable | Default | Meaning |
|---|---|---|
| `SEARCH_RATE` | `50` | target requests/second |
| `SEARCH_VUS` | `30` | pre-allocated VUs |
| `SEARCH_MAX_VUS` | `150` | max VUs |
| `DEEP_PAGING_ENABLED` | `false` | `true` to activate scroll / search_after |
| `PAGING_MODE` | `scroll` | `scroll` or `search_after` |
| `SEARCH_FLAT_FRACTION` | `0.60` | fraction for flat `_search` |
| `SEARCH_AGG_FRACTION` | `0.20` | fraction for aggregation queries |
| `SEARCH_UPDATE_FRACTION` | `0.10` | fraction for partial updates |

### Mixed (needs Redis+Webdis → chart `registry.enabled=true`)

| Variable | Default | Meaning |
|---|---|---|
| `INGEST_RATE` / `SEARCH_RATE` | `30` / `20` | per-stream target rates |
| `CONSISTENCY_FRACTION` | `0.10` | fraction of search iterations that query a recently-ingested doc |
| `WEBDIS_URL` | `http://webdis:7379` | Webdis HTTP-to-Redis proxy URL |
| `REGISTRY_ENABLED` | `false` | `true` activates the ID ring buffer (`--registry-enabled`); off = consistency reads fall back to flat searches |

### Chaos control (opt-in via `CONTROL_ENABLED=true` / `--control-enabled`)

Control commands are written to a Redis key (via Webdis) and polled by VUs mid-run:

| Command (written to `control_cmd`) | Effect |
|---|---|
| `pause` | all VUs halt within ~50 ms |
| `resume` (or delete the key) | VUs proceed |
| `set-rate:N` | probabilistic skip → effective throughput ≈ N |

---

## Thresholds vs Checks

- **`check()`** (k6 built-in) — observational assertions listed in the run summary. Failed checks do
  **not** cancel the run.
- **thresholds** — pass/fail gates on metrics. A breach fails the run (non-zero exit) unless
  `--extra-args --no-thresholds` is set. On a resource-constrained cluster, latency thresholds may
  breach even when every request succeeds — use `--no-thresholds` there.

---

## Observe & metrics

```bash
kubectl -n ma get wf -l app=k6-load-test          # one row per run
kubectl -n ma logs -l k6_cr=<run-name>,runner=true -c k6 --prefix -f
```

The run name is the same on both objects — the workflow names its TestRun after itself — so it
selects the k6 pods directly.

> **`kubectl logs` only works while the pods exist.** They belong to the TestRun, which is owned by
> the run's workflow: deleting the workflow takes the pods with it. They linger after a run finishes,
> so post-run inspection works, but tail *during* a long run if you want live output. Metrics land in
> Grafana either way.

Metrics land in the existing Grafana (kube-prometheus-stack); open the **k6-load-test** dashboard.
k6 pushes OTLP gRPC to the otel-collector (`K6_OUT=opentelemetry`,
`K6_OTEL_GRPC_EXPORTER_ENDPOINT=otel-collector:4317`); the collector exposes a Prometheus scrape
endpoint the dashboard reads.

> On a resource-constrained cluster the default latency thresholds may breach (k6 exits non-zero)
> even though every request succeeds. Pass `--extra-args --no-thresholds` for a clean run.

---

## Tear down

```bash
helm uninstall k6-load-test -n ma        # removes operator + WorkflowTemplates + RBAC
# or, if you brought it up via the data-plane script:
./deployment/k8s/deployCdcWorkflow.sh down
```

---

## Integration test

`Test0080CdcK6LoadTest` (`migrationConsole/lib/integ_test/.../test_cases/k6_load_test_tests.py`)
layers a short k6 run on a live CDC migration and asserts the traffic is captured and replayed to
the target **under load**. It's explicit-selection only, and IDs `0080-0089` are the reserved
load-test range.

The **only** command that installs the chart for you is the test-automation runner
(`libraries/testAutomation`) — it runs `helm upgrade --install k6-load-test` once the migration stack
is healthy, so you don't install separately for the test. Selecting a `008x` ID is the sole trigger
and there is no opt-in flag; the runner installs the chart for that run only, which keeps every other
test run free of the k6 operator. To bring the chart up without running a case, use the manual
`helm upgrade --install` above. On a cloud cluster whose migration stack is already up (e.g. from
`aws-bootstrap.sh`), add `--skip-install` so only the k6 chart is added:

```bash
pipenv run app --skip-install --test-ids 0080 \
  --source-version ES_7.10 --target-version OS_2.19 \
  --k6-scripts-image <ecr-repo>:migrations_k6_scripts_latest
```

`--k6-scripts-image` takes a complete reference — the same way the mountable transform fixtures are
handed to the runner as `--transform-image-*`, rather than inferring a layout from a registry.
A digest pin (`<repo>@sha256:…`) works too and reaches the chart as `scriptsImage.digest`, which wins
over the tag. Without the argument the reference is derived from `--registry-prefix`, which is what
the local kind jobs use (`docker-registry:5001/` → `docker-registry:5001/migrations/k6_scripts`).

CI runs this on EKS as the `eks-cdc-k6-load-test` job
(`jenkins/migrationIntegPipelines/eksCdcK6LoadTestCover.groovy`): post-merge on `main`, on a 6-hour
cadence, and on a PR labelled `run-eks-tests`. It reuses `eksCdcIntegPipeline`, which passes
`--k6-scripts-image=<registryEndpoint>:migrations_k6_scripts_latest`. The `_latest` tag matches how
every other MA image is referenced on EKS, and the ECR registry is created per run
(`migration-ecr-<stage>-<region>`), so there is no second writer to race with.

For any run **outside** this test harness, install the chart yourself first (see
[Install the load-test chart](#install-the-load-test-chart-opt-in)) — there is no equivalent
auto-install on `aws-bootstrap.sh` or the `workflow loadtest` / `k6-run.sh` commands.

---

## Design decisions

### Scenario & tool

- **Tool: k6.** Each Virtual User holds one persistent TCP connection — mapping directly to
  `connectionId` in the Capture Proxy, the foundation for connection-pinning in the sequences path.
- **Proxy TLS.** The proxy listens HTTPS with a self-signed cert; k6 uses
  `insecureSkipTLSVerify: true`. The source cluster behind the proxy runs plain HTTP.
- **Document schemas.** Scripts select a generator via `SCHEMA`; each provides its own index
  mapping, query samples and update-body generator, all in one folder per schema. `documents.js`
  `open()`s its own `mapping.json` and re-exports it, so a scenario reads `docs.mapping` and never
  names a schema's file paths. Adding a type needs only a new `lib/data/<name>/` holding
  `documents.js`, `queries.js` and `mapping.json`.
- **ID registry (mixed).** Cross-VU write-then-read state uses a Redis list via a Webdis HTTP proxy
  — k6's built-in `http` module calls Webdis (`GET /LPUSH/key/val`), so no xk6/native Redis build.

### Deployment architecture

Why the current setup looks the way it does (decision → rationale → alternative rejected):

1. **Separate, opt-in chart — not part of the migration.** The load-test chart is *not* a
   dependency of any migration aggregate, so a normal migration deployment contains no operator,
   no example runs, no RBAC, and the `workflow loadtest` commands are hidden/inert. This is deliberate
   safety: a user or an agent cannot accidentally fire a load test while running a migration.
   *Defense in depth:* four independent things are missing by default (the `testruns.k6.io` CRD,
   the RBAC on it, the `k6-<scenario>` WorkflowTemplates, and the visible CLI) — any one blocks a run.
   Argo being present changes nothing: with no template to instantiate, there is no run to submit.
   *Rejected:* bundling k6 into `migrationAssistantWithArgo`, which would make load testing always
   present and discoverable.

2. **k6-operator `TestRun` CRs — not an Argo WorkflowTemplate.** Chosen for native distributed
   runners (one test split across `--parallelism` pods via k6 execution segments) and a
   CRD-native lifecycle. *Cost:* a second orchestrator (the operator) alongside Argo — but it is
   scoped entirely to this opt-in chart. The earlier Argo `k6LoadTest.ts` template was retired.

3. **An OCI image is the data store — scenarios are files in this directory, not ConfigMaps.** The
   whole load test (`scenarios/`, `lib/`, `k6-config/`) lives here in its natural tree and
   is published as `migrations/k6_scripts`, a `FROM scratch` image holding nothing but those files.
   The chart mounts it read-only at `/scripts` with a Kubernetes `image:` volume on the initializer
   and every runner, which otherwise run **`grafana/k6`**. Imports and `open()` are ordinary
   relative paths and `script.localFile` is just a path under the mount. This is the same mechanism
   the migration uses for [mountable transforms](../../docs/MountableTransformsDesign.md), down to
   the `FROM scratch` + `COPY` Dockerfile and optional digest pinning.
   *Costs:* editing a scenario is an image rebuild + push rather than a `helm upgrade`, and
   ImageVolume means the chart requires k8s ≥ 1.35 (declared in `Chart.yaml`'s `kubeVersion`).

4. **Presets are files in the image, selected by `K6_PRESET`; overrides are `env`; metrics via
   `K6_OUT`, not `--out`.** `lib/config.js` `open()`s every `k6-config/*.env` and exports `CFG`, the
   selected preset merged **under** `__ENV` — so a real environment variable always wins over the
   preset, and per-run overrides stay plain `runner.env` entries. Scenarios read `CFG.X`, never
   `__ENV.X` directly. *Mechanism note:* the presets are opened by **literal path**. k6 resolves `open()` at init time and the operator's initializer bundles the result into
   the archive the runner pods execute; a computed path would bake in whichever preset the
   *initializer* saw and no runner could pick another. Metrics output is set with the `K6_OUT` env
   var because the operator also feeds `spec.arguments` to that same `k6 archive`, which rejects the
   run-only `--out` flag.

5. **Helm-rendered WorkflowTemplates are the single definition; runs are kubectl-native; the console
   is optional.** The chart renders one `k6-<scenario>` WorkflowTemplate per scenario (runner image,
   the scripts image mounted at `/scripts`, the script path under it, `K6_OUT`, default `K6_PRESET`,
   labels) whose single task creates the TestRun and waits on its stage. A run is `kubectl create` of
   a small Workflow naming that template, so it works with **no console and no console image** —
   `./k6-run.sh` is a thin helper over it, and `workflow loadtest` is the same submission as
   convenience (nicer flags, `list`/`stop`/`logs`, TUI), guarded by the template-presence check. One
   definition (Helm), consumed everywhere; no spec-builder to keep in sync.
   *Why a WorkflowTemplate rather than a ConfigMap of example TestRuns:* creating a TestRun starts a
   load test, so the chart cannot ship TestRun objects — the definition used to be stashed as JSON
   strings in a ConfigMap, which every consumer had to fetch and patch. A WorkflowTemplate is inert
   until instantiated, which is the definition/instance split that ConfigMap was imitating. Argo is
   already a hard dependency of the migration, and the operator still does the part Argo cannot
   (execution-segment sharding and the synchronised start). *Rejected:* the console CLI as the *only*
   submission path (couples every run to a current console image); replacing the operator with a
   pure-Argo fan-out (would mean reimplementing segment sharding). *Note:* `kubectl create` (not
   `apply`), because submissions use `generateName`.

6. **`--parallelism` splits global load; `separate` spreads pods across nodes.** `--rate` / `--vus`
   are totals divided across runner pods by k6 execution segments — surfaced explicitly so results
   aren't misread as per-pod. The defaults live in chart values (`testRun.parallelism`,
   `testRun.separate`) and render as parameter defaults on every WorkflowTemplate. `separate: true` is the
   operator's shorthand for **required** node anti-affinity; it defaults to `false` because it needs
   ≥ `parallelism` schedulable nodes (single-node minikube would otherwise wedge surplus pods in
   `Pending`). *Rejected:* hand-writing an `affinity` block per runner — `separate` is the
   CRD-native equivalent, valid since well before the vendored operator v1.5.0.

7. **Only the scenarios are ours; the runtime is upstream and mirrored normally.** The runner image
   is `grafana/k6`, so it goes through the ordinary mirroring path
   (`infra/mirror/k6-ecr-manifest.yaml`, kept separate from the migration's manifest so k6 mirroring
   is also opt-in). `migrations/k6_scripts` is deliberately **not** published as a release artifact
   — load testing is a dev/test capability, so it is absent from `publishedRepoByImageName` and from
   the list `aws-bootstrap.sh` mirrors out of `public.ecr.aws`. It is still an ordinary build target,
   so `aws-bootstrap.sh --build` pushes it to the private ECR registry with everything else; only a
   deployment made purely from released artifacts has to build and push those 25 KB itself.
   *Rejected:* publishing a public k6 image, which would make a load generator part of the released
   surface for every customer — and would enable nothing on its own, since the bootstrap never
   installs the k6 chart.

8. **Validation scripts assume a running data plane.** A single parameterized
   `scripts/run_test.sh --scenario ingest|search|mixed|sequences [--shape steady|ramp|burst] [--run]`
   submits a k6 run (console-independently, via `k6-run.sh`) and asserts against the in-cluster
   services with `kubectl` (Kafka/OpenSearch), in-cluster `curl` (proxy, Webdis), and PromQL against
   `kube-prometheus-stack`. The runtime control plane (pause/resume/set-rate) is a distinct
   behavioural test kept separate as `scripts/run_test_chaos.sh`. Setup/teardown is
   `deployCdcWorkflow.sh up`/`down`.
