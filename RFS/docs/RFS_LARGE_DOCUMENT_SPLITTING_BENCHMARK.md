# RFS large-document splitting benchmark

This report measures the CPU, allocation, and end-to-end worker-memory cost of splitting
transformed documents that exceed a 100 MiB bulk-request limit. The workload deliberately
uses many keys and significant nesting so that it exercises the JSON member index as well
as fragment construction.

## Workload

The transform:

- changes every index operation to create-only semantics and adds a small decoration;
- selects documents deterministically from the document ID at approximately one per million;
- expands selected documents to approximately 190 MiB or 390 MiB;
- spreads the payload across 512 containers, each nested eight levels deep; and
- uses 4 KiB leaf values, producing approximately 48,000 keys at 190 MiB and 99,000 keys
  at 390 MiB.

The cluster runs reused the same 2.2-billion-document quick snapshot, isolated worker node
pool, and target shape as the earlier quick benchmarks. Each run used 24 RFS workers with
3.3 vCPU, 7,000 MiB memory, 100 GiB ephemeral storage, 60 configured target connections,
20,000 documents per bulk request, and a 100 MiB request-size limit.

## Splitter microbenchmark

Run the opt-in JUnit matrix:

```text
./gradlew :RFS:jsonSplitterPerformanceTest
```

Build and run the forked JMH benchmark with GC allocation profiling:

```text
./gradlew :RFS:jmhJar
java -Xms3g -Xmx3g -jar RFS/build/libs/RFS-*-jmh.jar \
  '.*JsonObjectSplitterBenchmark.*' -f 1 -wi 2 -i 5 -w 1s -r 1s -prof gc
```

The following JMH results were collected on Corretto 21.0.12. Input construction is outside
the measured interval.

| Nominal document | Serialized bytes | Fragments | Average split time | Allocated bytes/op | Allocation/input |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 10 MiB | 10,524,458 | 1 | 2.988 ms | 43,914,533 | 4.17x |
| 100 MiB | 104,712,159 | 1 | 48.907 ms | 431,977,600 | 4.13x |
| 190 MiB | 198,899,859 | 3 | 80.874 ms | 1,020,203,066 | 5.13x |
| 390 MiB | 408,210,429 | 5 | 161.366 ms | 1,673,580,695 | 4.10x |

Time is approximately linear in this matrix: increasing the serialized input by 2.05x from
190 MiB to 390 MiB increased average split time by 2.00x. Allocation is also linear in size,
but its approximately 4-5x amplification is large enough to matter inside a worker that
already holds transformed documents and concurrent bulk batches.

## Cluster results

The no-op/create profile completed and provides the control. The expanded profiles were
stopped after repeated independent heap failures because each failure stranded a two-hour
source-shard lease; continuing would measure lease recovery and replay rather than a
converging migration.

| Transform profile | Outcome | Unique target docs | Elapsed | Observed oversized docs | Docs/s before stop | Peak worker working set | Worker CPU | Worker heap OOMs |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Decoration + create | Succeeded | 2,200,000,000 | 1h 52m 13s | 0 | 326,749 | 6,764 MiB | 392,039 CPU-s | 0 |
| Approximately 190 MiB | Failed | 783,240,249 | 38m 11s | 728 | 341,877 | 6,801 MiB | 148,576 CPU-s | 2 |
| Approximately 390 MiB | Failed | 164,390,019 | 12m 12s | at least 168 | 224,577 | 6,765 MiB | 37,314 CPU-s | 3 |

Actual transformed bodies were approximately 198,900,800 bytes and 408,211,400 bytes.
RFS divided them into four and six bulk requests respectively. Worker flow summaries
reported no target rejections, no documents given up, and no 5xx responses before the
runs were stopped.

The 190 MiB failures occurred on different workers after roughly 500 million and 700 million
target documents. The 390 MiB profile failed first at roughly 27 million documents, then
failed on two more workers near 150 million documents. The 7,000 MiB container limit used
`-XX:MaxRAMPercentage=80`, leaving an effective Java heap of about 5.5 GiB.

The allocation failures also identify more than one pressure point. A 190 MiB failure occurred
on a Reactor thread while requesting approximately a 100 MiB allocation; a 390 MiB failure
occurred on a transform thread while requesting approximately the full serialized document
size. The end-to-end path therefore retains or copies memory in the transform adapter,
serialized body, splitter, fragments, and admitted bulk batches concurrently.

## Conclusions

The splitter's CPU complexity is linear for this high-key, deeply nested shape. The current
memory behavior is not safe for transformed 190-390 MiB documents in a 7,000 MiB worker,
even at a one-per-million sampling rate.

The most valuable optimizations to investigate are:

1. Admit oversized-document work through a memory-aware, low-concurrency path before
   transformation and serialization can overlap across batches.
2. Remove full-body copies in fragment construction, especially the
   `ByteArrayOutputStream.toByteArray` and concatenation copies.
3. Represent fragments as byte ranges or composite buffers until the HTTP client needs
   encoded bytes.
4. Add a heap-aware transformed-document limit with an explicit failure reason.
5. Release or checkpoint a work item promptly when a worker process fails so a single
   oversized document does not turn a heap failure into hours of lease delay and shard replay.
