# Running the k6 load test in Kubernetes

The scenarios in [`../`](../) run as **k6-operator `TestRun`** CRs, driven from the migration
console (`workflow k6 …`) or the TUI. Everything k6 needs — the operator, the scenarios (as
ConfigMaps), and the console's RBAC — ships in one **standalone, opt-in** chart:
`deployment/k8s/charts/components/k6LoadTest`.

> **Deliberately separate from the migration.** The chart is **not** a dependency of any migration
> aggregate, so a normal migration deployment contains no operator, no scenarios, no RBAC, and the
> `workflow k6` commands are hidden/inert. Load testing only becomes possible after you explicitly
> install this chart — nothing (and no agent) can trigger a load test by accident.

> **Assumption:** k6 does **not** stand up Kafka / a source cluster / the Capture Proxy. It targets
> a reachable **Capture Proxy URL** and pushes metrics to the in-cluster **otel-collector**.

---

## How it fits together

```text
INSTALL  (opt-in, separate from the migration)
──────────────────────────────────────────────────────────────
  deployment/k8s/charts/components/k6LoadTest [1]
    ├── Chart dependency: grafana/k6-operator [2]  ──►  TestRun CRD + controller
    ├── ConfigMap/k6-scenarios [3]         (flat, type-prefixed files/k6/scripts/* → mounts at /scripts)
    ├── ConfigMap/k6-preset-<name> [4]     (one per k6-config/*.env, one key per var → envFrom)
    ├── ConfigMap/k6-testrun-examples [5]  (one ready-to-run TestRun JSON per scenario)
    └── Role/RoleBinding [6]               (grants the console SA rights on testruns.k6.io)

USAGE  (console optional — the example is the definition)
──────────────────────────────────────────────────────────────
  kubectl create (from example) │ ./k6-run.sh [7] │ workflow k6 run [8] │ TUI: k [9]
        └──────────────────┬───────────────┴──────────────────┘
                           ▼  load k6-testrun-examples.<scenario>, patch env/preset/parallelism
   TestRun (k6.io/v1alpha1)   labels: app=k6-load-test
     spec.parallelism · script.localFile=/scripts/SCENARIO_<scenario>.js
     runner.envFrom ◄─ k6-preset-<config>   ;   runner.env ◄─ overrides (win) + K6_OUT
     runner/initializer volumes ◄─ k6-scenarios (flat ConfigMap mounted at /scripts)
                           ▼  (operator: initializer → N runner pods)
               k6 runner pods  (stock grafana/k6)
        ┌───────────────┴────────────────┐
   HTTPS│                                │ OTLP :4317  (K6_OUT=opentelemetry)
        ▼                                ▼
  Capture Proxy                    otel-collector ─► Prometheus ─► Grafana [10]
        │                                             (k6-load-test dashboard)
        ▼
  Kafka ─► replayer ─► target      (migration capture-and-replay pipeline)

  observe / manage:   kubectl get/delete testrun -l app=k6-load-test   (or workflow k6 list/stop)
```

| # | Piece | Source |
|---|---|---|
| 1 | Standalone chart | `deployment/k8s/charts/components/k6LoadTest/` |
| 2 | k6-operator subchart (TestRun CRD) | `Chart.yaml` dependency → `grafana/k6-operator` |
| 3 | Scenario code ConfigMap (flat) | `templates/k6-scenarios-configmap.yaml` (from `files/k6/scripts/`) |
| 4 | Per-preset `envFrom` ConfigMaps | `templates/k6-presets-configmap.yaml` (parses `files/k6/k6-config/*.env`) |
| 5 | Example TestRuns (the run definition) | `templates/k6-testrun-examples.yaml` |
| 6 | Console RBAC on `testruns.k6.io` | `templates/rbac.yaml` |
| 7 | `k6-run.sh` (console-independent submit) | `TrafficCapture/trafficLoadTest/scripts/k6-run.sh` |
| 8 | `workflow k6` CLI (optional) | `migrationConsole/lib/console_link/console_link/workflow/commands/k6.py` |
| 9 | TUI k6 panel (`k`) | `.../workflow/tui/k6_panel_modal.py` + `.../tui/workflow_manage_app.py` |
| 10 | Grafana dashboard ConfigMap | `templates/grafanaDashboard.yaml` |

