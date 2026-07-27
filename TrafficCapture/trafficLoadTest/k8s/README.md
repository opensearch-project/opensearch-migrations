# Running the k6 load test in Kubernetes

This runs the scenarios in [`../`](../) as short-lived **Argo workflows** inside the
migration deployment. Drive them from the migration console (`workflow k6 …`, the friendly
front-end) or with raw `argo submit` — both target the same `k6-load-test` WorkflowTemplate.

> **Assumption:** k6 does **not** stand up Kafka / a source cluster / the Capture Proxy. It relies
> on the infra already deployed by a migration and only needs a reachable **Capture Proxy URL**
> plus the in-cluster **otel-collector** (both already present in the `ma` namespace).

---

## What gets deployed

Enabling `k6.enabled` in the `migration-assistant` chart adds, gated and namespaced:

| Resource | Purpose |
|---|---|
| `WorkflowTemplate/k6-load-test` | the reusable run definition (`argo submit --from` this) |
| `ConfigMap/k6-load-test-dashboard` | Grafana dashboard (auto-imported by the kube-prometheus-stack sidecar) |
| `Deployment/Service redis` + `webdis` | only when `k6.registry.enabled=true` — needed by the `mixed` & chaos scenarios |

The `migrations/k6` image bakes the scenarios and the `k6-config/*.env` presets into `/scripts`.
A run is specified by two names: `scenario` (script) and `configName` (preset).

---

## One-time setup

### Local minikube
```bash
# Build all images (incl. migrations/k6) into the local registry:
cd buildImages
./scripts/fillLocalRegistry.sh
# Deploy / upgrade the chart (k6.enabled=true is set in valuesForLocalK8sWithEnvSubst.yaml):
./scripts/startMinikubeAndDeployCharts.sh
```

### EKS / cloud
`k6.enabled=true` is set in `valuesEks.yaml`; the `migrations/k6` image ships through the normal
ECR-mirror image path. No extra step beyond the standard deploy.

### Verify the template is installed
```bash
kubectl get workflowtemplate -n ma k6-load-test
```

---

## Find the Capture Proxy endpoint (required per run)

The proxy Service name is chosen by the migration config, so look it up:
```bash
kubectl get svc -n ma -l migrations/proxy
PROXY=https://<proxy-svc>.ma.svc.cluster.local:9200
```
(k6 uses `insecureSkipTLSVerify`, matching the self-signed proxy cert.)

---

## From the migration console (`workflow k6`)

The console wraps submit/list/logs/stop over the same WorkflowTemplate — no `argo` CLI needed.
Run these inside the migration-console pod (or anywhere with the cluster context).

```bash
# Submit (every preset value is overridable; --override is repeatable)
workflow k6 run --scenario ingest --config ingest-steady --target "$PROXY"
workflow k6 run --scenario search --config search-deep-paging --rate 100 --duration 10m
workflow k6 run --scenario mixed --registry-enabled -o INGEST_RATE=80 -o SEARCH_RATE=40 --target "$PROXY"
workflow k6 run --scenario ingest --config ingest-burst --extra-args --no-thresholds --target "$PROXY"
workflow k6 run --scenario ingest --target "$PROXY" --wait        # block until it finishes

# Observe
workflow k6 list                       # NAME / SCENARIO / PHASE / PROGRESS / AGE
workflow k6 list --scenario mixed
workflow k6 logs <run-name> -f         # follow the k6 container

# Kill
workflow k6 stop <run-name>
workflow k6 stop --scenario mixed --delete
workflow k6 stop --all
```

Options mirror the WorkflowTemplate parameter contract (see **Parameters** below): `--scenario`,
`--config`, `--target`, `--rate`, `--duration`, `--vus`, `--registry-enabled/--no-…`,
`--control-enabled/--no-…`, `--override/-o KEY=VALUE` (repeatable), `--extra-args`. Omitted
options keep the preset's value.

**From the TUI:** in `workflow manage`, press **`k`** to open the k6 panel — it both **launches**
a new run (scenario, config, target, rate/duration/vus, registry/control toggles, overrides box)
and **lists the running** runs with per-run **Stop** (plus **Stop all** and a "delete after stop"
toggle). It uses the identical submit/stop paths as `workflow k6`. k6 runs are standalone Argo
Workflows, so launching or stopping one never affects the migration workflow you're managing.

---

