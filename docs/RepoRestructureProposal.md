# Repository Restructure Proposal

**Status:** Draft for discussion
**Companion docs:** [TopLevelDirectories.md](TopLevelDirectories.md) (what each directory is for)
and [DocsCleanupProposal.md](DocsCleanupProposal.md) (where every doc goes).

## Summary

The repo has ~35 top-level directories with no grouping. Java packages are shared across modules,
so a class's package doesn't tell you where it lives. Docs are scattered. This proposal:

1. **Groups directories by product area:** backfill, metadata, capture and replay,
   transformation, orchestration, console, and deployment, plus libraries, testing, and build
   tools.
2. **Gives every Java package one owning module.**
3. **Puts user-facing docs in the root `docs/` and technical docs in each area's `docs/`.**

Nothing changes behavior. Gradle project paths and artifact names stay the same, so dependency
declarations and downstream users are unaffected.

## Problems

- **A flat, mixed root.** Java modules, test fixtures, build tooling, and loose plan docs sit side by
  side. Only `TrafficCapture/` groups its modules, and it's the easiest area to find your way
  around.
- **Shared Java packages.** For example, `bulkload.common` is declared by six modules. Classes in a
  shared package can use each other's package-private members across modules, which hides coupling.
  The sharing is left over from the 2026 RFS module split, which moved files without changing their
  packages. New modules have copied the pattern since.
- **Scattered docs.** Designs, plans, and user guides are mixed in `docs/`, and more docs sit in
  module folders and at the root.

## Target layout

```
opensearch-migrations/
├── backfill/               # Phase 3 (docs/ in Phase 0): Reindex-from-Snapshot
│   ├── core/               #   RFS, RfsCommon, RfsHttp, RfsPipeline, SearchSnapshotExtractor
│   ├── readers/            #   SnapshotReader, SnapshotReaderGcs, SolrReader
│   └── apps/               #   DocumentsFromSnapshotMigration, CreateSnapshot
├── metadata/               # Phase 3 (docs/ in Phase 0): MetadataMigration, dashboardsSanitizer
├── TrafficCapture/         # unchanged: capture and replay
├── transformation/         # unchanged
├── orchestrationSpecs/     # unchanged
├── migrationConsole/       # unchanged
├── deployment/             # unchanged
├── libraries/              # Phase 4 (docs/ in Phase 0): coreUtilities, awsUtilities, s3Common, kafka*, testAutomation
├── testing/                # Phase 4 (docs/ in Phase 0): test fixtures, test images, sandboxes, e2e scripts (was test/)
├── build-tools/            # Phase 5: git-hooks, dev-tools
├── buildImages/  jenkins/  vars/  buildSrc/  gradle/   # stay at root (external references or tool rules)
├── schema-viewer/          # unchanged
└── docs/                   # Phase 0: high-level designs and user guides only
```

Each area directory gets one `docs/` folder for its technical docs. All of those folders are
created, and all docs moved, in Phase 0, before any code moves.

