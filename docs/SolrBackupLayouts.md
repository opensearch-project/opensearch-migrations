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

## Pointing the migration at a backup

Set the source version to your Solr version (e.g. `--source-version SOLR_7.7.3`) and provide the
backup location:

- **Local disk:** `--snapshot-local-dir <path>` (document migration) or
  `--file-system-repo-path <path>` (metadata migration).
- **S3:** `--s3-repo-uri s3://<bucket>[/<subpath>]`, `--s3-region`, `--s3-local-dir`, plus
  `--snapshot-name <snapshotName>`. Upload the backup verbatim so its contents land directly under
  `s3://<bucket>[/<subpath>]/<snapshotName>/`.

### What `--snapshot-name` means for Solr

Unlike Elasticsearch/OpenSearch, where `--snapshot-name` is a **required** key looked up in the
snapshot repository's metadata, Solr backups have no such registry. Here the flag is just a **path
segment** appended to the repo URI (`s3://<bucket>/[<subpath>/]<snapshotName>`) to locate the
backup — **optional**, and **S3-only** (ignored for local-disk backups, where the path you pass
*is* the backup directory). For a flat-root standalone backup it also names the target index (the
final path segment, `snapshot.` prefix stripped). Because it only composes a path, it can't rescue a
backup sitting at the bare bucket root — that just reads an empty location; the backup must live
under a prefix.

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
