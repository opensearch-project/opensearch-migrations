---
name: aoss-nextgen
description: Use when the user asks to create, test, or operate on Amazon OpenSearch Serverless (AOSS) NextGen collections, collection groups, vector/search indexes, or troubleshoot ingest/search against AOSS. Covers what makes a collection actually NextGen vs Classic, the gotchas in CLI/SDK versions, and the index/query shape the NextGen vector engine accepts.
version: 0.1.0
---

# OpenSearch Serverless NextGen — operator skill

## What "NextGen" actually means

NextGen and Classic are **two different generations** of AOSS that share the same data-plane API surface but differ in control-plane defaults and infrastructure. Surface signals that distinguish them:

| Signal | NextGen | Classic |
|---|---|---|
| Collection endpoint hostname | `<id>.aoss.<region>.on.aws` | `<id>.<region>.aoss.amazonaws.com` |
| Collection group `generation` field | `NEXTGEN` | (absent / `CLASSIC`) |
| Collection creation time | ~5 seconds | ~3 minutes |
| `standbyReplicas` on collection group | must be `ENABLED` | can be `DISABLED` |
| Collection group `capacityLimits.minIndexingCapacityInOCU` / `minSearchCapacityInOCU` | `0.0` (scale-to-zero) | `2.0` minimum |
| Vector index mapping | minimal: `{"type":"knn_vector","dimension":N}` — engine inferred | requires explicit `method.engine` (faiss/nmslib) |

**Always verify after creation** — both via console "Serverless generation" column and the `generation` field in `BatchGetCollectionGroup`. Older SDK/CLI versions silently drop unknown params and create Classic.

## Pre-flight: SDK/CLI version is the #1 gotcha

The `--generation NEXTGEN` flag on `create-collection-group` was added in **AWS CLI ≥ 2.34.56**. Older versions silently ignore it and the API defaults to Classic. boto3/botocore's bundled service model has the same constraint.

Before doing any NextGen create:

```bash
aws --version    # need ≥ 2.34.56
```

If older, upgrade:
```bash
brew upgrade awscli
# OR official pkg if brew bottle is stale:
curl "https://awscli.amazonaws.com/AWSCLIV2.pkg" -o /tmp/AWSCLIV2.pkg
sudo installer -pkg /tmp/AWSCLIV2.pkg -target /
hash -r       # if shell aliased aws to a path that got moved
```

If using a remote MCP/proxy AWS tool, **its CLI may be older than your local one**. When in doubt, fall back to local shell `aws` invocations.

## End-to-end create flow (CLI)

Region: pick anywhere AOSS NextGen is available. Names: lowercase, `[a-z][a-z0-9-]+`, 3–32 chars.

```bash
REGION=us-west-2
ACCT=$(aws sts get-caller-identity --query Account --output text)
ROLE_ARN=$(aws sts get-caller-identity --query Arn --output text \
           | sed 's|/[^/]*$||' | sed 's|sts:|iam:|;s|assumed-role|role|')
# (or just hardcode the role ARN you want to grant data access to)

# 1) Collection group(s). NextGen REQUIRES standby-replicas ENABLED.
aws opensearchserverless create-collection-group --region $REGION \
  --name cg-mything-1 --standby-replicas ENABLED --generation NEXTGEN

# 2) Encryption policy (covers all collections you plan to put in this group)
aws opensearchserverless create-security-policy --region $REGION \
  --name mything-enc --type encryption \
  --policy '{"Rules":[{"ResourceType":"collection","Resource":["collection/mything-1"]}],"AWSOwnedKey":true}'

# 3) Network policy (public for testing; use VPC endpoints for prod)
aws opensearchserverless create-security-policy --region $REGION \
  --name mything-net --type network \
  --policy '[{"Rules":[{"ResourceType":"dashboard","Resource":["collection/mything-1"]},{"ResourceType":"collection","Resource":["collection/mything-1"]}],"AllowFromPublic":true}]'

# 4) Data access policy — minimum useful set of perms for an IAM principal
aws opensearchserverless create-access-policy --region $REGION \
  --name mything-data --type data \
  --policy '[{"Rules":[
    {"ResourceType":"collection","Resource":["collection/mything-1"],
     "Permission":["aoss:CreateCollectionItems","aoss:DeleteCollectionItems","aoss:UpdateCollectionItems","aoss:DescribeCollectionItems"]},
    {"ResourceType":"index","Resource":["index/mything-1/*"],
     "Permission":["aoss:CreateIndex","aoss:DeleteIndex","aoss:UpdateIndex","aoss:DescribeIndex","aoss:ReadDocument","aoss:WriteDocument"]}
  ],"Principal":["'"$ROLE_ARN"'"]}]'

# 5) Collection — also needs --standby-replicas ENABLED for NextGen
aws opensearchserverless create-collection --region $REGION \
  --name mything-1 --type SEARCH \
  --collection-group-name cg-mything-1 --standby-replicas ENABLED
# Use --type VECTORSEARCH for vector workloads.
```