**Why this grouping:**
- It matches how the [README](../README.md#key-features) and [Architecture](Architecture.md)
  describe the product.
- The busiest directories (`TrafficCapture/`, `orchestrationSpecs/`, `migrationConsole/`) stay
  put, because CI, image builds, and tests reference their paths.
- `backfill/` gets subfolders because it's the largest group (10 modules), and its dependencies
  already flow in one direction: apps → readers → core.

## Docs

The root `docs/` keeps high-level designs and user guides. Technical docs move to the `docs/`
folder of the area they describe. **Every doc moves in Phase 0,** including into area directories
that don't hold code yet (`backfill/`, `metadata/`, `testing/`). Doing it in one step means:

- The docs reorganization is finished in one PR, and the `docs/README.md` index is written once.
- Phases 3–5 become pure code moves, which are easier to review.
- New docs written during the later phases land in their final place.

The cost:

- Until Phase 3–4, directories like `backfill/` hold only `docs/`. Gradle ignores a directory
  without a `build.gradle`, so this is harmless.
- About 6–8 README links to module docs get updated twice: once in Phase 0, and again when the
  module moves deeper. The link checker catches any that are missed.

**Entry gate:** maintainers agree on the target layout before Phase 0 merges, because it commits
the repo to the new directory names. If the restructure stops after Phase 0, the result is still a
working docs layout.

See [DocsCleanupProposal.md](DocsCleanupProposal.md) for the full map.

## Package ownership

**Rule:** each Java package belongs to exactly one module. In each shared package, the module with
the most foundational or most numerous classes keeps the package, and the other modules' classes
move into packages their own module owns. A build check (Phase 2) keeps it that way.

### Backfill packages (Phase 1)

| Shared package | Kept by | Others move to |
|---|---|---|
| `bulkload.common`, `cluster` | RfsCommon | RFS → `bulkload.target` (target clients, writers); RfsHttp → `bulkload.common.http`; SnapshotReader → `bulkload.snapshot`; SearchSnapshotExtractor → `bulkload.lucene` |
| `bulkload.pipeline`, `bulkload.pipeline.adapter` | RfsPipeline | RFS → `bulkload.target`; SnapshotReader → `bulkload.snapshot`; DocumentsFromSnapshotMigration → `bulkload.bootstrap` |
| `bulkload.tracing`, `bulkload.transformers`, `bulkload.worker` | RFS | RfsCommon → `bulkload.common.*`; SnapshotReader → `bulkload.snapshot` |
| `bulkload.version_es_6_8` | SnapshotReader | RFS → `bulkload.target.version_es_6_8` |

That's about 41 classes.

### Remaining shared packages (Phase 1b)

| Shared package | Decision |
|---|---|
| Root `org.opensearch.migrations` (10 modules) | **Reserved for application entry points.** The four `main` classes (`CreateSnapshot`, `DataGenerator`, `RfsMigrateDocuments`, `MetadataMigration`) stay, because workflow templates, the CDK stack, Gradle, and logging config reference them by full name. Everything else moves out: `Version` and its matchers → `version` *(new, transformation)*; `MigrationMode`, `VersionStrictness` → `bulkload.common`; `Utils`, `BulkDocErrorTypes` → `utils`; `IHttpMessage` → `aws`; `VersionConverter` → `parsing`; `NettyFutureBinders` → `replay.netty`; CreateSnapshot helpers → `snapshot.backup` *(new)*; `DataGeneratorArgs` → `data`; `SolrBackupDiscovery` → `bulkload.bootstrap`; MetadataMigration helpers → its existing `cli` and `commands` packages. |
| `transform` (14 modules) | **Kept by `jsonMessageTransformerInterface`**, the public interface custom transforms implement, so custom transforms keep compiling. Each plugin moves to its own subpackage (`transform.jolt`, `transform.jmespath`, `transform.js`, `transform.python`, `transform.graal`, `transform.typemappings`, `transform.loaders`). User configs keep working, because providers are matched by simple class name. The `META-INF/services` files are updated to match. The replayer's auth transformers → `replay.auth` *(new)*. |
| `utils` | **Kept by coreUtilities.** `FileSystemUtils` (DocumentsFromSnapshotMigration) → `bulkload.bootstrap`; `TrackedFutureJsonFormatter` (replayer) → `replay.util`. |
| `utils.kafka` | **Kept by kafkaCommandLineFormatter**, because users pass `org.opensearch.migrations.utils.kafka.Base64Formatter` to `kafka-console-consumer` (see `kafkaExport.sh`). kafkaUtils' loader classes → `utils.kafka.loader`. |
| `replay.sink` | **Kept by tupleSink.** The replayer's `ThreadLocalTupleWriter` → `replay`. |
| `trafficcapture.protos` | **Kept by captureProtobufs** (generated code). The replayer's `TrafficStreamUtils` → `replay.util`. |

That's about 54 classes. After Phase 1b, the root package is the only shared package left, and
the build check only allows `main` classes in it.

**Compatibility:** Java has no type aliases, so external code that imports a moved class has to
change. The classes users are known to reference (entry points, the transform interfaces, and
`Base64Formatter`) don't move.

## Phases

Each phase ships on its own, and the work can stop after any of them.

| Phase | Change | Risk |
|---|---|---|
| 0 | **Docs cleanup:** create every area's `docs/` folder and move all docs; delete two obsolete files. Requires agreement on the target layout. | Low |
| 1 | **Backfill package ownership:** ~41 class moves, 4 PRs | Medium |
| 1b | **Remaining shared packages:** ~54 class moves | Medium |
| 2 | **Build check:** fail the build when two modules declare the same package. Can land first, with an allowlist that shrinks as Phase 1 lands. | Low |
| 3 | **`backfill/` and `metadata/`:** move the modules (code only) | Low–medium |
| 4 | **`libraries/` and `testing/`:** move the modules (code only). Jenkins pipelines are made path-tolerant first, because they call `test/` scripts. | Medium |
| 5 | **`build-tools/`:** move `git-hooks/` and `dev-tools/` | Low |

## Constraints and risks

- **Some directories can't move.**
  - `vars/`, `buildSrc/`, and `gradle/` must stay at the root (Jenkins and Gradle rules).
  - `buildImages/` and `jenkins/` stay because released CLI binaries and the Jenkins server's
    job configuration reference their paths.
- **Hard-coded paths:** Docker build contexts, CI path filters, and `vars/` pipelines name
  directories, and each move updates them in the same PR.
- **Open PRs will conflict.** Schedule moves right after a release and announce them ahead of time.
  Keep each PR a pure move.
- **External links into `docs/` break,** because GitHub doesn't redirect moved files.

## Open questions

1. Do external users import any RFS or transform plugin classes directly? If so, moved classes may
   need deprecated copies at the old name for one release.
2. Should `TrafficCapture/` later be split into `capture/`, `replay/`, and `shim/`, like
   `backfill/`? The code allows it, but about 30 files and 9 build files reference its paths.
3. Do the Jenkins e2e jobs load `vars/` from `main` or from the PR branch? This affects how Phase 4
   handles the `test/` move.

## Full target layout

Every top-level directory and the modules inside it, with each area's `docs/` folder. `was:`
gives today's path for anything that moves.

```
opensearch-migrations/
├── backfill/                               # Reindex-from-Snapshot (RFS) modules
│   ├── docs/                               # RFS/ and RfsPipeline/ technical docs
│   ├── core/                               # Migration engine, shared by readers and apps
│   │   ├── RFS/                            # was: RFS/
│   │   ├── RfsCommon/                      # was: RfsCommon/
│   │   ├── RfsHttp/                        # was: RfsHttp/
│   │   ├── RfsPipeline/                    # was: RfsPipeline/
│   │   └── SearchSnapshotExtractor/        # was: SearchSnapshotExtractor/ (Lucene reading library)
│   │
│   ├── readers/                            # Source-format readers
│   │   ├── SnapshotReader/                 # was: SnapshotReader/ (ES/OS snapshots)
│   │   ├── SnapshotReaderGcs/              # was: SnapshotReaderGcs/
│   │   └── SolrReader/                     # was: SolrReader/
│   │
│   └── apps/                               # Runnable entry points (images, CLIs)
│       ├── DocumentsFromSnapshotMigration/ # was: DocumentsFromSnapshotMigration/
│       └── CreateSnapshot/                 # was: CreateSnapshot/
│
├── metadata/                               # Metadata and dashboards migration
│   ├── docs/                               # MetadataMigration/, DashboardsMigration/, dashboardsSanitizer/ docs
│   ├── MetadataMigration/                  # was: MetadataMigration/
│   └── dashboardsSanitizer/                # was: dashboardsSanitizer/
│
├── TrafficCapture/                         # unchanged: capture and replay
│   ├── docs/                               # capture, replay, shim, and load-test docs
│   ├── trafficCaptureProxyServer/
│   ├── captureOffloader/
│   ├── captureKafkaOffloader/
│   ├── captureProtobufs/
│   ├── nettyWireLogging/
│   ├── trafficReplayer/
│   ├── tupleSink/
│   ├── transformationShim/
│   ├── SolrTransformations/
│   ├── trafficLoadTest/
│   └── dockerSolution/
│
├── transformation/                         # unchanged
│   ├── docs/                               # (none yet)
│   ├── transformationPlugins/
│   └── standardJavascriptTransforms/
│
├── orchestrationSpecs/                     # unchanged
│   ├── docs/                               # workflow, CRD, and config designs
│   └── packages/                           # schemas, config-processor, migration-workflow-templates,
│                                           # argo-workflow-builders, and generated type packages
│
├── migrationConsole/                       # unchanged
│   ├── docs/                               # console designs and dev references
│   ├── lib/                                # console_link, integ_test
│   └── cluster_tools/
│
├── deployment/                             # unchanged
│   ├── docs/                               # chart, CLI, and test-environment docs
│   ├── k8s/                                # Helm charts, AWS bootstrap CLI, kind scripts
│   ├── terraform/                          # aws, gcp
│   ├── cdk/
│   └── migration-assistant-solution/
│
├── libraries/                              # Shared, product-agnostic libraries
│   ├── docs/                               # coreUtilities/ instrumentation guide
│   ├── coreUtilities/                      # was: coreUtilities/
│   ├── awsUtilities/                       # was: awsUtilities/
│   ├── s3Common/                           # was: s3Common/
│   ├── kafkaUtils/                         # unchanged
│   ├── kafkaCommandLineFormatter/          # unchanged
│   └── testAutomation/                     # unchanged
│
├── testing/                                # Test fixtures, sandboxes, and e2e scripts
│   ├── docs/                               # custom-es-images/ benchmark
│   ├── testHelperFixtures/                 # was: testHelperFixtures/
│   ├── searchClusterTestFixtures/          # was: searchClusterTestFixtures/
│   ├── DataGenerator/                      # was: DataGenerator/
│   ├── custom-es-images/                   # was: custom-es-images/
│   ├── custom-solr-images/                 # was: custom-solr-images/
│   ├── solrMigrationDevSandbox/            # was: solrMigrationDevSandbox/
│   └── e2e/                                # was: test/
│
├── build-tools/                            # Developer tooling
│   ├── docs/                               # (none yet)
│   ├── git-hooks/                          # was: git-hooks/
│   └── dev-tools/                          # was: dev-tools/
│
├── buildImages/                            # stays at root (released CLIs reference its path)
├── jenkins/                                # stays at root (Jenkins job config references its path)
├── vars/                                   # stays at root (Jenkins shared library)
├── buildSrc/                               # stays at root (Gradle)
├── gradle/                                 # stays at root (Gradle)
├── schema-viewer/                          # unchanged
└── docs/                                   # High-level designs and user guides
```
