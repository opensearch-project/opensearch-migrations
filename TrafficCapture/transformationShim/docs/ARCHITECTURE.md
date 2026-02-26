# Transformation Shim — Architecture

A multi-target HTTP proxy that sits between clients and backend search engines, providing request/response transformation, parallel dispatch, and in-flight response validation.

## Table of Contents

- [System Context](#system-context)
- [Cutover & Validation Use Cases](#cutover--validation-use-cases)
- [Component Architecture](#component-architecture)
- [Request Flow](#request-flow)
- [Data Model](#data-model)
- [Validation Framework](#validation-framework)
- [Transform Pipeline](#transform-pipeline)
- [Deployment Modes](#deployment-modes)
- [CLI Reference](#cli-reference)

---

## System Context

The shim is a Netty-based reverse proxy that accepts Solr-format HTTP requests and can dispatch them to one or more named backend targets (Solr, OpenSearch, etc.) in parallel. Each target can have independent request/response transforms and authentication. The primary target's response is returned to the client, with validation results from cross-target comparison added as HTTP headers.

```mermaid
graph LR
    Client["Solr Client"]

    subgraph Shim["Transformation Shim (:8080)"]
        Router["MultiTargetRoutingHandler"]
    end

    Solr["Solr (:8983)"]
    OS["OpenSearch (:9200)"]

    Client -->|"Solr HTTP request"| Router
    Router -->|"original request"| Solr
    Router -->|"transformed request"| OS
    Solr -->|"Solr response"| Router
    OS -->|"OS response → transform back"| Router
    Router -->|"primary response + validation headers"| Client
```

The shim supports three operational patterns, all from the same binary:

| Pattern | Targets | Transforms | Validators | Use Case |
|---------|---------|------------|------------|----------|
| Passthrough | 1 | None | None | Transparent proxy |
| Transform | 1 | Request + Response | None | Protocol translation (Solr → OpenSearch) |
| Validation | 2+ | Per-target | Cross-target | In-flight correctness verification |

---

## Cutover & Validation Use Cases

The shim supports a phased migration from Solr to OpenSearch. Each phase maps to a shim configuration — no code changes, just CLI flags.

```mermaid
graph LR
    subgraph Phase1["Phase 1: Shadow Validation"]
        P1C["Client"] --> P1S["Shim<br/><i>primary=solr</i>"]
        P1S --> P1Solr["Solr ✓"]
        P1S -.->|"shadow"| P1OS["OpenSearch"]
    end

    subgraph Phase2["Phase 2: Cutover"]
        P2C["Client"] --> P2S["Shim<br/><i>primary=opensearch</i>"]
        P2S -.->|"safety net"| P2Solr["Solr"]
        P2S --> P2OS["OpenSearch ✓"]
    end

    subgraph Phase3["Phase 3: Direct"]
        P3C["Client"] --> P3S["Shim<br/><i>opensearch only</i>"]
        P3S --> P3OS["OpenSearch ✓"]
    end

    Phase1 -->|"validators pass"| Phase2
    Phase2 -->|"stable"| Phase3
```

### Phase 1 — Shadow Validation (Solr Primary)

Production traffic continues to be served by Solr. The shim silently forwards every request to OpenSearch in parallel, runs validators, and reports results in response headers. The client always gets the Solr response — OpenSearch failures are invisible to end users.

**Goal:** Build confidence that OpenSearch returns equivalent results before switching.

```bash
--target solr=http://solr:8983 \
--target opensearch=https://os:9200 \
--primary solr \
--targetTransform opensearch=request:req.js,response:resp.js \
--validator field-equality:solr,opensearch:ignore=responseHeader.QTime \
--validator doc-count:solr,opensearch:assert=solr==opensearch
```

**What to monitor:**
- `X-Validation-Status` header — track PASS/FAIL ratio over time
- `X-Target-opensearch-Latency` — compare latency between backends
- `X-Target-opensearch-Error` — catch connectivity or transform issues early
- `X-Validation-Details` — identify which validators fail and on which queries

### Phase 2 — Cutover with Safety Net (OpenSearch Primary)

Flip `--primary` to `opensearch`. The client now gets OpenSearch responses, but Solr still runs in the background as a safety net. Validators continue to compare — if OpenSearch diverges, the headers flag it immediately.

**Goal:** Serve production from OpenSearch while retaining the ability to detect regressions.

```bash
--target solr=http://solr:8983 \
--target opensearch=https://os:9200 \
--primary opensearch \
--targetTransform opensearch=request:req.js,response:resp.js \
--validator field-equality:solr,opensearch:ignore=responseHeader.QTime \
--validator doc-count:solr,opensearch:assert=solr==opensearch
```

**Rollback:** Change `--primary` back to `solr` — one config change, no data migration.

### Phase 3 — Direct OpenSearch (Solr Decommissioned)

Once validation passes consistently, remove Solr from the target list. The shim becomes a simple transform proxy (or can be removed entirely if the client speaks OpenSearch natively).

```bash
--target opensearch=https://os:9200 \
--primary opensearch \
--targetTransform opensearch=request:req.js,response:resp.js
```

### Validation Strategies

Different migration scenarios call for different validator configurations:

| Scenario | Validators | What it catches |
|----------|-----------|-----------------|
| Exact parity | `field-equality` (ignore QTime) | Any response difference beyond timing |
| Document completeness | `doc-count:assert=solr==opensearch` | Missing or extra documents in OpenSearch |
| Ranking equivalence | `doc-ids:ordered` | Same docs in same order |
| Superset check | `doc-count:assert=opensearch>=solr` | OpenSearch returns at least as many results |
| Custom business logic | `js:script=custom.js` | Application-specific invariants (e.g., facet counts, highlighting) |

### Multi-Cluster Comparison

The shim isn't limited to two targets. Compare multiple OpenSearch clusters (e.g., different versions, different index configurations) against the Solr baseline simultaneously:

```mermaid
graph LR
    Client["Client"] --> Shim["Shim<br/><i>primary=solr</i>"]
    Shim --> Solr["Solr"]
    Shim --> OSv1["OpenSearch v1"]
    Shim --> OSv2["OpenSearch v2"]

    Shim -.->|"validate solr vs os-v1"| V1["field-equality"]
    Shim -.->|"validate solr vs os-v2"| V2["field-equality"]
    Shim -.->|"validate os-v1 vs os-v2"| V3["doc-count"]
```

```bash
--target solr=http://solr:8983 \
--target os-v1=https://os-v1:9200 \
--target os-v2=https://os-v2:9200 \
--primary solr \
--validator field-equality:solr,os-v1 \
--validator field-equality:solr,os-v2 \
--validator doc-count:os-v1,os-v2:assert=os-v1==os-v2
```

---

## Component Architecture

```mermaid
graph TB
    subgraph CLI["CLI Layer"]
        ShimMain["ShimMain<br/><i>JCommander CLI</i>"]
    end

    subgraph Proxy["Proxy Layer"]
        ShimProxy["ShimProxy<br/><i>Netty ServerBootstrap</i>"]
    end

    subgraph Pipeline["Netty Pipeline"]
        Codec["HttpServerCodec"]
        Agg["HttpObjectAggregator"]
        KA["KeepAlive Detect"]
        MTRH["MultiTargetRoutingHandler"]
    end

    subgraph Dispatch["Per-Target Dispatch"]
        ReqTx["Request Transform<br/><i>IJsonTransformer</i>"]
        Auth["Auth Handler<br/><i>SigV4 / Basic</i>"]
        Backend["HTTP Client<br/><i>Netty Bootstrap</i>"]
        RespTx["Response Transform<br/><i>IJsonTransformer</i>"]
        TRH["TargetResponseHandler"]
    end

    subgraph Validation["Validation Layer"]
        VR["ValidationRule[]"]
        FIE["FieldIgnoringEquality"]
        DCV["DocCountValidator"]
        DIV["DocIdValidator"]
        JSV["JavascriptValidator"]
    end

    subgraph Transforms["TypeScript Transforms"]
        TS["*.transform.ts"]
        Build["build.mjs<br/><i>esbuild</i>"]
        JS["*.js<br/><i>GraalVM closure</i>"]
    end

    ShimMain --> ShimProxy
    ShimProxy --> Codec --> Agg --> KA --> MTRH
    MTRH --> ReqTx --> Auth --> Backend --> TRH --> RespTx
    MTRH --> VR
    VR --> FIE
    VR --> DCV
    VR --> DIV
    VR --> JSV
    TS --> Build --> JS
    JS -.->|"loaded at startup<br/>hot-reloaded with --watchTransforms"| ReqTx
    JS -.->|"loaded at startup<br/>hot-reloaded with --watchTransforms"| RespTx
```

### Package Structure

```
transformationShim/
├── src/main/java/org/opensearch/migrations/transform/shim/
│   ├── ShimMain.java                    # CLI entry point
│   ├── ShimProxy.java                   # Netty server bootstrap
│   ├── ReloadableTransformer.java       # Hot-swap transformer wrapper
│   ├── TransformFileWatcher.java        # File watcher for --watchTransforms
│   ├── netty/
│   │   ├── MultiTargetRoutingHandler.java  # Core: parallel dispatch + validation
│   │   ├── HttpMessageUtil.java            # Netty ↔ Map conversion + response utilities
│   │   ├── SigV4SigningHandler.java        # AWS SigV4 auth
│   │   ├── BasicAuthSigningHandler.java    # Basic/header auth
│   │   └── ShimChannelAttributes.java      # Channel attributes
│   └── validation/
│       ├── Target.java                  # Named backend record
│       ├── TargetResponse.java          # Per-target result record
│       ├── ValidationResult.java        # Validation outcome record
│       ├── ResponseValidator.java       # Validator interface
│       ├── ValidationRule.java          # Named rule binding
│       ├── FieldIgnoringEquality.java   # Deep JSON diff
│       ├── DocCountValidator.java       # Document count comparison
│       ├── DocIdValidator.java          # Document ID comparison
│       └── JavascriptValidator.java     # Custom JS validator (GraalVM)
└── src/test/java/...
```

---

## Request Flow

### Single-Target Mode

When only one target is active, the shim acts as a simple transform proxy:

```mermaid
sequenceDiagram
    participant C as Client
    participant S as ShimProxy
    participant T as Target Backend

    C->>S: HTTP Request
    S->>S: HttpServerCodec → Aggregator → KeepAlive
    S->>S: MultiTargetRoutingHandler.channelRead0()
    S->>S: applyRequestTransform(target, request)
    S->>S: applyAuth(target, request)
    S->>T: HTTP Request (transformed)
    T->>S: HTTP Response
    S->>S: TargetResponseHandler.channelRead0()
    S->>S: applyResponseTransform(target, response)
    S->>S: buildFinalResponse(primary, headers)
    S->>C: HTTP Response + X-Shim-* headers
```

### Multi-Target Validation Mode

When multiple targets are active, requests are dispatched in parallel:

```mermaid
sequenceDiagram
    participant C as Client
    participant S as ShimProxy
    participant A as Target A (primary)
    participant B as Target B (secondary)

    C->>S: HTTP Request
    S->>S: channelRead0() — fork

    par Dispatch to all targets
        S->>A: request (original or transformed)
        S->>B: request (original or transformed)
    end

    A->>S: Response A
    B->>S: Response B (or timeout)

    S->>S: collectResponses(futures)
    S->>S: runValidators(allResponses)
    S->>S: buildFinalResponse(primary=A, all, validationResults)
    S->>C: Response A body + per-target headers + validation headers
```

### Response Headers

Every response includes metadata headers:

```mermaid
graph LR
    subgraph Always["Always Present"]
        H1["X-Shim-Primary: solr"]
        H2["X-Shim-Targets: solr,opensearch"]
    end

    subgraph PerTarget["Per Target"]
        H3["X-Target-solr-StatusCode: 200"]
        H4["X-Target-solr-Latency: 12"]
        H5["X-Target-opensearch-StatusCode: 200"]
        H6["X-Target-opensearch-Latency: 45"]
    end

    subgraph Val["Validation (multi-target only)"]
        H7["X-Validation-Status: PASS"]
        H8["X-Validation-Details: field-equality:PASS, doc-count:PASS"]
    end
```

| Header Pattern | When | Example |
|----------------|------|---------|
| `X-Shim-Primary` | Always | `solr` |
| `X-Shim-Targets` | Always | `solr,opensearch` |
| `X-Target-{name}-StatusCode` | Per target (success) | `200` |
| `X-Target-{name}-Latency` | Per target (success) | `45` (ms) |
| `X-Target-{name}-Error` | Per target (failure) | `Connection refused` |
| `X-Validation-Status` | Multi-target + validators | `PASS` / `FAIL` / `ERROR` |
| `X-Validation-Details` | Multi-target + validators | `field-equality:PASS, doc-count:FAIL[...]` |

---

## Data Model

Core types that flow through the system:

```mermaid
classDiagram
    class Target {
        +String name
        +URI uri
        +IJsonTransformer requestTransform
        +IJsonTransformer responseTransform
        +Supplier~ChannelHandler~ authHandlerSupplier
    }

    class TargetResponse {
        +String targetName
        +int statusCode
        +byte[] rawBody
        +Map parsedBody
        +Duration latency
        +Throwable error
        +isSuccess() boolean
        +error(name, latency, error)$ TargetResponse
    }

    class ValidationResult {
        +String ruleName
        +boolean passed
        +String detail
    }

    class ValidationRule {
        +String name
        +List~String~ targetNames
        +ResponseValidator validator
    }

    class ResponseValidator {
        <<interface>>
        +validate(Map~String,TargetResponse~) ValidationResult
    }

    class FieldIgnoringEquality {
        -String targetA
        -String targetB
        -Set~String~ ignoredPaths
    }

    class DocCountValidator {
        -String targetA
        -String targetB
        -Comparison comparison
        -String countPath
    }

    class DocIdValidator {
        -String targetA
        -String targetB
        -boolean orderMatters
        -String docsPath
        -String idField
    }

    class JavascriptValidator {
        -String name
        -JavascriptTransformer transformer
    }

    Target --> TargetResponse : produces
    ValidationRule --> ResponseValidator : wraps
    ResponseValidator <|.. FieldIgnoringEquality
    ResponseValidator <|.. DocCountValidator
    ResponseValidator <|.. DocIdValidator
    ResponseValidator <|.. JavascriptValidator
    ResponseValidator --> ValidationResult : returns
```

---

## Validation Framework

Validators compare responses from two or more targets. Each validator receives the full response map but operates on its configured target pair.

```mermaid
graph TB
    subgraph Input["Collected Responses"]
        RA["TargetResponse: solr<br/>statusCode=200, body={...}"]
        RB["TargetResponse: opensearch<br/>statusCode=200, body={...}"]
    end

    subgraph Rules["Validation Rules"]
        R1["ValidationRule: field-equality<br/>targets=[solr, opensearch]"]
        R2["ValidationRule: doc-count<br/>targets=[solr, opensearch]"]
    end

    subgraph Validators["Validator Implementations"]
        V1["FieldIgnoringEquality<br/>ignore: responseHeader.QTime"]
        V2["DocCountValidator<br/>assert: solr == opensearch"]
    end

    subgraph Output["Results"]
        VR1["ValidationResult<br/>field-equality: PASS"]
        VR2["ValidationResult: doc-count: PASS"]
        Status["X-Validation-Status: PASS"]
    end

    RA --> R1
    RB --> R1
    RA --> R2
    RB --> R2
    R1 --> V1 --> VR1
    R2 --> V2 --> VR2
    VR1 --> Status
    VR2 --> Status
```

### Built-in Validators

| Validator | What it does | Config |
|-----------|-------------|--------|
| `FieldIgnoringEquality` | Deep JSON diff between two targets, skipping specified dot-paths | `ignore=responseHeader.QTime,responseHeader.params.NOW` |
| `DocCountValidator` | Compares document count at a JSON path (default: `response.numFound`) | `assert=solr==opensearch` (or `<=`, `>=`) |
| `DocIdValidator` | Compares document IDs from `response.docs[].id` | `ordered` (optional — checks order too) |
| `JavascriptValidator` | Custom JS function via GraalVM: `(responses) → {passed, detail}` | `script=custom.js` |

### Error Handling

```mermaid
flowchart TD
    A[Primary target fails?] -->|Yes| B[Return 502 Bad Gateway]
    A -->|No| C[Secondary target fails?]
    C -->|Yes| D["Return primary response<br/>X-Target-{name}-Error: reason<br/>X-Validation-Status: ERROR"]
    C -->|No| E[Run validators]
    E --> F{All passed?}
    F -->|Yes| G["X-Validation-Status: PASS"]
    F -->|No| H["X-Validation-Status: FAIL"]
    E --> I{Validator threw?}
    I -->|Yes| J["X-Validation-Status: ERROR"]
```

---

## Transform Pipeline

Transforms convert between Solr and OpenSearch HTTP formats. They're written in TypeScript, bundled to JavaScript, and executed in GraalVM at runtime.

```mermaid
graph LR
    subgraph Dev["Development"]
        TS["request.transform.ts<br/>response.transform.ts"]
    end

    subgraph Build["Build (esbuild)"]
        Bundle["bundle + tree-shake"]
        Wrap["wrap in GraalVM closure:<br/>(function(bindings) {<br/>  ...bundled code...<br/>  return transform;<br/>})"]
    end

    subgraph Runtime["Runtime (Java)"]
        Load["Files.readString(path)"]
        Graal["JavascriptTransformer<br/><i>GraalVM polyglot</i>"]
        IJson["IJsonTransformer<br/>.transformJson(Map)"]
    end

    TS --> Bundle --> Wrap
    Wrap -->|".js file"| Load --> Graal --> IJson
```

### Hot-Reload (`--watchTransforms`)

When `--watchTransforms` is enabled, transforms are wrapped in `ReloadableTransformer` and a `TransformFileWatcher` daemon thread monitors the JS files via Java's `WatchService`. When the `transform-watcher` container rebuilds a JS file, the shim detects the change and atomically swaps in a new `JavascriptTransformer` — no restart required. In-flight requests complete with the old transformer; new requests use the updated one.

```mermaid
sequenceDiagram
    participant Dev as Developer
    participant TW as transform-watcher<br/>(esbuild --watch)
    participant FS as Filesystem<br/>(shared volume)
    participant FW as TransformFileWatcher<br/>(WatchService)
    participant RT as ReloadableTransformer

    Dev->>TW: Edit .ts file
    TW->>FS: Rebuild .js
    FS->>FW: ENTRY_MODIFY event
    FW->>FW: Files.readString(path)
    FW->>RT: reload(() → new JavascriptTransformer)
    RT->>RT: Swap delegate (volatile)
    Note over RT: Next request uses new transform
```

### Transform Contract

Transforms implement the `IJsonTransformer` interface via JavaScript:

```
Input (request):  { method, URI, protocol, headers, payload: { inlinedTextBody } }
Output (request): { method, URI, protocol, headers, payload: { inlinedTextBody } }

Input (response):  { statusCode, protocol, headers, payload: { inlinedTextBody } }
Output (response): { statusCode, protocol, headers, payload: { inlinedTextBody } }
```

The Solr→OpenSearch request transform rewrites:
- `/solr/{collection}/select?q=...` → `POST /{collection}/_search`
- Adds `{"query":{"match_all":{}}}` body

The OpenSearch→Solr response transform converts:
- `hits.hits[]._source` → `response.docs[]`
- Adds `responseHeader` with status and QTime

### Auth Handlers

Per-target authentication is applied after the request transform:

```mermaid
graph LR
    Req["Transformed Request"] --> Auth{Auth Type?}
    Auth -->|sigv4| SigV4["SigV4SigningHandler<br/><i>AWS credentials + service + region</i>"]
    Auth -->|basic| Basic["BasicAuthSigningHandler<br/><i>Authorization: Basic ...</i>"]
    Auth -->|header| Header["BasicAuthSigningHandler<br/><i>Authorization: custom-value</i>"]
    Auth -->|none| Pass["No-op"]
    SigV4 --> Out["Signed Request"]
    Basic --> Out
    Header --> Out
    Pass --> Out
```

Auth handlers are applied via `EmbeddedChannel` — the existing Netty handlers (SigV4, Basic) are reused without modification.

---

## Deployment Modes

The Docker Compose setup runs multiple shim instances to demonstrate all modes simultaneously:

```mermaid
graph TB
    Client["Solr Client"]

    subgraph Docker["docker-compose.validation.yml"]
        subgraph Backends
            Solr["Solr :8983"]
            OS["OpenSearch :9200"]
        end

        subgraph Shims
            S1[":8081 solr-only<br/><i>passthrough</i>"]
            S2[":8082 opensearch-only<br/><i>transform</i>"]
            S3[":8083 solr-primary<br/><i>dual + validate</i>"]
            S4[":8084 opensearch-primary<br/><i>dual + validate</i>"]
        end

        Watcher["transform-watcher<br/><i>node:20 — esbuild watch</i>"]
    end

    Client --> S1
    Client --> S2
    Client --> S3
    Client --> S4

    S1 --> Solr
    S2 -->|"transform"| OS
    S3 --> Solr
    S3 -->|"transform"| OS
    S4 --> Solr
    S4 -->|"transform"| OS

    Watcher -.->|"rebuilds .js"| S2
    Watcher -.->|"rebuilds .js"| S3
    Watcher -.->|"rebuilds .js"| S4
```

All shim instances with transforms use `--watchTransforms`, so when the `transform-watcher` rebuilds JS files from TypeScript changes, the shims hot-reload them without restart.

| Port | Service | Primary | Targets | Validators | Description |
|------|---------|---------|---------|------------|-------------|
| 8081 | shim-solr-only | solr | solr | — | Passthrough proxy to Solr |
| 8082 | shim-opensearch-only | opensearch | opensearch | — | Transform proxy to OpenSearch |
| 8083 | shim-solr-primary | solr | solr, opensearch | field-equality, doc-count | Returns Solr response, validates against OpenSearch |
| 8084 | shim-opensearch-primary | opensearch | solr, opensearch | field-equality, doc-count | Returns OpenSearch response, validates against Solr |

### Build & Run

```bash
# Build the Docker image
cd TrafficCapture && ../gradlew :TrafficCapture:transformationShim:jibDockerBuild

# Start all services
cd SolrTransformations
docker compose -f docker/docker-compose.validation.yml up

# Or run the full demo (builds, seeds data, demos each mode)
./demo-validation.sh
```

---

## CLI Reference

```
ShimMain — Multi-target validation shim proxy

Required:
  --listenPort <port>           Proxy listen port
  --target <name=uri>           Named target (repeatable)
  --primary <name>              Target whose response is returned to client

Optional:
  --active <name,name,...>      Active targets (default: all)
  --targetTransform <spec>      Per-target transforms (repeatable)
                                Format: name=request:file.js,response:file.js
  --targetAuth <spec>           Per-target auth (repeatable)
                                Formats: name=sigv4:service,region
                                         name=basic:user:pass
                                         name=header:value
                                         name=none
  --validator <spec>            Validation rule (repeatable)
                                Formats: field-equality:a,b:ignore=path1,path2
                                         doc-count:a,b:assert=a<=b
                                         doc-ids:a,b[:ordered]
                                         js:a,b:script=file.js
  --timeout <ms>                Secondary target timeout (default: 30000)
  --watchTransforms             Watch transform JS files and hot-reload on change
  --insecureBackend             Trust all backend TLS certificates
```

### Example Configurations

**Passthrough proxy:**
```bash
--target solr=http://solr:8983 --primary solr
```

**Single-target with transforms:**
```bash
--target opensearch=https://os:9200 --primary opensearch \
--targetTransform opensearch=request:req.js,response:resp.js
```

**Dual-target validation (Solr primary):**
```bash
--target solr=http://solr:8983 \
--target opensearch=https://os:9200 \
--targetTransform opensearch=request:req.js,response:resp.js \
--targetAuth opensearch=sigv4:es,us-east-1 \
--primary solr \
--validator field-equality:solr,opensearch:ignore=responseHeader.QTime \
--validator doc-count:solr,opensearch:assert=solr==opensearch
```

**Three targets (comparing two OpenSearch clusters):**
```bash
--target solr=http://solr:8983 \
--target os-v1=https://os-v1:9200 \
--target os-v2=https://os-v2:9200 \
--targetTransform os-v1=request:req.js,response:resp.js \
--targetTransform os-v2=request:req-v2.js,response:resp-v2.js \
--primary solr \
--validator field-equality:solr,os-v1 \
--validator field-equality:solr,os-v2 \
--validator doc-count:os-v1,os-v2:assert=os-v1==os-v2
```
