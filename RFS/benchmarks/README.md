# RFS versioning benchmark

`VersioningBenchmark` measures the cost of preserving Lucene `_version` using
real OpenSearch shard files. It is opt-in, with no timing assertions in CI.
The same benchmark source compiles against main before version preservation.
See [measured results](RESULTS.md) for the mainline comparison and the native
transformation and allocation analysis.

## Prepare inputs

From the repository root, with Docker and Python 3 available:

```sh
python3 RFS/benchmarks/prepare_versioning_fixture.py \
  /tmp/rfs-version-fixture-os3 --image opensearchproject/opensearch:3.7.0
python3 RFS/benchmarks/prepare_versioning_fixture.py \
  /tmp/rfs-version-fixture-os2 --image opensearchproject/opensearch:2.19.4
```

The image is an explicit input, so later comparisons can pin the same codec.
The script starts a disposable node, indexes 8,192 documents per dataset,
force-merges, takes a snapshot, stops the node, and copies committed shard files.
It removes its container even on failure. Output directories must not already
exist. Manifests record the engine/codec versions and source/file SHA-256 hashes.
Use these **same files** for both code revisions:

| Dataset | Source size | Versions |
| --- | --- | --- |
| `small-uniform` | 1 KiB | Internally assigned `1` |
| `small-varied` | 1 KiB | Mixed small values and exact integers above 2^53 |
| `large-varied` | 16 KiB | Same distribution, with 120 source fields |

## Run

Use the dedicated task; the existing Gradle `jmh` task sets `fork=0`,
which is unsuitable for comparing JIT-compiled code across revisions.
The dedicated task preserves the ordinary runtime classpath and its service
resources and allows JMH to fork JVMs:

```sh
MIGRATIONS_ROOT_LOG_LEVEL=ERROR ./gradlew :RFS:versioningBenchmark \
  --args="VersioningBenchmark.(readDocument|nativeBatch) \
  -p fixture=/tmp/rfs-version-fixture-os3/small-varied \
  -f 3 -wi 3 -w 2s -i 5 -r 1s -prof gc -foe true \
  -jvmArgsAppend '-Xms1g -Xmx1g -XX:ActiveProcessorCount=4 -DmigrationLogLevel=ERROR -Dbenchmark.expectVersions=true' \
  -rf json -rff /tmp/versioning-native.json"
```

Run `VersioningBenchmark.pipeline` with `-p mode=native,native_gte,java_gte,external_gte`
to measure the production concurrent reader, batching, sink, optional GraalJS
transformation, and bulk serialization. This replaces only the HTTP exchange
with an in-process successful response. `java_gte` supplies a direct Java
`IJsonTransformer` with snapshot-version validation and metadata rewriting.
It is a benchmark comparator, not a registered provider or the full configurable
application-field modifier. `external_gte` uses the bundled JavaScript provider.
`native_gte` uses the bundled `BulkVersioningTransformerProvider`, which edits
typed metadata and retains the source bytes.
The optional `identity` mode measures a pass-through JavaScript transformation.

`java_gte` and JavaScript take the sink's JSON transformation path, including
body conversion to Maps. Selecting Java removes the scripting runtime.
`native_gte` additionally avoids these conversions through the native bulk
transformation contract. All three modes select `external_gte`.

`readDocument` and `nativeBatch` report microseconds and allocation **per
document**. `pipeline` reports them **per complete fixture**: divide by the
manifest's document count. The latter includes allocations on reader threads
when used with JMH's GC profiler. Document-count and native-version assertions
fail the run if extraction or serialization is incorrect.

For mainline, create a separate worktree at the recorded main commit and copy
only this benchmark class and the JMH build configuration into it. Build and
run there with `-Dbenchmark.expectVersions=false`. Run `native` and `identity`;
the version-policy comparisons require the PR's extracted versions.
Do not inject the PR's document reader or adapter into the baseline.

Use the same JDK, heap, CPU allocation, inputs, and JMH options. Run revisions
sequentially, alternate their order, and avoid concurrent builds or containers
during measurements. On Linux, prefix the command with `taskset -c <CPUs>` and
use Gradle's `--no-daemon` option to constrain both JMH and its forked JVMs to
the same CPUs.

## Scope

These are warm-file CPU/allocation measurements, not total migration duration.
They exclude snapshot creation/download, cold storage, HTTP, compression, target
indexing, conflicts/retries, sourceless reconstruction, and deleted documents.
The production reader benchmark includes its existing concurrency and scheduler
overhead. Report uncertainty and allocation alongside averages; small latency
differences within run-to-run variation are inconclusive.
