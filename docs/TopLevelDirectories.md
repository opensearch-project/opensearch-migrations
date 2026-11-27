# Top-Level Directories Proposal

This page defines each top-level directory in the proposed repo layout: what it contains, and what
part of a migration it serves. Where a directory doesn't exist yet, the modules it groups are
listed with their current paths.

## Map

```
opensearch-migrations/
├── backfill/               # Move existing documents from a snapshot (RFS)
│   ├── core/               #   engine: RFS, RfsCommon, RfsHttp, RfsPipeline, SearchSnapshotExtractor
│   ├── readers/            #   source readers: SnapshotReader, SnapshotReaderGcs, SolrReader
│   └── apps/               #   runnables: DocumentsFromSnapshotMigration, CreateSnapshot
├── metadata/               # Move indices, templates, aliases; sanitize dashboards
├── TrafficCapture/         # Capture live traffic and replay it to the target
├── transformation/         # Rewrite requests and documents for the target version
├── orchestrationSpecs/     # Migration config schema and the workflows that run each step
├── migrationConsole/       # The user's CLI and control point
├── deployment/             # Install the Migration Assistant (Helm, Terraform, CDK)
├── libraries/              # Shared code: metrics/tracing, AWS signing, S3, Kafka helpers
├── testing/                # Test fixtures, test clusters, sandboxes, e2e scripts
├── build-tools/            # git hooks and developer tools
├── buildImages/            # Builds all container images (stays at root)
├── jenkins/  vars/         # CI pipelines and Jenkins shared library (stay at root)
├── buildSrc/  gradle/      # Gradle build logic and wrapper (stay at root)
├── schema-viewer/          # Web viewer for the config schema
└── docs/                   # High-level designs and user guides
```

## How the directories map to a migration

A migration with the Migration Assistant runs through these stages. Each stage is served by one
top-level directory, with `orchestrationSpecs/` and `migrationConsole/` driving the whole run.

| Stage | What happens | Directory |
|---|---|---|
| 1. Deploy | Stand up the Migration Assistant on Kubernetes (EKS, GKE, or local) | `deployment/` |
| 2. Configure and run | Describe the migration in a config; the workflow runs each step | `orchestrationSpecs/`, `migrationConsole/` |
| 3. Capture | Record live traffic to the source cluster | `TrafficCapture/` |
| 4. Metadata | Move indices, templates, settings, and aliases to the target | `metadata/` |
| 5. Backfill | Move existing documents from a snapshot to the target | `backfill/` |
| 6. Replay | Send captured traffic to the target to catch it up and compare behavior | `TrafficCapture/` |
| Throughout | Rewrite requests and documents for the target version | `transformation/` |
| Switchover | Route clients to the target | `deployment/`, `TrafficCapture/` |

Everything else (`libraries/`, `testing/`, `build-tools/`, and the root build folders) supports
building and testing the tools rather than running a migration.

## Migration tools

### `backfill/`

**Contains:** Reindex-from-Snapshot (RFS), the document backfill tooling, in three groups:

