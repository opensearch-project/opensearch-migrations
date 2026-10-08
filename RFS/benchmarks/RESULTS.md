# Versioning performance measurements

Measured on 2026-10-08 using immutable shard files produced by real OpenSearch
2.19.4 and 3.7.0 nodes. See [reproduction instructions](README.md) and
[measurements, confidence intervals, and fork means](results.csv).

## Native transformations and buffer sizing

The final comparison uses production revision `17c2a5156`, with `d8f17af7f`
as the header-only control and actual mainline `05474e3b4`. Each cell is elapsed
milliseconds for 8,192 documents, followed by JMH's 99.9% confidence interval.

| Path | 1 KiB, ms (99.9% CI) | 16 KiB, ms (99.9% CI) |
| --- | ---: | ---: |
| Mainline default | 59.90 (58.13–61.66) | 205.16 (202.01–208.30) |
| Header optimization only | 56.72 (55.64–57.81) | 204.59 (201.37–207.81) |
| PR default | 55.43 (53.72–57.15) | 178.95 (176.86–181.04) |
| Native Java GTE | 56.38 (55.19–57.58) | 177.30 (174.94–179.66) |
| Java through Maps GTE | 111.34 (109.88–112.80) | 691.62 (675.37–707.87) |
| Bundled JavaScript GTE | 172.35 (168.52–176.17) | 804.69 (743.80–865.59) |

Native GTE and the new default path have overlapping confidence intervals.
Their mean times differ by +1.7% for 1 KiB documents and -0.9% for 16 KiB
documents; this does not establish that either path is faster than the other.
Compared with the bundled JavaScript policy, native GTE reduces measured elapsed
time by 67% and 78%, respectively. Compared with Java through Maps, the reductions
are 49% and 74%.

| Path | 1 KiB allocation, B/doc | 16 KiB allocation, B/doc |
| --- | ---: | ---: |
| Mainline default | 33,531 | 158,719 |
| Header optimization only | 33,589 | 158,695 |
| PR default | 30,355 | 109,517 |
| Native Java GTE | 30,465 | 109,572 |
| Java through Maps GTE | 44,003 | 241,346 |
| Bundled JavaScript GTE | 52,277 | 249,580 |

Reserving the bulk buffer using known source lengths removes about 3.2 KiB
and 48 KiB of allocation per document versus the header-only control. Native
GTE adds about 110 and 55 bytes per document relative to the default path.
The default path's measured time is 7.5% and 12.8% lower than mainline in this
comparison, despite preserving versions. This is a combined result of the
serialization optimizations; the separate reader measurements below show the
version-read cost.

A native metadata transformation keeps the original body bytes. Accessing
`getDocument()` opts that operation into a mutable body Map; configuring
`versionField` also requires body access. Chains containing JavaScript or opaque
wrappers use the existing JSON path. This API does not make arbitrary scripts
avoid body materialization.

## Method

- AMD EPYC 9R14, Linux x86-64, four pinned CPUs.
- Amazon Corretto 21.0.12.1, 1 GiB fixed heap, G1, `ActiveProcessorCount=4`.
- JMH 1.37; 37 cases / 111 JVM forks / 555 measured iterations across the
  two comparison phases. Three fresh JVM forks per case, three 2-second warmups, five
  1-second measurement iterations, one benchmark thread, GC allocation profiler.
- 8,192 documents per fixture, batches of 256. Sources are exactly 1 KiB or
  16 KiB; the larger documents contain 120 fields. Varied versions include
  values above JavaScript's safe integer range.
- Revisions run sequentially against the same files, with reversed revision
  order for the second dataset. Builds, Docker nodes, and tests are stopped
  before timing. Baseline worktrees contain only the copied benchmark harness
  and its build configuration; their production code is unchanged.
- The `pipeline` benchmark uses the production concurrent Lucene reader,
  batching, document sink, transformation, and bulk serializer. Only the HTTP
  exchange is replaced by a successful response. Timing is per complete
  fixture; the CSV normalizes timing and allocation per document.