A run is specified by a `scenario` (script) and a `config` (a `k6-config/*.env` preset, applied via
`envFrom`). Every value is overridable per run (`runner.env` wins over `envFrom`); load is spread
across `--parallelism` runner pods by k6 execution segments.

---

## Install the load-test chart (opt-in)

The chart depends on the `k6-operator` subchart, so vendor it once, then install into the migration
namespace (`ma`). For local minikube the runner image comes from GCR's Docker Hub mirror.

```bash
CHART=deployment/k8s/charts/components/k6LoadTest
helm repo add grafana https://grafana.github.io/helm-charts
helm dependency build "$CHART"          # vendors grafana/k6-operator into charts/

helm upgrade --install k6-load-test "$CHART" -n ma \
  --set image.repository=mirror.gcr.io/grafana/k6 --set image.tag=latest
```

Or let the data-plane script do it for you (it installs this chart alongside the capture proxy,
source/target, Kafka and replayer):

```bash
./buildImages/scripts/deployWorkflowComponents.sh up      # data plane + k6 chart
```

Verify:
```bash
kubectl get crd testruns.k6.io
kubectl -n ma get pods -l app.kubernetes.io/name=k6-operator
kubectl -n ma get cm k6-scenarios k6-testrun-examples     # scenario code + ready-to-run examples
kubectl -n ma get cm -l app=k6-load-test | grep k6-preset  # one envFrom ConfigMap per preset
```

On EKS the operator + runner images are mirrored to ECR via
`deployment/k8s/charts/components/k6LoadTest/infra/mirror/k6-ecr-manifest.yaml`.

---

## Find the Capture Proxy endpoint

```bash
# Data plane from deployWorkflowComponents.sh:
PROXY=https://capture-proxy:9200
# Capture proxy inside a running CDC migration:
PROXY=https://capture-proxy:9201
```
(k6 uses `insecureSkipTLSVerify`, matching the self-signed proxy cert.)

---

## Running a load test

Three ways, all producing the same TestRun. **None requires the migration console** — it's
optional convenience. The chart renders a ready-to-run TestRun per scenario into the
`k6-testrun-examples` ConfigMap, with the scenario mount, runner image, `K6_OUT` metrics, and a
default preset (via `envFrom`) all baked in. Override defaults by swapping the `envFrom` preset or
adding `runner.env` entries — **`env` wins over `envFrom`** natively.

### 1. kubectl (no console, no extra tooling)

```bash
# Defaults straight from the example:
kubectl -n ma get cm k6-testrun-examples -o "jsonpath={.data.ingest}" | kubectl create -f -

# With overrides (jq): different preset, parallelism, and an env override:
kubectl -n ma get cm k6-testrun-examples -o "jsonpath={.data.ingest}" \
  | jq '.spec.parallelism=4
        | .spec.runner.envFrom[0].configMapRef.name="k6-preset-ingest-burst"
        | .spec.runner.env += [{"name":"INGEST_RATE","value":"120"}]' \
  | kubectl -n ma create -f -
```
Use `kubectl create` (not `apply` — the examples use `generateName`).

### 2. `k6-run.sh` (thin helper, still no console)

```bash
./scripts/k6-run.sh ingest --preset ingest-burst --parallelism 4 -e INGEST_RATE=120
```
Fetches the example, applies `--preset` / `--parallelism` / `--target` / `-e KEY=VAL`, creates it,
prints the run name. `CONTEXT` / `NAMESPACE` env-overridable.

### 3. `workflow k6` (console convenience, when it's up)

Nicer flags + `list`/`stop`/`logs` + the TUI. **Hidden/inert unless the TestRun CRD is present.**
```bash
workflow k6 run --scenario ingest --config ingest-burst --parallelism 4 -o INGEST_RATE=120
workflow k6 run --scenario search --config search-deep-paging --rate 100 --duration 10m --wait
workflow k6 list                 # NAME / SCENARIO / STAGE / PARALLEL / AGE
workflow k6 logs <run-name> -f
workflow k6 stop <run-name>   |  --scenario mixed  |  --all
```
`--config` swaps the `envFrom` preset; `--rate`/`--vus` fan out to the ingest+search vars; `-o
KEY=VAL` and `--target` add `runner.env` overrides. TUI: `workflow manage` → **`k`** (launch + list
+ stop). k6 runs are standalone TestRuns, so one never affects a migration workflow.