| Group | Modules (today's path) | Purpose |
|---|---|---|
| `core/` | `RFS` | Core RFS behavior: target cluster clients, bulk writing, work coordination between workers, and per-version writers |
| | `RfsCommon` | Shared models, interfaces, and enums used by every backfill module |
| | `RfsHttp` | HTTP client, authentication, and connection handling for talking to clusters |
| | `RfsPipeline` | Source-agnostic pipeline that streams documents from a source to a sink, with progress tracking for resumption |
| | `SearchSnapshotExtractor` | Reads Lucene index files directly, using bundled copies of Lucene 5 through 10, to rebuild documents from snapshot segments |
| `readers/` | `SnapshotReader` | Reads Elasticsearch and OpenSearch snapshots, from ES 1.7 through 9.0, on a filesystem or in S3 |
| | `SnapshotReaderGcs` | Adds Google Cloud Storage as a snapshot repository |
| | `SolrReader` | Reads Apache Solr backups and converts Solr schemas to OpenSearch mappings |
| `apps/` | `DocumentsFromSnapshotMigration` | The runnable backfill worker. Many copies run in parallel, each claiming shards and writing their documents to the target. |
| | `CreateSnapshot` | Takes a snapshot of the source cluster in the form RFS expects (including global metadata) |

**`docs/`:** `RFS/` (design, field-type conversion, snapshot reading) and `RfsPipeline/` (architecture, data flow, IR types, testing strategy).

**Role in a migration:** moves the existing data. RFS reads documents straight from a snapshot of
the source, instead of querying the source cluster, so the backfill puts no load on production. It
can also skip intermediate upgrades: for example, an Elasticsearch 6.8 snapshot can go directly to
OpenSearch 2.x.

### `metadata/`

**Contains:**

| Module (today's path) | Purpose |
|---|---|
| `MetadataMigration` | Evaluates and migrates indices, index templates, component templates, and aliases, and reports what can't be moved as-is |
| `dashboardsSanitizer` | *(Experimental)* Fixes Kibana saved-object exports from 7.10.2 or later so OpenSearch Dashboards can import them |

**`docs/`:** `MetadataMigration/` (design, developer guide), `DashboardsMigration/` (design), and `dashboardsSanitizer/` (end-to-end testing).

**Role in a migration:** runs before the backfill, because indices and templates must exist on the
target before documents arrive. Its evaluate mode lets users see incompatibilities before changing
anything.

### `TrafficCapture/`

**Contains:** the whole capture-and-replay system, despite the name:

| Part | Modules | Purpose |
|---|---|---|
| Capture | `trafficCaptureProxyServer` | A proxy in front of the source that forwards every request and records it |
| | `captureOffloader`, `captureKafkaOffloader`, `captureProtobufs`, `nettyWireLogging` | Serialize captured traffic and write it to Kafka |
| Replay | `trafficReplayer` | Reads captured traffic from Kafka, transforms it, sends it to the target, and records source and target responses |
| | `tupleSink` | Writes the source/target response pairs ("tuples") for later comparison |
| Replay-path transforms | `transformationShim`, `SolrTransformations` | A shim that translates requests in flight, including Solr-to-OpenSearch query translation |
| Supporting | `trafficLoadTest`, `dockerSolution` | Load-testing tools, and a Docker Compose environment for local development |

**`docs/`:** scaling design, Kafka auth wiring plan, and per-module docs for `trafficReplayer/`, `trafficCaptureProxyServer/`, `trafficLoadTest/`, `SolrTransformations/`, and `transformationShim/`.

**Role in a migration:** keeps the target in sync with live changes, and lets users validate the
target under real traffic before switching over. The proxy captures traffic from before the
snapshot is taken, so after the backfill the replayer can catch the target up to the present.

### `transformation/`

**Contains:** the transformation framework and its plugins. `transformationPlugins/` holds the
transformer interface, loaders, and implementations in JavaScript, Python, JMESPath, and Jolt, plus
a type-mappings sanitizer. `standardJavascriptTransforms/` holds the built-in transforms.

**`docs/`:** none yet.

**Role in a migration:** rewrites requests and documents so a source cluster's data works on the
target version. One example is removing mapping types that newer versions don't support. The
metadata migration, the backfill, and the replayer all load the same transforms, and users can
supply their own.

### `orchestrationSpecs/`

**Contains:** TypeScript packages that define and run the migration workflow:

| Package | Purpose |
|---|---|
| `schemas` | The user-facing migration config format, validated with Zod |
| `config-processor` | CLI that turns a user config into Kubernetes resources and submits the workflow |
| `migration-workflow-templates` | The full Argo Workflow definitions for each migration step |
| `argo-workflow-builders` | Library for building Argo Workflows in code |
| `k8s-types`, `argo-types`, `strimzi-types`, `crd-type-generator` | Types generated from the Kubernetes, Argo, and Strimzi Kafka schemas |

**`docs/`:** workflow CRD and reconfiguration designs, config resolution and validation, CRD/VAP hardening plans, the RFS CronJob monitor, mountable transforms and file bundles, bring-your-own captured traffic, and the argo-workflow-builders test reference.

**Role in a migration:** the engine that runs everything else. A user describes the source,
target, and steps in one config. The workflow then deploys and sequences the capture proxy,
metadata migration, backfill, and replayer, and pauses at approval gates for the user to confirm.

### `migrationConsole/`

**Contains:** the Migration Console container image. It includes the `console` CLI and its Python
library (`console_link`), cluster tools, the integration test suite (`integ_test`), and Kafka
helper commands.

**`docs/`:** console Kubernetes resource selection, workflow tree display, and manual approval-gate testing.

**Role in a migration:** the user's control point. From the console, users submit and watch
workflows, approve gates, check backfill progress, inspect clusters, and export captured traffic.

### `deployment/`

**Contains:** everything that installs the Migration Assistant:

| Folder | Purpose |
|---|---|
| `k8s/` | Helm charts (the main `migrationAssistantWithArgo` chart, test clusters, image-mirroring helpers), the AWS bootstrap CLI, and local kind-cluster scripts |
| `terraform/` | Terraform modules for AWS (EKS Auto Mode) and GCP (GKE with GCS snapshots) |
| `cdk/` | The earlier AWS CDK deployment |
| `migration-assistant-solution/` | The AWS Solutions CloudFormation packaging |

**`docs/`:** EKS network-isolation results, k8s testing, the AWS CLI dependency audit, the `migrationAssistantWithArgo` developer guide, and the ECR mirroring plan.

**Role in a migration:** step one. Users pick a platform, deploy the infrastructure and the Helm
chart, and get a running console and workflow engine.

## Supporting directories

### `libraries/`

**Contains:** shared code that isn't specific to one migration tool:

| Module | Purpose |
|---|---|
| `coreUtilities` | The metrics and tracing framework (OpenTelemetry-based) that every tool uses |
| `awsUtilities` | AWS SigV4 request signing |
| `s3Common` | Writing rotating, compressed files to S3 (used for outputs such as the failed-document stream) |
| `kafkaUtils`, `kafkaCommandLineFormatter` | Load captured traffic from a file into Kafka, and print captured records readably |
| `testAutomation` | Integration-test helpers for Kubernetes-based migrations |

**`docs/`:** the `coreUtilities/` instrumentation developer guide.

**Role in a migration:** none directly. These libraries are built into the tools above.

### `testing/`

**Contains:** test infrastructure:

| Module (today's path) | Purpose |
|---|---|
| `testHelperFixtures`, `searchClusterTestFixtures` | Shared test utilities and containerized search clusters for unit and integration tests |
| `DataGenerator` | Generates benchmark-style workloads to load into a test source cluster |
| `custom-es-images`, `custom-solr-images` | Docker images for every Elasticsearch minor version and for Solr, used as test sources |
| `solrMigrationDevSandbox` | A local Solr, OpenSearch, and shim environment for end-to-end checks |
| `e2e/` (today's `test/`) | Scripts that deploy full AWS environments and run the end-to-end suites from Jenkins |

**`docs/`:** the `custom-es-images/` benchmark.

**Role in a migration:** none directly. This is how the project verifies migrations across source
versions before release.

### `build-tools/`

**Contains:** `git-hooks/` (the pre-commit hook) and `dev-tools/` (API request templates and a
local Jenkins setup).

**`docs/`:** none yet.

**Role in a migration:** none. These are developer conveniences.

### Directories that stay at the root

| Directory | Purpose | Why it stays at the root |
|---|---|---|
| `buildImages/` | Builds every container image, using local or in-cluster BuildKit | Released CLI binaries and Helm templates reference its path |
| `jenkins/` | Jenkins pipeline definitions and build-host setup scripts | The Jenkins server's job configuration references its path |
| `vars/` | The Jenkins shared library (pipeline steps) | Jenkins requires `vars/` at the repo root |
| `buildSrc/`, `gradle/` | Shared Gradle build logic and the Gradle wrapper | Gradle convention |
| `schema-viewer/` | A static web page for browsing the workflow config schema, published to GitHub Pages | Already self-contained |
| `docs/` | High-level designs and user guides (see [DocsCleanupProposal.md](DocsCleanupProposal.md)) | Repo-wide |

## Full target layout

Every top-level directory and the modules inside it. `was:` gives today's path for anything that
moves. Each migration and supporting directory has one `docs/` folder for its technical docs (see
[DocsCleanupProposal.md](DocsCleanupProposal.md)). The build and CI directories that stay at the
root don't get one. Git doesn't track empty folders, so the ones marked *(none yet)* are created
when their first doc arrives. See [RepoRestructureProposal.md](RepoRestructureProposal.md) for the phases that make each
move.

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
