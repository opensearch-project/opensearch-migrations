# Solr Transformations — Design

TypeScript-based request/response transforms that convert between Solr and OpenSearch HTTP protocols, executed at runtime via GraalVM.

## Table of Contents

- [Overview](#overview)
- [Build System](#build-system)
- [Transform Lifecycle](#transform-lifecycle)
- [Request Transform](#request-transform)
- [Response Transform](#response-transform)
- [Type System](#type-system)
- [Testing](#testing)

---

## Overview

```mermaid
graph LR
    subgraph SolrTransformations["SolrTransformations/transforms/"]
        subgraph Source["src/"]
            Types["types.ts"]
            ReqTS["solr-to-opensearch/<br/>request.transform.ts"]
            RespTS["solr-to-opensearch/<br/>response.transform.ts"]
            Cases["solr-to-opensearch/<br/>cases.testcase.ts"]
            Config["matrix.config.ts"]
        end

        subgraph BuildTool["build.mjs"]
            ESBuild["esbuild<br/>bundle + tree-shake"]
            GraalWrap["GraalVM closure wrapper"]
            TestExtract["Test case extractor"]
        end

        subgraph Dist["dist/"]
            ReqJS["solr-to-opensearch-request.js"]
            RespJS["solr-to-opensearch-response.js"]
            TestJSON["solr-to-opensearch-cases.testcases.json"]
            ConfigJSON["matrix.config.json"]
        end
    end

    ReqTS --> ESBuild --> GraalWrap --> ReqJS
    RespTS --> ESBuild --> GraalWrap --> RespJS
    Cases --> ESBuild --> TestExtract --> TestJSON
    Config --> ESBuild --> ConfigJSON
```

---

## Build System

The build script (`build.mjs`) processes three types of source files:

| Suffix | Plugin | Output | Purpose |
|--------|--------|--------|---------|
| `*.transform.ts` | `graalvmWrapPlugin` | `*.js` | Runtime transforms |
| `*.testcase.ts` | `testCaseExtractPlugin` | `*.testcases.json` | E2E test data |
| `*.config.ts` | `configExtractPlugin` | `*.config.json` | Test matrix config |

### GraalVM Closure Format

The Java-side `JavascriptTransformer` expects a self-invoking function that returns a transform function:

```javascript
// Output format — what GraalVM loads
(function(bindings) {
  // ... bundled TypeScript code ...
  function transform(msg) { ... }
  return transform;
})
```

The `graalvmWrapPlugin` strips ESM exports and wraps the bundled code in this closure.

### Watch Mode

```bash
npm run watch    # Rebuilds on file changes
npm run build    # One-shot build
```

In Docker, the `transform-watcher` service runs `npm run watch` and writes to a shared volume that the shim containers mount read-only.

---

## Transform Lifecycle

```mermaid
sequenceDiagram
    participant Dev as Developer
    participant FS as Filesystem
    participant Watcher as transform-watcher
    participant Shim as ShimProxy

    Dev->>FS: Edit request.transform.ts
    Watcher->>FS: Detect change
    Watcher->>FS: esbuild → dist/solr-to-opensearch-request.js
    Note over Shim: Reads .js at startup<br/>(no hot-reload yet)
    Shim->>FS: Files.readString(path)
    Shim->>Shim: new JavascriptTransformer(script)
    Shim->>Shim: GraalVM evaluates closure
    Note over Shim: Transform ready for requests
```

---

## Request Transform

Converts Solr select queries into OpenSearch `_search` requests.

```mermaid
graph LR
    subgraph Input["Solr Request"]
        A["GET /solr/mycore/select?q=*:*"]
    end

    subgraph Transform["request.transform.ts"]
        B["Regex: /solr/{collection}/select"]
        C["Rewrite URI → /{collection}/_search"]
        D["Set method → POST"]
        E["Set body → {query:{match_all:{}}}"]
    end

    subgraph Output["OpenSearch Request"]
        F["POST /mycore/_search<br/>{query:{match_all:{}}}"]
    end

    A --> B --> C --> D --> E --> F
```

### Logic

```
IF URI matches /solr/{collection}/select:
  1. URI = /{collection}/_search
  2. method = POST
  3. payload = { inlinedTextBody: '{"query":{"match_all":{}}}' }
  4. headers.content-type = application/json
ELSE:
  passthrough (no modification)
```

---

## Response Transform

Converts OpenSearch `hits` format back to Solr `response` format.

```mermaid
graph LR
    subgraph Input["OpenSearch Response"]
        A["{ hits: {<br/>  total: { value: 3 },<br/>  hits: [<br/>    { _source: { id: '1', title: 'foo' } },<br/>    ...<br/>  ]<br/>}}"]
    end

    subgraph Transform["response.transform.ts"]
        B["Extract hits.hits[]._source"]
        C["Build responseHeader + response"]
    end

    subgraph Output["Solr Response"]
        E["{ responseHeader: { status: 0, QTime: 0 },<br/>  response: {<br/>    numFound: 3, start: 0,<br/>    docs: [<br/>      { id: '1', title: 'foo' },<br/>      ...<br/>    ]<br/>}}"]
    end

    A --> B --> C --> E
```

---

## Type System

The TypeScript types mirror the Java-side `JsonKeysForHttpMessage` schema:

```mermaid
classDiagram
    class HttpRequestMessage {
        +string method
        +string URI
        +string protocol
        +HttpHeaders headers
        +Payload payload
    }

    class HttpResponseMessage {
        +string code
        +string reason
        +string protocol
        +HttpHeaders headers
        +Payload payload
    }

    class Payload {
        +unknown inlinedJsonBody
        +string inlinedTextBody
        +unknown[] inlinedJsonSequenceBodies
        +unknown inlinedBinaryBody
        +string inlinedBase64Body
    }

    class HttpHeaders {
        +[key: string]: string | string[]
    }

    HttpRequestMessage --> Payload
    HttpRequestMessage --> HttpHeaders
    HttpResponseMessage --> Payload
    HttpResponseMessage --> HttpHeaders
```

The types also define the tuple schema (`SourceTargetTuple`) used by the replayer's tuple transforms, though the shim currently only uses the v2 request/response schema.

---

## Testing

### E2E Test Architecture

```mermaid
graph TB
    subgraph TestCases["cases.testcase.ts"]
        TC["TestCaseDefinition[]<br/>name, input, expectedOutput"]
    end

    subgraph Build["build.mjs"]
        Extract["testCaseExtractPlugin"]
    end

    subgraph JSON["dist/solr-to-opensearch-cases.testcases.json"]
        Data["[{name, input, expected}, ...]"]
    end

    subgraph Java["TransformationShimE2ETest.java"]
        Load["Load JSON test cases"]
        Shim["Start ShimProxy"]
        Send["Send input through shim"]
        Assert["Assert output matches expected"]
    end

    TC --> Extract --> Data --> Load --> Shim --> Send --> Assert
```

Test cases are defined in TypeScript alongside the transforms, extracted to JSON at build time, and consumed by the Java E2E test suite. This keeps test data co-located with the transform logic while allowing the Java test harness to drive the actual HTTP round-trip through the full Netty pipeline.

### Matrix Testing

The `matrix.config.ts` file defines test matrix parameters (Solr versions, OpenSearch versions, etc.) that are extracted to JSON and consumed by the test framework for parameterized testing.
