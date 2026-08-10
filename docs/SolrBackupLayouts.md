# Solr Backup Layouts

The Migration Assistant reads an Apache Solr backup directly — from a local path or from S3 — to
migrate metadata (schema → mappings) and documents to OpenSearch. It understands the directory
layouts that Solr itself produces, so **no manual reshaping of the backup is required** before
pointing the migration at it. Upload the backup to S3 exactly as Solr wrote it.

## Supported layouts

### SolrCloud (Collections API `BACKUP`)

Solr 6/7 write the collection's data directly into the backup directory, with no per-collection
wrapper:

```
<snapshotName>/
├── backup.properties          # collection name is read from here
├── snapshot.shard1/           # Lucene index per shard
├── snapshot.shard2/
└── zk_backup/                 # configset + schema
```

The target index name is recovered from `backup.properties`. Solr 8.9+ incremental backups instead
nest the data one level down under a `<collection>/` directory (with `zk_backup_N/`,
`shard_backup_metadata/`, and `index/`); both shapes are handled automatically.

### Standalone Solr (replication handler `command=backup`)

A standalone backup is a single flat Lucene index inside a `snapshot.<name>/` directory, with no
`zk_backup/` or `backup.properties`:

```
<snapshotName>/
└── snapshot.<backupName>/
    ├── segments_N
    └── ...
```