> **`--parallelism` splits the load.** `--rate`/`--vus` are **global totals** k6 divides across the
> runner pods via execution segments — `--rate 100 --parallelism 4` ≈ 25 req/s per pod.

### Variants (only the preset / env vars change)

| Variant | How |
|---|---|
| steady / ramp / burst | preset `<scenario>-{steady,ramp,burst}` |
| document type | `-e SCENARIO=logs_data` (default `nyc_taxis`) |
| search deep paging | preset `search-deep-paging` (or `-e DEEP_PAGING_ENABLED=true -e PAGING_MODE=search_after`) |
| stateful sequences | `-e SEQUENCE_FRACTION=0.15 -e CONNECTION_MODE=pinned` |
| mixed consistency | `mixed` scenario + `REGISTRY_ENABLED=true` — **needs the chart installed with `registry.enabled=true`** (Redis+Webdis) |
| chaos control | `-e CONTROL_ENABLED=true`, then drive via Webdis — also needs `registry.enabled=true` |
| ignore thresholds | `--extra-args --no-thresholds` |

The `k6-config/*.env` files are the source of truth: Helm renders each into a `k6-preset-<name>`
ConfigMap, consumed via `envFrom`. (Metrics use `K6_OUT=opentelemetry`, not `--out` — see Design
decisions.)

---

## Observe & metrics

```bash
kubectl -n ma get testrun -l app=k6-load-test
kubectl -n ma logs -l k6_cr=<run-name>,runner=true -c k6 --prefix -f
```
Metrics land in the existing Grafana (kube-prometheus-stack); open the **k6-load-test** dashboard.

> On a resource-constrained cluster the default latency thresholds may breach (k6 exits non-zero)
> even though every request succeeds. Pass `--extra-args --no-thresholds` for a clean run.

---

## Tear down

```bash
helm uninstall k6-load-test -n ma        # removes operator + scenarios + RBAC
# or, if you brought it up via the data-plane script:
./buildImages/scripts/deployWorkflowComponents.sh down
```

---

## Scenario reference

| Input | Default | Meaning |
|---|---|---|
| `--scenario` | `ingest` | `ingest` \| `search` \| `mixed` (script at `/scripts/SCENARIO_<scenario>.js`) |
| `--config` | `<scenario>-steady` | any `k6-config/*.env` preset name (without `.env`) |
| `--parallelism` | `1` | runner pods; k6 splits `--rate`/`--vus` across them |
| `--target` | preset's `CAPTURE_PROXY_URL` | Capture Proxy endpoint |
| `--rate` | keep preset | request rate (sets `INGEST_RATE`+`SEARCH_RATE`) |
| `--duration` | keep preset | `DURATION` (e.g. `30s`, `10m`) |
| `--vus` | keep preset | pre-allocated VUs (`INGEST_VUS`+`SEARCH_VUS`) |
| `-o KEY=VALUE` | — | extra env override, applied last (wins over the preset); repeatable |
| `--extra-args` | — | extra flags for `k6 run` (e.g. `--no-thresholds`) |
| `--registry-enabled` | keep preset | mixed consistency ring buffer (needs `registry.enabled=true` on the chart) |
| `--control-enabled` | keep preset | chaos pause/resume/set-rate control bus |

**Document type** (`nyc_taxis` default, or `logs_data`) is a separate axis from `--scenario` (the
script). Switch it via the overrides bag: `-o SCENARIO=logs_data`.

For independent ingest/search rates in `mixed`, use `-o INGEST_RATE=…  -o SEARCH_RATE=…` rather
than the single `--rate` convenience option.

---

## Integration test

`Test0050CdcK6LoadTest` (`migrationConsole/lib/integ_test/.../test_cases/k6_load_test_tests.py`)
layers a short k6 run on a live CDC migration and asserts the traffic is captured and replayed to
the target **under load**. It's explicit-selection only; the test runner installs this chart when
invoked with `--with-load-test`.

---

## Design decisions

Why the current setup looks the way it does (decision → rationale → alternative rejected):

