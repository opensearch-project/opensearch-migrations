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

A standalone backup does **not** record the core name, so supply the target index name (see
[Setting the target index name](#setting-the-target-index-name)).

## Pointing the migration at a backup

Set the source version to your Solr version (e.g. `--source-version SOLR_7.7.3`) and provide the
backup location:

- **Local disk:** `--snapshot-local-dir <path>` (document migration) or
  `--file-system-repo-path <path>` (metadata migration).
- **S3:** `--s3-repo-uri s3://<bucket>[/<subpath>]`, `--s3-region`, `--s3-local-dir`, plus
  `--snapshot-name <snapshotName>`. Upload the backup verbatim so its contents land directly under
  `s3://<bucket>[/<subpath>]/<snapshotName>/`.

## Setting the target index name

Both the document migration and the metadata migration let you set the target index name for a
bare backup:

- **SolrCloud:** optional — the collection name is read from `backup.properties`. Set it only to
  override that name.
- **Standalone:** recommended — the core name is not in the backup, so without an override the
  index name is derived from the `snapshot.<backupName>` directory.
- **Wrapped multi-collection layouts:** ignored (each collection keeps its own directory name).

**CLI:** pass `--solr-collection-name <index>`.

**Orchestration workflow:** set `solrCollectionName` in the snapshot's document-backfill options:

```json
{
  "snapshotMigrationConfigs": [{
    "fromSource": "solrSource",
    "toTarget": "osTarget",
    "perSnapshotConfig": {
      "myBackup": [{
        "documentBackfillConfig": {
          "solrCollectionName": "my_target_index"
        }
      }]
    }
  }]
}
```
