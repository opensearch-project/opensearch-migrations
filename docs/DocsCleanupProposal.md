# Documentation Cleanup Proposal

**Status:** Draft for discussion
**Scope:** Where every doc in the repo lives. Some docs will also be edited, combined, or
restructured to fit the new layout and make them easier to maintain, and their content will be
checked to confirm it's up to date.

## Summary

The repo-root `docs/` folder should hold only high-level designs and user guides. Fine-grained
technical docs should live with the code they describe, in a single `docs/` folder for each
top-level directory of the [repo restructure layout](RepoRestructureProposal.md#target-layout).
Today, technical and user-facing docs are mixed together in `docs/`, and another 33 docs are spread
across module folders and the repo root.

## Rule

- **One `docs/` folder per top-level directory, at most.** No module has its own `docs/` folder.
  Inside a top-level `docs/` folder, a subfolder named after a module groups that module's docs,
  for example `backfill/docs/RFS/` and `backfill/docs/RfsPipeline/`. This keeps large areas like
  `backfill/`, with its 10 modules, easy to navigate without scattering `docs/` folders across
  modules.
- **The repo-root `docs/` keeps two kinds of doc:**
  - **High-level designs:** what the system does and why, readable without the code.
  - **User and run guides:** how to configure, deploy, and operate it.
- **Fine-grained technical docs go in the `docs/` folder of the top-level directory whose code they
  describe:** implementation designs, plans, developer guides, and test references.
- **Top-level directories follow the repo restructure layout.** Each migration and supporting
  directory gets one `docs/` folder, created with its first doc. The build and CI directories that
  stay at the root (`buildImages/`, `jenkins/`, `vars/`, `buildSrc/`, `gradle/`) don't get one.
- **These stay where they are:**
  - READMEs, which describe the folder they're in.
  - The standard root files (`CONTRIBUTING`, `DEVELOPER_GUIDE`, `MAINTAINERS`, `SECURITY`,
    `CODE_OF_CONDUCT`, `TESTING`).
  - `.github/` templates.
  - The [exceptions](#exceptions-docs-that-must-stay-put) listed below.

## Proposed layout

```
opensearch-migrations/
├── README.md                     # project overview and entry point
├── CONTRIBUTING.md               # standard root files (unchanged)
├── DEVELOPER_GUIDE.md
├── TESTING.md
├── SECURITY.md
├── MAINTAINERS.md
├── CODE_OF_CONDUCT.md
├── LICENSE, NOTICE
│
├── docs/                         # High-level architecture, repo organization, and user guides
│   ├── README.md                 #   index: what's here, and where every other docs/ folder is
│   ├── Architecture.md           #   high-level architecture of the Migration Assistant
│   ├── RepoOrganization.md       #   what each top-level directory is for
│   ├── guides/                   #   user guides: deploying, configuring, running, and operating
│   ├── imageOverviews/           #   per-image quick references (path unchanged)
│   └── diagrams/                 #   diagrams used by the docs above
│
├── backfill/docs/                # RFS and pipeline designs, snapshot reading, field-type conversion
├── metadata/docs/                # metadata and dashboards migration designs, developer guide
├── TrafficCapture/docs/          # capture proxy, replayer, shim, scaling, and load-test docs
├── transformation/docs/          # transformation docs (created with its first doc)
├── orchestrationSpecs/docs/      # workflow, CRD, and config designs; workflow test references
├── migrationConsole/docs/        # console designs and developer references
├── deployment/docs/              # charts, deployment CLI, and test-environment docs
├── libraries/docs/               # shared-library guides (for example, instrumentation)
├── testing/docs/                 # test images and fixtures docs
├── build-tools/docs/             # developer tooling docs (created with its first doc)
│
└── buildImages/, jenkins/, vars/, buildSrc/, gradle/, schema-viewer/   # no docs/ folder; READMEs only
```

Module READMEs stay in their modules. Inside an area's `docs/` folder, subfolders named after
modules group each module's docs (for example `backfill/docs/RFS/`).

### Placement notes

- **`rfsCronJobMonitor.md`** and its plan go under `orchestrationSpecs` rather than `backfill`.
  They change how the workflow templates monitor RFS, not RFS itself.
- **`TrafficCapture/` covers replay as well as capture.** The directory holds the capture proxy,
  the offloaders, the replayer, and the replay-path transforms, so the replayer docs go in
  `TrafficCapture/docs/`.
- **User guides pulled into the repo-root `docs/`** are `quickstart.md`, `KAFKA_USER_GUIDE.md`,
  the dashboards sanitizer `UserGuide.md`, and the CDK `options.md`. Three are renamed on the way
  in, so their names say what they cover without their old folder for context.

## Exceptions: docs that must stay put

| Doc | Why |
|---|---|
| `migrationConsole/kafkaCmdRef.md` | Packaged into the console image (`build.gradle`, and `COPY kafkaCmdRef.md` in the `Dockerfile`) |
| `migrationConsole/lib/console_link/console_library_diagram.svg` | Embedded in that folder's README |
| `orchestrationSpecs/.../tests/integ/artifacts/parity-catalog.md` | Generated by `parityReporter.mjs`, and excluded by path in `CI.yml` |
| `RFS/test-resources/inventory.md` | Describes the test resources next to it, like a README |
| `buildImages/README-K8s.md` | A second README for that folder |
| `jenkins/DEPENDENCY_CACHE.md` | `jenkins/` stays at the root and gets no `docs/` folder |
| `deployment/k8s/charts/imageMirroringHelpers/AGENT_UPDATE_MANIFESTS.md` | An AI-agent prompt for the files next to it; tooling, not documentation |

## Sequencing

**All doc moves happen at once,** as Phase 0 of the repo restructure. That includes creating
`backfill/docs/`, `metadata/docs/`, and `testing/docs/` before those directories hold any code.
`libraries/` already exists.

- **Entry gate:** maintainers agree on the target layout first, because this commits the repo to
  the new directory names.
- **One or two PRs:** for example, the repo-root `docs/` in one and the module docs in another.
  Each contains only `git mv` renames, link fixes, and the new index, so reviewers can check it
  with `git diff -M`.
- **Later code moves (Phases 3–4)** update about 6–8 README links to these docs a second time,
  when the modules move one or two levels deeper.

## Links to update

About 25. A link checker run (`lychee --offline '**/*.md'`) gates each PR. Besides links between
docs, these files reference the moved docs:

- the root `README.md`, `DEVELOPER_GUIDE.md`, `RFS/README.md`, and
  `DocumentsFromSnapshotMigration/README.md`
- `orchestrationSpecs/README.md`, the `s3TrafficLoader.ts` comment, and the
  `migrationConfigTransformer.ts` comment
- the CDK `README.md` and `awsFeatureUsage.sh`
- the aws CLI `README.md` and the `Cargo.toml` comment
- the `custom-es-images` README
- the argo-workflow-builders test `README.md`
- `deployment/k8s/TESTING.md` and `migrationConsole/lib/integ_test/README.md`, which both link to
  `quickstart.md`

## Risks

- **External links break.** GitHub doesn't redirect moved files. Before merging, check
  opensearch.org docs for links into the repo, then update them or leave "Moved to …" stubs for
  one release.
- **`docs/imageOverviews/` may have an outside consumer.** Nothing in the repo references it, but
  its format suggests it's published as container-registry image descriptions. Confirm before
  touching it.
- **Docs added while a PR is open** need placing on rebase.