## Run scenarios (raw `argo submit`)

### Ingest (steady preset)
```bash
argo submit -n ma --from workflowtemplate/k6-load-test \
  -p scenario=ingest -p configName=ingest-steady -p targetUrl="$PROXY" \
  -l app=k6-load-test -l k6-scenario=ingest
```

### Search, concurrently, with per-run overrides
Named params (`rate`, `duration`, `vus`) and a generic `overrides` bag both beat the preset:
```bash
argo submit -n ma --from workflowtemplate/k6-load-test \
  -p scenario=search -p configName=search-steady -p targetUrl="$PROXY" \
  -p rate=100 -p duration=10m \
  -p overrides=$'DEEP_PAGING_ENABLED=true\nPAGING_MODE=search_after' \
  -l app=k6-load-test -l k6-scenario=search
```
`overrides` is one `KEY=VALUE` per line; JSON values work too, e.g.
`-p overrides='RAMP_STAGES=[{"duration":"2m","target":150}]'`.

### Ramp / burst
```bash
argo submit -n ma --from workflowtemplate/k6-load-test \
  -p scenario=ingest -p configName=ingest-burst -p targetUrl="$PROXY" \
  -p extraArgs=--no-thresholds \
  -l app=k6-load-test -l k6-scenario=ingest
```
> Burst is designed to saturate the proxy; a k6 threshold breach exits non-zero (the *finding*, not
> a broken test). `extraArgs=--no-thresholds` keeps the workflow from being marked failed.

### Mixed / chaos (need Redis+Webdis → `k6.registry.enabled=true`, then redeploy)
```bash
argo submit -n ma --from workflowtemplate/k6-load-test \
  -p scenario=mixed -p configName=mixed-steady -p targetUrl="$PROXY" \
  -p registryEnabled=true \
  -l app=k6-load-test -l k6-scenario=mixed
```

---

## Observe running runs

```bash
argo list -n ma -l app=k6-load-test                         # CLI
argo logs -n ma @latest                                     # live logs of the newest run
kubectl -n ma port-forward service/argo-server 8001:2746    # Argo UI at https://localhost:8001
```
Metrics land in the existing Grafana (kube-prometheus-stack); open the **k6-load-test** dashboard.

---

## Kill runs

```bash
argo terminate -n ma <run-name>              # stop one
argo delete    -n ma -l k6-scenario=mixed    # a selection (by label)
argo delete    -n ma -l app=k6-load-test     # all k6 runs
```

---

## Parameters

| Param | Default | Meaning |
|---|---|---|
| `scenario` | `ingest` | `ingest` \| `search` \| `mixed` (script at `/scripts/scenarios/<scenario>.js`) |
| `configName` | `ingest-steady` | any `k6-config/*.env` preset name (without `.env`) |
| `targetUrl` | *(empty — required)* | Capture Proxy endpoint |
| `rate` | *(empty)* | override request rate (sets `INGEST_RATE`+`SEARCH_RATE`); empty = keep preset |
| `duration` | *(empty)* | override `DURATION`; empty = keep preset |
| `vus` | *(empty)* | override pre-allocated VUs (`INGEST_VUS`+`SEARCH_VUS`) |
| `overrides` | *(empty)* | newline-separated `KEY=VALUE`, applied last (wins over the preset) |
| `extraArgs` | *(empty)* | extra flags for `k6 run` (e.g. `--no-thresholds`) |
| `registryEnabled` | *(empty)* | empty = keep preset; `true` → mixed consistency ring buffer (needs `k6.registry.enabled`) |
| `controlEnabled` | *(empty)* | empty = keep preset; `true` → chaos pause/resume/set-rate control bus |
| `webdisUrl` | *(empty)* | empty = keep preset (mixed presets set `http://webdis:7379`) |

Every non-`scenario`/`configName` param is empty-by-default, meaning **"keep the preset's value"**;
set it to override. (`targetUrl` still wins over the preset's `CAPTURE_PROXY_URL` when provided.)

For independent ingest/search rates in `mixed`, use the `overrides` bag (`INGEST_RATE=…`,
`SEARCH_RATE=…`) rather than the single `rate` convenience param.

**Document type** (`nyc_taxis` default, or `logs_data`) is a separate axis from `scenario` (the
script). Switch it via the overrides bag: `-p overrides=$'SCENARIO=logs_data'`.