A standalone backup does **not** record the core name, so the target index name is derived from the
`snapshot.<name>` directory (see
[How the target index name is determined](#how-the-target-index-name-is-determined)).

## Running against a secured Solr

Naming what to migrate keeps the migration off Solr's admin-read APIs:

```
--solr-collections movies,reviews
```

Omitting it makes the migration enumerate the source, and Solr offers no way to list collections or
cores below `collection-admin-read` / `core-admin-read`. Supplying it removes that step, leaving the
SolrCloud backup mechanism as the only thing that still needs an admin permission.

Solr is never queried just to work out the topology. Where that can't be established from the backup
or from an operation which has to run anyway, the migration asks you instead:

```
--solr-topology cloud|standalone
```

**`--mode import` normally needs this flag.** Import stages the schema *into* the snapshot, so at
the point topology is decided the snapshot usually holds nothing that identifies either kind. Only
an import over a backup that already carries SolrCloud or standalone markers can skip it.

In a migration workflow the same setting is `topology`, on the backup entry under the source
cluster's `snapshotInfo.backups`:

```yaml
sourceClusters:
  solrSource:
    snapshotInfo:
      backups:
        solrBackup:
          repoName: default
          externalBackupName: preexisting-solr-backup
          topology: standalone          # normally needed; see the marker table below
```

It is accepted on a `createBackupConfig` entry too, where it is optional — creating a backup infers
the topology, so set it there only to skip that inference (see the note under **Creating a backup**
below). The migration console reads it from `snapshot.solr_topology` in `services.yaml`.

### What each step needs

Under Solr's `RuleBasedAuthorizationPlugin`:

| Step | Permission |
| --- | --- |
| SolrCloud backup (`action=BACKUP`, `action=REQUESTSTATUS`) | `collection-admin-edit`, `collection-admin-read` |
| Standalone backup (`replication?command=backup`) | `read` |
| Standalone schema fetch (`admin/file`, or `schema` as fallback) | `config-read`, or `schema-read` |
| Discovery, only without `--solr-collections` | `collection-admin-read` or `core-admin-read` |

This applies only where authorization rules are configured. Solr allows any request that
matches no rule, so an open Solr — or a user with `all` — needs nothing extra.

### How SolrCloud vs standalone is determined

No request to Solr is ever made purely to detect the topology. It comes from an operation the
migration has to run anyway, or from reading the backup itself, and an ambiguous answer is always a
hard failure rather than a guess. Passing `--solr-topology` skips inference altogether.

**Creating a backup** — the SolrCloud `BACKUP` call is attempted directly. A standalone source
rejects it with *"not running in SolrCloud mode"*, and the migration switches to the replication
handler. Any other failure is reported as-is.

> If your standalone Solr's `security.json` guards `collection-admin-edit`, that attempt comes back
> as HTTP 403 rather than the rejection, so the switch never happens. Pass
> `--solr-topology standalone` to skip the attempt.

**Importing an existing backup** — `--solr-topology` is used when given. Otherwise the backup's own
layout is read, which describes the snapshot rather than whatever cluster the source URL points at:

| Found in the backup | Read as |
| --- | --- |
| `backup.properties` / `backup_N.properties`, or `shard_backup_metadata` | SolrCloud |
| a `snapshot.<backupName>/` index (anything but `snapshot.shard<N>`) | standalone |
| neither | fails, asking for `--solr-topology` |

The last row is the usual one, which is why the flag is normally required for import. `zk_backup*`
is deliberately not a signal either way — SolrCloud writes it, and so does this tool when it stages
a schema for a standalone backup — so a snapshot being prepared often carries no evidence at all.
Two real layouts land there too: a flat-root standalone backup (`segments_N` at the root, no
`snapshot.<name>/` wrapper), and a backup named `shard<N>`, which is excluded from the standalone
test so SolrCloud's shard directories can't match it.

## Pointing the migration at a backup

Set the source version to your Solr version (e.g. `--source-version SOLR_7.7.3`) and provide the
backup location:

- **Local disk:** `--snapshot-local-dir <path>` (document migration) or
  `--file-system-repo-path <path>` (metadata migration).
- **S3:** `--s3-repo-uri s3://<bucket>[/<subpath>]`, `--s3-region`, `--s3-local-dir`, plus
  `--snapshot-name <snapshotName>`. Upload the backup verbatim so its contents land directly under
  `s3://<bucket>[/<subpath>]/<snapshotName>/`.

### What `--snapshot-name` means for Solr

For Elasticsearch/OpenSearch, `--snapshot-name` is a key looked up in the snapshot repository's
metadata. Solr backups have no such registry, so here it is only a **path segment** — and whether
it is required, or appended at all, depends on the stage and the backend.

| Stage | Local disk | S3 |
| --- | --- | --- |
| Creating a snapshot | **required**; the path is the repository *root*, and the backup is written to `<root>/<snapshotName>/` | required; appended to the repo URI |
| Metadata migration | optional; the path **is** the backup directory | appended when supplied |
| Document migration | not required for Solr; the path **is** the backup directory | required; appended to the repo URI |

The rule of thumb: on **local disk** only `CreateSnapshot` treats the path as a repository root and
appends the name — the migration stages read the directory you hand them. On **S3** the name is
appended to the repository URI at every stage, so the backup must live under
`s3://<bucket>[/<subpath>]/<snapshotName>/`. Because it only composes a path, it can't rescue a
backup sitting at the bare bucket root — that just reads an empty location.

For a flat-root standalone backup the name also determines the target index (final path segment,
`snapshot.` prefix stripped).

## How the target index name is determined

The target index name is determined automatically from the backup itself — there is no override to
set. How it is resolved depends on the layout:

| Layout | Target index name |
| --- | --- |
| SolrCloud (bare or incremental) | Recovered from `backup.properties` / the backup metadata. |
| Standalone — wrapped (`<snapshotName>/snapshot.<name>/…`) | The inner `snapshot.<name>` directory with the `snapshot.` prefix stripped. |
| Standalone — flat-root (`segments_N` directly at the repo root, no `snapshot.<name>/` wrapper) | The repo's final path segment — the snapshot name — with any `snapshot.` prefix stripped. |
| Wrapped multi-collection layouts | Each collection keeps its own directory name. |

Because a standalone name is derived from the backup's final path segment, the backup must live
**under a prefix** — a flat standalone index sitting directly at the S3 bucket root (an empty repo
key with no snapshot name) has no segment to name the index after and is rejected with an error. The
fix is to store the backup under a named prefix (e.g. `s3://<bucket>/<name>/`) and point the reader
at it — via a repo subpath and/or `--snapshot-name <name>`. Note that `--snapshot-name` only changes
where the reader looks; it does not relocate the data, so it cannot rescue a backup whose segments
genuinely sit at the bucket root.
