# Solr → OpenSearch Shim & Transformations — 30-Minute Demo

A hands-on walkthrough that takes you from zero to contributing. By the end you'll understand the architecture, run the full stack locally, see the shim proxy in action across all modes, read the transform code, and know how to run tests.

## Prerequisites

- Docker & Docker Compose
- Java 17+ (Corretto recommended)
- Node.js 20+
- `curl` and `python3` (for JSON formatting)

## Table of Contents

| Section | Time | What You'll Do |
|---------|------|----------------|
| [1. What Are Migrations?](#1-what-are-migrations) | 3 min | Understand the big picture |
| [2. Build & Start the Stack](#2-build--start-the-stack) | 5 min | Build Docker images, start Solr + OpenSearch + shim |
| [3. Seed Data](#3-seed-data) | 3 min | Index documents into both backends |
| [4. Query Both Backends Directly](#4-query-both-backends-directly) | 3 min | See the API differences between Solr and OpenSearch |
| [5. The Shim in Action](#5-the-shim-in-action) | 5 min | Send Solr requests through 4 shim modes |
| [6. Code Walkthrough](#6-code-walkthrough) | 5 min | Read the transforms, features, and pipeline |
| [7. Running Tests](#7-running-tests) | 3 min | Gradle test commands for shim and transforms |
| [8. Inline Real-Time Validation](#8-inline-real-time-validation) | 3 min | Understand dual-target validation and hot-reload |

---

## 1. What Are Migrations?

The [opensearch-migrations](https://github.com/opensearch-project/opensearch-migrations) project helps teams migrate from Elasticsearch or Solr to OpenSearch. It provides tools for:

- **Snapshot-based migration** — bulk-migrate indices and documents from a snapshot
- **Traffic capture & replay** — record live traffic, replay it against OpenSearch to validate behavior
- **Metadata migration** — migrate index settings, mappings, templates, and aliases

### Where the Shim Fits In

The **Transformation Shim** is a Netty-based HTTP proxy that sits between your Solr clients and OpenSearch. It solves the "live traffic" problem for Solr migrations:

```
                                    ┌──────────────┐
                                    │   Solr 8     │
                                    │  (:8983)     │
                                    └──────┬───────┘
                                           │
┌──────────┐     ┌─────────────────┐       │
│  Solr    │────▶│  Shim Proxy     │───────┤
│  Client  │     │  (:8080)        │       │
└──────────┘     └─────────────────┘       │
                   │ request transform     │
                   │ response transform    │
                   ▼                       │
                 ┌──────────────┐          │
                 │ OpenSearch   │──────────┘
                 │  (:9200)     │  (optional: validate
                 └──────────────┘   responses match)
```

Your application keeps speaking Solr HTTP. The shim translates requests to OpenSearch's `_search` API and converts responses back to Solr format. In dual-target mode, it sends to both backends in parallel and validates that responses match — giving you confidence before cutting over.

### Migration Phases

| Phase | Shim Config | What Happens |
|-------|-------------|--------------|
| **Shadow validation** | `--primary solr` + both targets | Solr serves production. OpenSearch runs in shadow. Validation headers report match/mismatch. |
| **Cutover** | `--primary opensearch` + both targets | OpenSearch serves production. Solr is the safety net. One flag to roll back. |
| **Direct** | `--primary opensearch` only | Solr decommissioned. Shim is a transform-only proxy (or removed entirely). |

---

## 2. Build & Start the Stack

All commands run from the repo root.

### Build the shim Docker image

```bash
cd TrafficCapture
../gradlew :TrafficCapture:transformationShim:jibDockerBuild
```

This builds the `migrations/transformation_shim` Docker image using [Jib](https://github.com/GoogleContainerTools/jib) (no Dockerfile needed).

### Build the TypeScript transforms

```bash
cd SolrTransformations/transforms
npm install
npm run build
```

Output goes to `transforms/dist/`:
- `solr-to-opensearch-request.js` — request transform (GraalVM closure)
- `solr-to-opensearch-response.js` — response transform (GraalVM closure)
- `solr-to-opensearch-cases.testcases.json` — test case data

### Start the validation stack

```bash
cd SolrTransformations
docker compose -f docker/docker-compose.validation.yml up -d
```

This starts 7 services:

| Service | Port | Description |
|---------|------|-------------|
| `solr` | 8983 | Solr 8 backend |
| `opensearch` | 9200 | OpenSearch 3.3 backend |
| `transform-watcher` | — | Watches TypeScript, rebuilds JS on change |
| `shim-solr-only` | 8081 | Passthrough proxy to Solr |
| `shim-opensearch-only` | 8082 | Transform proxy to OpenSearch |
| `shim-solr-primary` | 8083 | Dual-target, returns Solr, validates against OpenSearch |
| `shim-opensearch-primary` | 8084 | Dual-target, returns OpenSearch, validates against Solr |

Wait for backends to be healthy:

```bash
# Wait for OpenSearch
until curl -sf http://localhost:9200 >/dev/null 2>&1; do sleep 2; done
echo "OpenSearch ready"

# Wait for Solr
until curl -sf http://localhost:8983/solr/admin/info/system >/dev/null 2>&1; do sleep 2; done
echo "Solr ready"

# Give shims a moment to connect
sleep 5
```

> **Shortcut:** Run `./demo-validation.sh --no-teardown` to build, start, seed, and demo everything automatically. The `--no-teardown` flag leaves services running so you can explore.

---

## 3. Seed Data

Create a Solr collection and index identical documents into both backends.

### Create Solr core

Solr 8 in standalone mode uses cores (not collections). Create one via the Solr CLI inside the container:

```bash
docker exec $(docker compose -f docker/docker-compose.validation.yml ps -q solr) \
  solr create_core -c demo
```

### Index documents into Solr

```bash
curl -s -X POST "http://localhost:8983/solr/demo/update/json/docs?commit=true" \
  -H 'Content-Type: application/json' \
  -d '[
    {"id": "1", "title": "Introduction to OpenSearch", "category": "search", "author": "Alice"},
    {"id": "2", "title": "Migrating from Solr",        "category": "migration", "author": "Bob"},
    {"id": "3", "title": "Query DSL Deep Dive",         "category": "search", "author": "Charlie"}
  ]'
```

### Index same documents into OpenSearch

```bash
curl -s -X PUT "http://localhost:9200/demo/_doc/1" -H 'Content-Type: application/json' \
  -d '{"id":"1","title":"Introduction to OpenSearch","category":"search","author":"Alice"}'

curl -s -X PUT "http://localhost:9200/demo/_doc/2" -H 'Content-Type: application/json' \
  -d '{"id":"2","title":"Migrating from Solr","category":"migration","author":"Bob"}'

curl -s -X PUT "http://localhost:9200/demo/_doc/3" -H 'Content-Type: application/json' \
  -d '{"id":"3","title":"Query DSL Deep Dive","category":"search","author":"Charlie"}'

# Refresh so docs are searchable
curl -s -X POST "http://localhost:9200/demo/_refresh"
```

---

## 4. Query Both Backends Directly

### Query Solr

```bash
curl -s "http://localhost:8983/solr/demo/select?q=*:*&wt=json" | python3 -m json.tool
```

Solr response format:
```json
{
    "responseHeader": { "status": 0, "QTime": 1, "params": { "q": "*:*", "wt": "json" } },
    "response": {
        "numFound": 3,
        "start": 0,
        "docs": [
            { "id": "1", "title": ["Introduction to OpenSearch"], "category": ["search"], "author": ["Alice"] },
            ...
        ]
    }
}
```

### Query OpenSearch

```bash
curl -s "http://localhost:9200/demo/_search" | python3 -m json.tool
```

OpenSearch response format:
```json
{
    "hits": {
        "total": { "value": 3 },
        "hits": [
            { "_id": "1", "_source": { "id": "1", "title": "Introduction to OpenSearch", ... } },
            ...
        ]
    }
}
```

**Key difference:** Solr uses `response.docs[]` with multi-valued string arrays. OpenSearch uses `hits.hits[]._source` with scalar values. The shim transforms bridge this gap.

---

## 5. The Shim in Action

All 4 shim modes accept the same Solr URL. The difference is which backend serves the response and whether validation runs.

### Mode 1: Solr-Only Passthrough (`:8081`)

Proxies directly to Solr. No transforms, no validation. Useful as a baseline.

```bash
curl -sD- "http://localhost:8081/solr/demo/select?q=*:*&wt=json" | head -20
```

Look for these headers:
```
X-Shim-Primary: solr
X-Shim-Targets: solr
X-Target-solr-StatusCode: 200
```

### Mode 2: OpenSearch-Only with Transforms (`:8082`)

Transforms the Solr request → OpenSearch `_search`, transforms the response back to Solr format.

```bash
curl -sD- "http://localhost:8082/solr/demo/select?q=*:*&wt=json" | head -20
```

Headers:
```
X-Shim-Primary: opensearch
X-Shim-Targets: opensearch
X-Target-opensearch-StatusCode: 200
```

The body looks like a Solr response, but the data came from OpenSearch.

### Mode 3: Dual-Target, Solr Primary (`:8083`)

Sends to **both** backends in parallel. Returns the Solr response. Runs validators and reports results in headers.

```bash
curl -sD- "http://localhost:8083/solr/demo/select?q=*:*&wt=json" | head -30
```

Headers include per-target status and validation results:
```
X-Shim-Primary: solr
X-Shim-Targets: solr,opensearch
X-Target-solr-StatusCode: 200
X-Target-solr-Latency: 12
X-Target-opensearch-StatusCode: 200
X-Target-opensearch-Latency: 45
X-Validation-Status: FAIL
X-Validation-Details: field-equality(solr,opensearch):FAIL[responseHeader.params: missing in opensearch; response.docs[0]._version_: missing in opensearch; ...], doc-count(solr,opensearch):PASS
```

The `field-equality` validator reports FAIL because Solr includes `responseHeader.params` and `_version_` fields that the OpenSearch response transform doesn't produce. The `doc-count` validator passes — both return 3 docs. To make `field-equality` pass, add those paths to the `ignore` list in the docker-compose validator config.

### Mode 4: Dual-Target, OpenSearch Primary (`:8084`)

Same dual-target setup, but returns the OpenSearch (transformed) response. Solr runs as the safety net.

```bash
curl -sD- "http://localhost:8084/solr/demo/select?q=*:*&wt=json" | head -30
```

This is the **cutover** mode — flip `--primary` from `solr` to `opensearch` and you're serving from OpenSearch.

### Quick Comparison

```bash
# Side by side — same Solr URL, different backends
echo "=== Solr Direct ==="
curl -s "http://localhost:8983/solr/demo/select?q=*:*&wt=json&rows=1" | python3 -m json.tool

echo "=== Through Shim (OpenSearch) ==="
curl -s "http://localhost:8082/solr/demo/select?q=*:*&wt=json" | python3 -m json.tool
```

---

## 6. Code Walkthrough

### Project Structure

```
TrafficCapture/
├── transformationShim/          # Java — Netty proxy, validators, CLI
│   ├── src/main/java/.../shim/
│   │   ├── ShimMain.java        # CLI entry point (JCommander)
│   │   ├── ShimProxy.java       # Netty ServerBootstrap
│   │   ├── netty/
│   │   │   └── MultiTargetRoutingHandler.java  # The brain — parallel dispatch + validation
│   │   └── validation/
│   │       ├── Target.java              # Named backend (URI, transforms, auth)
│   │       ├── FieldIgnoringEquality.java  # Deep JSON diff validator
│   │       ├── DocCountValidator.java      # Document count comparison
│   │       └── DocIdValidator.java         # Document ID comparison
│   └── build.gradle             # Jib Docker image, mainClass = ShimMain
│
└── SolrTransformations/         # TypeScript transforms + E2E tests
    ├── transforms/
    │   ├── src/
    │   │   ├── types.ts                    # HTTP message types (mirrors Java)
    │   │   ├── test-types.ts               # Test case framework
    │   │   └── solr-to-opensearch/
    │   │       ├── request.transform.ts    # Entry point — thin wrapper
    │   │       ├── response.transform.ts   # Entry point — thin wrapper
    │   │       ├── context.ts              # Parse once, share across transforms
    │   │       ├── pipeline.ts             # Micro-transform runner
    │   │       ├── registry.ts             # Feature registration
    │   │       ├── cases.testcase.ts       # E2E test case definitions
    │   │       └── features/               # 11 micro-transforms
    │   │           ├── select-uri.ts       # /solr/{col}/select → /{col}/_search
    │   │           ├── query-q.ts          # q=... → query DSL
    │   │           ├── filter-query.ts     # fq=... → bool filter
    │   │           ├── field-list.ts       # fl=... → _source
    │   │           ├── sort.ts             # sort=... → sort clause
    │   │           ├── pagination.ts       # start/rows → from/size
    │   │           ├── facets.ts           # facet params → aggregations
    │   │           ├── hits-to-docs.ts     # hits.hits → response.docs
    │   │           ├── multi-valued.ts     # scalar → array wrapping
    │   │           ├── response-header.ts  # synthesize responseHeader
    │   │           └── response-writer.ts  # set content-type for wt
    │   └── build.mjs                       # esbuild: TS → GraalVM closures
    ├── src/test/java/
    │   └── TransformationShimE2ETest.java  # Data-driven E2E runner
    └── docker/
        ├── docker-compose.yml              # Single shim mode
        └── docker-compose.validation.yml   # 4 shim modes
```

### How a Request Transform Works

Each feature is a small, focused **micro-transform**. Here's `select-uri.ts`:

```typescript
// features/select-uri.ts — rewrites /solr/{collection}/select → /{collection}/_search
export const request: MicroTransform<RequestContext> = {
  name: 'select-uri',
  apply: (ctx) => {
    ctx.msg.URI = `/${ctx.collection}/_search`;
    ctx.msg.method = 'POST';
    ctx.msg.headers = { ...ctx.msg.headers, 'content-type': 'application/json' };
  },
};
```

Features are registered in `registry.ts` and run in order by the pipeline:

```typescript
// registry.ts — request transforms for /select endpoint
export const requestRegistry: TransformRegistry<RequestContext> = {
  global: [],
  byEndpoint: {
    select: [
      selectUri.request,       // URI rewrite — must be first
      queryQ.request,          // q=... → query DSL
      filterQuery.request,     // fq=... → bool filter
      fieldList.request,       // fl=... → _source
      sort.request,            // sort=... → sort clause
      pagination.request,      // start/rows → from/size
      facets.request,          // facet params → aggregations
    ],
  },
};
```

### How to Add a New Feature

1. Create `features/my-feature.ts` exporting `request` and/or `response` micro-transforms
2. Import it in `registry.ts`
3. Add to the appropriate endpoint group
4. Add a test case in `cases.testcase.ts`

Example — adding a `defType` handler:

```typescript
// features/def-type.ts
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';

export const request: MicroTransform<RequestContext> = {
  name: 'def-type',
  match: (ctx) => ctx.params.has('defType'),
  apply: (ctx) => {
    const defType = ctx.params.get('defType');
    if (defType === 'edismax') {
      // Convert edismax params to OpenSearch multi_match
      // ...
    }
  },
};
```

### The Build Pipeline

TypeScript → esbuild → GraalVM closure → Java runtime:

```
*.transform.ts  →  esbuild bundle  →  (function(bindings) { return transform; })  →  JavascriptTransformer
*.testcase.ts   →  esbuild bundle  →  extract testCases array  →  .testcases.json  →  Java E2E harness
```

The `build.mjs` script handles all three output types. In Docker, the `transform-watcher` service runs `npm run watch` for live rebuilds.

---

## 7. Running Tests

### Shim unit tests (Java — validators, proxy, bugfix regression)

```bash
# From repo root
./gradlew :TrafficCapture:transformationShim:test
```

Runs 28 tests:
- `FieldIgnoringEqualityTest` (6) — deep JSON diff with ignored paths
- `DocCountValidatorTest` (6) — document count comparison
- `DocIdValidatorTest` (5) — document ID matching
- `JavascriptValidatorTest` (4) — custom JS validators via GraalVM
- `ShimProxyTest` (4) — full Netty pipeline: single-target, dual-target, validation
- `FactoryFunctionContextTest` (1) — regression: GraalVM factory function invocation
- `URLSearchParamsPolyfillTest` (1) — regression: GraalVM URLSearchParams polyfill
- `JsonRoundTripPropertyModificationTest` (1) — regression: GraalVM polyglot property bridging

### E2E tests (Java + TypeScript — full round-trip through real Solr + OpenSearch)

```bash
# From repo root — requires Docker (uses Testcontainers)
./gradlew :TrafficCapture:SolrTransformations:test
```

This:
1. Builds the TypeScript transforms (`npm install && npm run build`)
2. Copies `dist/*.js` and `dist/*.testcases.json` to test resources
3. For each test case × Solr version in the matrix:
   - Starts Solr + OpenSearch containers via Testcontainers
   - Starts a ShimProxy with the transforms
   - Seeds documents into both backends
   - Sends the test request through the proxy
   - Sends the same request directly to Solr
   - Compares responses using assertion rules (ignore, expect-diff, etc.)

### Test case definitions

Test cases live in `transforms/src/solr-to-opensearch/cases.testcase.ts`:

```typescript
solrTest('filter-query-fq', {
  documents: [
    { id: '1', title: 'cat', category: 'animal' },
    { id: '2', title: 'dog', category: 'animal' },
    { id: '3', title: 'car', category: 'vehicle' },
  ],
  solrSchema: {
    fields: {
      title:    { type: 'text_general' },
      category: { type: 'string' },       // Solr 'string' = exact match
    },
  },
  opensearchMapping: {
    properties: {
      title:    { type: 'text' },
      category: { type: 'keyword' },       // OpenSearch 'keyword' = exact match
    },
  },
  requestPath: '/solr/testcollection/select?q=*:*&fq=category:animal&wt=json',
  assertionRules: [
    ...SOLR_INTERNAL_RULES,
    { path: '$.response.numFound', rule: 'expect-diff', reason: 'fq not fully implemented yet' },
  ],
});
```

Each test case defines:
- `solrSchema` — Solr field types (applied via Schema API)
- `opensearchMapping` — corresponding OpenSearch mapping
- `documents` — data seeded into both backends
- `requestPath` — the Solr query to test
- `assertionRules` — how to handle expected differences

### Adding a test case

Just add a `solrTest()` entry in `cases.testcase.ts`. It automatically runs against every Solr version in the matrix config. No Java changes needed.

---

## 8. Inline Real-Time Validation

### What It Is

In dual-target mode (ports 8083/8084), every request is sent to both Solr and OpenSearch in parallel. The shim runs **validators** that compare the responses and reports results in HTTP headers — on every single request, in real time.

### Validation Headers

```bash
curl -sD- "http://localhost:8083/solr/demo/select?q=*:*&wt=json" 2>&1 | grep -i "x-"
```

```
X-Shim-Primary: solr
X-Shim-Targets: solr,opensearch
X-Target-solr-StatusCode: 200
X-Target-solr-Latency: 12
X-Target-opensearch-StatusCode: 200
X-Target-opensearch-Latency: 45
X-Validation-Status: FAIL
X-Validation-Details: field-equality(solr,opensearch):FAIL[responseHeader.params: missing in opensearch; response.docs[0]._version_: missing in opensearch; ...], doc-count(solr,opensearch):PASS
```

The `field-equality` validator reports FAIL because Solr includes `responseHeader.params` and `_version_` fields that the OpenSearch response transform doesn't produce. The `doc-count` validator passes — both return 3 docs. To make `field-equality` pass, add those paths to the `ignore` list in the docker-compose validator config.

### Built-in Validators

| Validator | CLI Flag | What It Checks |
|-----------|----------|----------------|
| `field-equality` | `--validator field-equality:solr,opensearch:ignore=responseHeader.QTime` | Deep JSON diff, ignoring specified paths |
| `doc-count` | `--validator doc-count:solr,opensearch:assert=solr==opensearch` | Document count matches (==, <=, >=) |
| `doc-ids` | `--validator doc-ids:solr,opensearch:ordered` | Same document IDs, optionally in same order |
| `js` | `--validator js:solr,opensearch:script=custom.js` | Custom JavaScript validator via GraalVM |

### Hot-Reload

The shim supports `--watchTransforms` for live transform reloading:

1. Edit a `.ts` file in `transforms/src/`
2. The `transform-watcher` container rebuilds the `.js` file
3. The shim's `TransformFileWatcher` detects the change
4. `ReloadableTransformer` atomically swaps in the new transform
5. Next request uses the updated transform — no restart needed

Try it:

```bash
# In one terminal — watch the transform-watcher logs
docker compose -f docker/docker-compose.validation.yml logs -f transform-watcher

# In another terminal — edit a transform and see it rebuild
# Then send a request to see the new behavior
curl -s "http://localhost:8082/solr/demo/select?q=*:*&wt=json" | python3 -m json.tool
```

### Error Handling

- **Primary target fails** → client gets the error (same as any proxy)
- **Secondary target fails** → primary response returned normally, `X-Target-{name}-Error` header set, `X-Validation-Status: ERROR`
- **Validator throws** → `X-Validation-Status: ERROR`, details in `X-Validation-Details`

The primary target is never timeout-bounded. Only secondary targets have the `--timeout` constraint (default: 30s).

---

## Teardown

```bash
docker compose -f docker/docker-compose.validation.yml down -v
```

## Quick Reference

| What | Command |
|------|---------|
| Build shim image | `./gradlew :TrafficCapture:transformationShim:jibDockerBuild` |
| Build transforms | `cd SolrTransformations/transforms && npm run build` |
| Watch transforms | `cd SolrTransformations/transforms && npm run watch` |
| Start single-mode stack | `docker compose -f docker/docker-compose.yml up -d` |
| Start validation stack | `docker compose -f docker/docker-compose.validation.yml up -d` |
| Run shim unit tests | `./gradlew :TrafficCapture:transformationShim:test` |
| Run E2E tests | `./gradlew :TrafficCapture:SolrTransformations:test` |
| Run full demo | `./demo-validation.sh --no-teardown` |
| Teardown | `docker compose -f docker/docker-compose.validation.yml down -v` |

## Next Steps

- Read [ARCHITECTURE.md](../../transformationShim/docs/ARCHITECTURE.md) for the full shim architecture with mermaid diagrams
- Read [TRANSFORMS.md](TRANSFORMS.md) for the transform design and build system details
- Add a new feature transform in `features/` and a test case in `cases.testcase.ts`
- Try writing a custom JavaScript validator for your specific use case