1. **Separate, opt-in chart — not part of the migration.** The load-test chart is *not* a
   dependency of any migration aggregate, so a normal migration deployment contains no operator,
   no scenarios, no RBAC, and the `workflow k6` commands are hidden/inert. This is deliberate
   safety: a user or an agent cannot accidentally fire a load test while running a migration.
   *Defense in depth:* four independent things are missing by default (the `testruns.k6.io` CRD,
   the console RBAC, the scenario/preset ConfigMaps, and the visible CLI) — any one blocks a run.
   *Rejected:* bundling k6 into `migrationAssistantWithArgo`, which would make load testing always
   present and discoverable.

2. **k6-operator `TestRun` CRs — not an Argo WorkflowTemplate.** Chosen for native distributed
   runners (one test split across `--parallelism` pods via k6 execution segments) and a
   CRD-native lifecycle. *Cost:* a second orchestrator (the operator) alongside Argo — but it is
   scoped entirely to this opt-in chart. The earlier Argo `k6LoadTest.ts` template was retired.

3. **Scenarios as a ConfigMap tree — not a baked custom image.** k6 runs on the **stock**
   `grafana/k6` image; the scenarios ship as the `k6-scenarios` ConfigMap. Editing a scenario is a
   `helm upgrade`, not an image rebuild. *Mechanism:* the scenario tree is **flattened** into one
   directory (`files/k6/scripts/`), so ConfigMap keys are plain filenames and the ConfigMap
   **mounts directly at `/scripts`** on both the runner and the initializer — no `items` projection.
   Imports and `open()` are all `./`-relative. Because the flat dir mixes types, each file carries a
   **`TYPE_` prefix** (`SCENARIO_`, `LIB_`, `GENERATOR_` for the doc/query builders, `SCHEMA_` for
   the index-mapping JSON) — this also resolves the former `documents.js`/`queries.js`/`mapping.json`
   name collisions. Total size (~130 KB) is well under the 1 MiB limit.
   *Rejected:* a PVC (needs ReadWriteMany for multi-node parallelism, unavailable on minikube's
   default storage) and a `k6 archive` tarball (reintroduces a build step, non-editable blob).

4. **Presets are `envFrom` ConfigMaps; overrides are `env`; metrics via `K6_OUT`, not `--out`.**
   Each `k6-config/*.env` is rendered into a `k6-preset-<name>` ConfigMap (one key per var) and
   pulled in via `runner.envFrom`. Per-run overrides go in `runner.env`, and **Kubernetes makes
   `env` win over `envFrom`** — so "defaults + overrides" is native, with no preset-parsing in any
   tool. Metrics output is set with the `K6_OUT` env var because the operator also feeds
   `spec.arguments` to the initializer's `k6 archive`, which rejects the run-only `--out` flag.

5. **Helm-rendered example TestRuns are the single definition; runs are kubectl-native; the console
   is optional.** The chart renders one ready-to-run TestRun per scenario into
   `k6-testrun-examples` (the flat `/scripts` mount, image, `K6_OUT`, default `envFrom` preset,
   labels, `generateName`). A run is `kubectl create` from that example (optionally patched), so it
   works with **no console and no console image** — `./k6-run.sh` is a ~20-line `jq` helper over it,
   and `workflow k6` is the same load-and-patch as convenience (nicer flags, `list`/`stop`/`logs`,
   TUI), guarded by the CRD-presence check. One definition (Helm), consumed everywhere; no
   spec-builder to keep in sync. *Rejected:* the console CLI as the *only* submission path (couples
   every run to a current console image). *Note:* `kubectl create` (not `apply`), because the
   examples use `generateName`.

6. **`--parallelism` splits global load.** `--rate` / `--vus` are totals divided across runner pods
   by k6 execution segments — surfaced explicitly so results aren't misread as per-pod.

7. **Stock images are mirrored, not pulled ad hoc.** The runner image comes from a chart value
   (locally `mirror.gcr.io/grafana/k6`, GCR's Docker Hub mirror — same pattern as the data plane's
   OpenSearch/Kafka images); on EKS the operator + runner images are mirrored to ECR via
   `infra/mirror/k6-ecr-manifest.yaml`, kept separate from the migration's mirror manifest so k6
   mirroring is also opt-in.

8. **Validation scripts assume a running data plane and drive through the console.**
   `scripts/run_test_*.sh` submit k6 via `workflow k6 run` and assert against the in-cluster
   services with `kubectl` (Kafka/OpenSearch), console-pod `curl` (proxy, Webdis), and PromQL
   against `kube-prometheus-stack`. Setup/teardown is `deployWorkflowComponents.sh up`/`down`.
