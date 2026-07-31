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
    ├── ConfigMap/k6-scenarios [3]   (scenarios/ + lib/ + data/, "/"→"__" keys)
    ├── ConfigMap/k6-presets   [3]   (k6-config/*.env)
    ├── ConfigMap/k6-image-config    (runner image ref → stock grafana/k6)
    └── Role/RoleBinding [4]         (grants the console SA rights on testruns.k6.io)

USAGE  (from the migration console)
──────────────────────────────────────────────────────────────
  workflow k6 run … [5]           press  k  in  workflow manage [6]
   (CLI, --parallelism N)          (TUI panel: launch · list · stop)
        └───────────────┬───────────────┘
                        ▼  build_testrun_spec + create_testrun [7]
   TestRun (k6.io/v1alpha1)   labels: app=k6-load-test
     spec.parallelism · script.localFile=/scripts/scenarios/<scenario>.js
     runner.env ◄─ preset (k6-presets) + overrides ;  runner.image ◄─ k6-image-config
     runner/initializer volumes ◄─ k6-scenarios (items projection → /scripts)
                        ▼  (operator: initializer → N runner pods)
               k6 runner pods  (stock grafana/k6)
        ┌───────────────┴────────────────┐
   HTTPS│                                │ OTLP :4317  (K6_OUT=opentelemetry)
        ▼                                ▼
  Capture Proxy                    otel-collector ─► Prometheus ─► Grafana [8]
        │                                             (k6-load-test dashboard)
        ▼
  Kafka ─► replayer ─► target      (migration capture-and-replay pipeline)

  observe / manage:   workflow k6 list · logs · stop [5]
```

| # | Piece | Source |
|---|---|---|
| 1 | Standalone chart | `deployment/k8s/charts/components/k6LoadTest/` |
| 2 | k6-operator subchart (TestRun CRD) | `Chart.yaml` dependency → `grafana/k6-operator` |
| 3 | Scenario / preset ConfigMaps | `templates/k6-scenarios-configmap.yaml`, `templates/k6-presets-configmap.yaml` (from `files/k6/`) |
| 4 | Console RBAC on `testruns.k6.io` | `templates/rbac.yaml` |
| 5 | `workflow k6 run/list/logs/stop` | `migrationConsole/lib/console_link/console_link/workflow/commands/k6.py` |
| 6 | TUI k6 panel (`k`) | `.../workflow/tui/k6_panel_modal.py` + `.../tui/workflow_manage_app.py` |
| 7 | TestRun CRUD | `.../workflow/commands/testrun_utils.py` |
| 8 | Grafana dashboard ConfigMap | `templates/grafanaDashboard.yaml` |

A run is specified by a `scenario` (script) and a `config` (a `k6-config/*.env` preset). Every
preset value is overridable per run; load is spread across `--parallelism` runner pods by k6
execution segments.

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
kubectl -n ma get cm k6-scenarios k6-presets k6-image-config
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

## From the migration console (`workflow k6`)

Run inside the migration-console pod (or anywhere with cluster context). The command group is
**hidden/inert unless the TestRun CRD is present** — i.e. unless the chart above is installed.

```bash
# Submit (every preset value is overridable; --override is repeatable)
workflow k6 run --scenario ingest --config ingest-steady --target "$PROXY"
workflow k6 run --scenario ingest --parallelism 4 --target "$PROXY"           # 4 runner pods
workflow k6 run --scenario search --config search-deep-paging --rate 100 --duration 10m
workflow k6 run --scenario mixed --registry-enabled -o INGEST_RATE=80 -o SEARCH_RATE=40 --target "$PROXY"
workflow k6 run --scenario ingest --config ingest-burst --extra-args --no-thresholds --target "$PROXY"
workflow k6 run --scenario ingest --target "$PROXY" --wait        # block until it finishes

# Observe
workflow k6 list                       # NAME / SCENARIO / STAGE / PARALLEL / AGE
workflow k6 list --scenario mixed
workflow k6 logs <run-name> -f         # follow the runner pods' k6 containers

# Stop (deletes the TestRun; the operator tears down its pods)
workflow k6 stop <run-name>
workflow k6 stop --scenario mixed
workflow k6 stop --all
```

> **`--parallelism` splits the load.** `--rate` / `--vus` are **global totals** that k6 divides
> across the runner pods via execution segments — `--rate 100 --parallelism 4` ≈ 25 req/s per pod.

Options: `--scenario`, `--config`, `--parallelism`, `--target`, `--rate`, `--duration`, `--vus`,
`--registry-enabled/--no-…`, `--control-enabled/--no-…`, `--override/-o KEY=VALUE` (repeatable),
`--extra-args`. Omitted options keep the preset's value.

**From the TUI:** in `workflow manage`, press **`k`** to open the k6 panel — it **launches** a new
run (scenario, config, target, rate/duration/vus/parallelism, registry/control toggles, overrides
box) and **lists running** runs with per-run **Stop** (plus **Stop all**). k6 runs are standalone
TestRuns, so launching or stopping one never affects the migration workflow you're managing.

### Raw kubectl (no console)

`workflow k6 run` builds a TestRun; you can also hand-apply one. The scenario tree is mounted from
the `k6-scenarios` ConfigMap via an `items` projection (keys are file paths with `/`→`__`) at
`/scripts` on **both** the `runner` and the `initializer`, with `script.localFile:
/scripts/scenarios/<scenario>.js`. Metrics output goes through `K6_OUT=opentelemetry` (runner env)
— **not** `--out`, which the operator would also feed to the initializer's `k6 archive` and be
rejected as an unknown flag.

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
| `--scenario` | `ingest` | `ingest` \| `search` \| `mixed` (script at `/scripts/scenarios/<scenario>.js`) |
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
   `helm upgrade`, not an image rebuild. *Mechanism:* ConfigMap keys can't contain `/`, so each
   file's path is stored with `/`→`__` and the console rebuilds an `items[]` volume projection at
   `/scripts` on **both** the runner and the initializer (so imports and `open()` resolve). Total
   size (~130 KB) is well under the 1 MiB ConfigMap limit. *Rejected:* a PVC (needs ReadWriteMany
   for multi-node parallelism, unavailable on minikube's default storage) and a `k6 archive`
   tarball (reintroduces a build step and yields a non-editable binary blob).

4. **Console-side env resolution; metrics via `K6_OUT`, not `--out`.** The operator owns the runner
   command, so the console resolves preset (`k6-presets` ConfigMap) + overrides into `runner.env`
   rather than an in-container shell wrapper. Metrics output is set with the `K6_OUT` env var
   because the operator also feeds `spec.arguments` to the initializer's `k6 archive`, which
   rejects the run-only `--out` flag as unknown.

5. **The `workflow k6` CLI is the single submission path — scripts included, not raw kubectl.**
   The CLI *is* the TestRun spec-builder (preset/override precedence, the `items` projection, the
   runner image, the `K6_OUT` handling, and the isolation labels), and the interactive CLI, the
   TUI panel, and the validation scripts all share it — one tested path, one place to fix bugs.
   *Cost:* runs are submitted by `kubectl exec`-ing the console pod, so they need a console image
   built from this branch. *Rejected:* raw `kubectl apply`, which would either duplicate the
   spec-builder in bash or require static per-scenario manifests that lose the preset/override
   ergonomics and drift from the scenario files. *Escape hatch:* a future `workflow k6 run
   --dry-run` that emits the built TestRun YAML would let scripts `kubectl apply` it without the
   console-pod coupling.

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