**Policy ordering matters**: encryption + network policies must exist *before* the collection is created (they're matched by resource pattern). Data access policy can be created before or after. Create policies first, then the collection — AOSS won't backfill associations cleanly otherwise.

**Wait for ACTIVE**:
```bash
aws opensearchserverless batch-get-collection --region $REGION --names mything-1 \
  --query 'collectionDetails[0].{status:status,endpoint:collectionEndpoint}'
```
NextGen typically goes ACTIVE in ~5s.

## Data plane (ingest + search)

AOSS uses SigV4 with service name `aoss`. From Python:

```python
import boto3, requests, json
from requests_aws4auth import AWS4Auth
c = boto3.Session().get_credentials().get_frozen_credentials()
auth = AWS4Auth(c.access_key, c.secret_key, "us-west-2", "aoss",
                session_token=c.token)
H = {"Content-Type": "application/json"}
EP = "https://<id>.aoss.us-west-2.on.aws"   # from collectionEndpoint

# Create index
requests.put(f"{EP}/books", auth=auth, headers=H, data=json.dumps({
    "mappings": {"properties": {
        "title": {"type": "text"},
        "author": {"type": "keyword"},
        "year": {"type": "integer"},
    }}}))

# Bulk index (note: NDJSON, blank line at end)
bulk = "\n".join([
    json.dumps({"index": {}}), json.dumps({"title": "Dune", "author": "Frank Herbert", "year": 1965}),
    json.dumps({"index": {}}), json.dumps({"title": "Foundation", "author": "Isaac Asimov", "year": 1951}),
]) + "\n"
requests.post(f"{EP}/books/_bulk", auth=auth,
              headers={"Content-Type": "application/x-ndjson"}, data=bulk)

# Search
import time; time.sleep(2)   # AOSS refresh interval — first search after bulk often needs it
requests.post(f"{EP}/books/_search", auth=auth, headers=H, data=json.dumps({
    "query": {"match": {"title": "dune"}}}))
```

Constraints worth knowing:
- AOSS does **not** support `_doc/<id>` PUT with arbitrary IDs — let it auto-assign, or use `_create`.
- AOSS does **not** support refresh-on-write — there's a brief delay (sub-second to a few seconds) before docs are searchable.
- `aoss:CreateCollectionItems` is what authorizes `PUT /<index>` (creating indexes), not `aoss:CreateIndex` alone — grant both.

## Vector search (NextGen specifics)

The NextGen vector engine ignores/rejects the legacy `method.engine`/`method.name` fields. Use a **minimal mapping**:

```python
# CORRECT for NextGen VECTORSEARCH:
{"settings": {"index": {"knn": True}},
 "mappings": {"properties": {
     "name": {"type": "text"},
     "embedding": {"type": "knn_vector", "dimension": 384}
 }}}

# WRONG for NextGen (works on Classic, returns 400 on NextGen):
# "embedding": {"type": "knn_vector", "dimension": 384,
#   "method": {"name": "hnsw", "engine": "faiss", "space_type": "l2"}}
```

After creation, GET `/<index>/_mapping` shows NextGen has auto-applied `mode: on_disk` and `compression_level: 32x` — this is normal.

Query shape (unchanged from Classic):
```json
{"size": 5,
 "query": {"knn": {"embedding": {"vector": [...], "k": 5}}}}
```

`_source` may **not** include the embedding field unless you explicitly request it via `_source: ["field1", "embedding"]`.

## Timing + avoiding 401s on a fresh collection (measured)

End-to-end "create + ingest 1 doc + search returns it" on NextGen with `min=2 / max=8` (warm), parallelizing group + 3 policies and immediately polling for ACTIVE:

| Step | Step ms | Notes |
|---|---|---|
| Group + 3 policies (parallel) | ~1,300 | All 4 control-plane creates in parallel |
| `create-collection` API returns | ~1,900 | |
| Collection CREATING → ACTIVE | ~5,800 | This is the NextGen "5 second" claim |
| Data-plane warmup probe (`GET /_cat/indices`) | ~400 | First non-401 means access policy has propagated |
| `PUT /<index>` (create index) | ~900 | |
| `POST /<index>/_doc` (1 doc) | **~17,000** | Cold first write — unavoidable internal warmup |
| First search returning hits | ~12,000 | Refresh window after first ingest |
| **Total wall clock** | **~38 seconds** | |

### How to avoid the 401s

The 401s seen earlier (`POST /_doc` returning 401 on a freshly-created collection) are **data access policy propagation lag**, not a permissions bug. They go away within seconds. Two strategies:

1. **Warmup probe before the real write**: hit `GET /_cat/indices` (read-only, only needs `aoss:DescribeIndex`) in a tight loop, accept any non-401 response, *then* do the real PUT/POST. Costs ~400ms typically and ~zero in the steady state.
2. **Create the data access policy *before* the collection** — the API does match-by-resource-pattern, so the policy can pre-exist. This shrinks the propagation window. Combine with the warmup probe for belt-and-suspenders.

The remaining 17s on the first doc-write is internal NextGen cold-start that you cannot bypass from outside. `min*InOCU > 0` doesn't help — those reserved OCUs are warm but the collection-level data-plane wiring still needs time on the very first request.

### What does NOT help

- Sleeping a fixed amount before writes (wastes time when warmup is fast, fails when it's slow).
- Setting `min*InOCU > 0` to skip the first-write cold-start — measured no difference vs `min=0`.
- Hitting the FIPS endpoint (same backend).
- Cutting from 3 policies to 2 by using inline `--encryption-config aWSOwnedKey=true`. Confirmed working but saves only ~100ms on a ~38s run.
- Probing the endpoint before `batch-get-collection` returns ACTIVE. The endpoint URL is predictable from the collection ID (`https://<id>.aoss.<region>.on.aws`) and *does* start accepting requests ~3s before the control plane reports ACTIVE — but the savings get eaten by the first-write cold-start variance.

### Optimizations that work but yield small wins

| Change | Saves |
|---|---|
| Run group + policies in parallel | ~0.5–1s |
| Inline `--encryption-config aWSOwnedKey=true` (no encryption policy) | ~0.1–0.2s |
| Predict endpoint, hammer PUT until 200 instead of polling control plane | ~3s |
| Tighter ACTIVE poll interval (250ms vs 1s) | ~0.5s |

**The dominant cost is the first `POST /_doc` after collection creation** — observed 17–22s across runs, with no client-side mitigation possible. After this first write, subsequent writes are sub-second.

### Warmup probe gotcha

`GET /_cat/indices` may return 200 before write nodes are accepting traffic — it goes through a read tier with earlier policy propagation. Use the actual write you intend to do (e.g. `PUT /<index>`) as the probe, retrying on 401/403/timeout until 200, instead of trusting a separate readiness endpoint.

### Performance characteristics worth knowing

- **Subsequent writes** (after the first) settle to ~50-200ms.
- **Subsequent searches** (after refresh) settle to ~100-500ms.
- Refresh interval after a write before the doc is searchable: typically 1-3 seconds. Poll, don't fixed-sleep.

## Cost / safety

NextGen with `min*=0` actually scales to zero (no charge when idle) — major win over Classic which kept 2 OCUs warm at all times. But:
- First request after idle pays a cold-start tax (single-digit seconds).
- `maxIndexingCapacityInOCU` defaults to 96 — set lower (e.g. 4–8) for test workloads if you're worried about runaway scale.
- **NextGen requires `standby-replicas=ENABLED`** at both group and collection level. Classic let you save half by disabling.

## Cleanup ordering

Delete in reverse-dependency order, or delete-collection succeeds but group delete blocks:

```bash
# 1) collections (need IDs, not names)
aws opensearchserverless delete-collection --region $REGION --id <collection-id>
# poll until BatchGetCollection returns NOT_FOUND for the name

# 2) collection groups
aws opensearchserverless delete-collection-group --region $REGION --id <group-id>

# 3) policies
aws opensearchserverless delete-security-policy --region $REGION --name <n> --type encryption
aws opensearchserverless delete-security-policy --region $REGION --name <n> --type network
aws opensearchserverless delete-access-policy --region $REGION --name <n> --type data
```

## Verification checklist (use after every NextGen create)

1. `aws --version` ≥ 2.34.56 ✓
2. `BatchGetCollectionGroup` → `generation: NEXTGEN` ✓
3. Collection `collectionEndpoint` host matches `*.aoss.<region>.on.aws` ✓
4. Time from `CREATING` → `ACTIVE` is seconds, not minutes ✓
5. Console "Serverless generation" column shows "NextGen" ✓

If any of these fail, you got a Classic collection — destroy and recreate, don't paper over it.

## Common failure → fix

| Symptom | Cause | Fix |
|---|---|---|
| `unknown options: --generation` | CLI < 2.34.56 | Upgrade CLI (see top) |
| `StandbyReplicas cannot be set to DISABLED for NEXTGEN` | NextGen requires standbys | Pass `--standby-replicas ENABLED` |
| Index mapping returns `400 Field parameter 'engine' is not supported` | NextGen vector mapping | Drop `method` block from mapping |
| `403 not authorized for index/<col>/<idx>` on PUT | Missing `aoss:CreateCollectionItems` on collection resource | Add it to the data access policy collection rule |
| 5–10s timeout on first search after idle | Scale-to-zero cold start | Retry; or set `minIndexingCapacityInOCU > 0` on the group |
| 401 on first PUT/POST after collection ACTIVE | Data access policy propagation lag (~seconds) | Warmup probe `GET /_cat/indices` until non-401 before real writes |
| First doc-write takes ~15-20s even on warm group | Internal data-plane cold start on first write | Unavoidable; expect it. Subsequent writes are sub-second. |
| `aws opensearchserverless` rejects `--generation` / `--collection-group-name` from a fresh shell while it works in your prompt | Shell PATH resolves to an older `aws` (e.g. mise/pyenv-installed v1.x); your interactive prompt aliases to a newer one | Hardcode `AWS=/opt/homebrew/bin/aws` (or `which -a aws | head` to find the right one) at the top of any script |
| `User: ... is not authorized to perform: aoss:APIAccessAll` (or generic 403) on data plane | Caller's IAM principal doesn't match `Principal` in data access policy | Update access policy to include the actual assumed-role ARN (`sts get-caller-identity` shows the `assumed-role/<role-name>/<session>` form — the policy needs the *role* ARN, not the assumed-role ARN) |