These are warm-file CPU/allocation measurements. They exclude snapshot
creation/download, cold storage, HTTP, compression, target indexing, and
conflicts/retries. They do not predict the speedup of a network- or
target-limited migration. Small differences with overlapping confidence
intervals are inconclusive.

## Cost of the version read

`readDocument` measures the production reader before adaptation or serialization.
Comparing actual mainline `05474e3b4` with version preservation:

| Source / versions | Main, µs/doc | With version, µs/doc | Additional allocation, B/doc |
| --- | ---: | ---: | ---: |
| OS 3.7, constant version 1 | 7.218 | 7.449 | 24 |
| OS 3.7, varied versions | 7.447 | 8.128 | 447 |
| OS 2.19, varied versions | 7.282 | 7.465 | 431 |

The read is not free. These differences include carrying the additional value
in the returned document. The OS 3.7 varied-version timing has particularly
wide uncertainty: its 99.9% interval is 7.342–8.915 µs/doc, versus
7.379–7.516 for mainline. Allocation differences are more consistent than
these small elapsed-time differences.

## Removing metadata Map conversion

`nativeBatch` measures sequential reading, adaptation, and serialization.
The original version-preservation implementation is `5facc5bcb`; the direct
metadata serialization change is `d8f17af7f`.

| OS 3.7 fixture | Main, µs/doc | Versions before optimization, µs/doc | Direct metadata serialization, µs/doc | Allocation removed, B/doc |
| --- | ---: | ---: | ---: | ---: |
| 1 KiB, constant version 1 | 8.892 | 9.587 | 9.045 | 1,203 |
| 1 KiB, varied versions | 9.134 | 9.735 | 9.154 | 1,239 |
| 16 KiB, varied versions | 38.993 | 39.581 | 38.997 | 1,226 |

Serializing the metadata POJO directly avoids an intermediate metadata Map
without changing the request bytes. For the two varied-version fixtures,
the resulting means are close to mainline; the constant-version fixture
still shows a small overhead.

## Why changing languages alone is insufficient

Before adding the native transformation contract, the pipeline measured:

| OS 3.7 fixture | Main default, ms | Versioned default, ms | Java through Maps, ms | Bundled JavaScript, ms |
| --- | ---: | ---: | ---: | ---: |
| 1 KiB, varied versions | 58.28 | 56.78 | 110.48 | 174.13 |
| 16 KiB, varied versions | 205.75 | 204.09 | 729.65 | 812.49 |

Each cell is one complete 8,192-document fixture. Both transformation cases
select `external_gte`; default writes use `external`. The Java comparator is
a benchmark implementation of `IJsonTransformer`, not Jolt.

Java removes scripting overhead, but the JSON sink still parses bodies and
copies them through transformation Maps. This motivated the typed
`BulkOperationTransformer` contract and lazy body materialization.

## Correctness and wire size

Functional validation includes 560 RFS unit tests, version-policy scenarios
on OpenSearch 1.3.20, 2.19.4, and 3.7.0, native GTE replay of a real snapshot,
and data-stream migration with rollover. Native tests check exact source
bytes, long precision, original-source diagnostics, generated IDs, retries,
body edits, and ordering with JavaScript. Custom composite overrides retain
their JSON behavior.

An untimed byte-for-byte comparison over all 24,576 OS 3.7 fixture documents
confirmed identical NDJSON SHA-256 hashes before and after buffer sizing.
The earlier direct-metadata optimization also preserved those request bytes.

Preserving the version adds 38 raw bytes per document for the constant-version
fixture and about 50 for varied versions. An untimed gzip size comparison
using 256-document batches found increases of 0.28% for constant 1 KiB
documents, 1.94% for varied 1 KiB documents, and 0.14% for varied 16 KiB
documents. Compression CPU time is not included in the timing measurements.
