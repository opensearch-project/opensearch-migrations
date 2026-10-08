# Routing Indices to OpenSearch Serverless Collections

OpenSearch Serverless gives every account a single endpoint per Region, `<account-id>.aoss.<region>.on.aws`, that
serves all of the account's NextGen collections. Each request names its collection in the
`x-amz-aoss-collection-name` header, and that header has to be part of the SigV4 signature. Migration Assistant
can use this endpoint as one target and send each source index to its own collection, so you don't need a
separate target and snapshot migration for every collection.

The per-account endpoint only works with NextGen collections, which are created in a NextGen collection group
and can be search or vector search collections. Classic collections, including all time series collections, only
have per-collection endpoints, so each one still needs its own target.

Collection routing applies to metadata migration and document backfill. Traffic replay doesn't support it yet,
so a replayer can't point at a collection-routed target.

## Configuration

Mark the target as `collectionRouted` and give it SigV4 auth with the `aoss` service. Then add
`staticCollectionRouting`, `regexCollectionRouting`, or both to every per-snapshot migration entry that uses the
target. The lists sit next to `metadataMigrationConfig` and `documentBackfillConfig` because both steps read
them, which keeps an index's metadata and its documents in the same collection.

```json
"targetClusters": {
  "tenant-collections": {
    "endpoint": "https://123456789012.aoss.us-east-1.on.aws",
    "authConfig": { "sigv4": { "region": "us-east-1", "service": "aoss" } },
    "collectionRouted": true
  }
},
"snapshotMigrationConfigs": [{
  "fromSource": "source",
  "toTarget": "tenant-collections",
  "perSnapshotConfig": {
    "snap1": [{
      "staticCollectionRouting": [
        { "sourceIndex": "shared-config", "collection": "common" }
      ],
      "regexCollectionRouting": [
        { "sourceIndex": "(.+)[-_]\\d{4}", "collection": "$1" }
      ],
      "metadataMigrationConfig": { },
      "documentBackfillConfig": { }
    }]
  }
}]
```

With these lists, `shared-config` goes to `common`, and `tenant-a-2024` and `tenant-a_2025` go to the `tenant-a`
collection.

## How entries match

An entry in `staticCollectionRouting` maps one exact index name to one collection, and its `collection` is used
as written. Each index name can appear there only once.

An entry in `regexCollectionRouting` has a Java regular expression as its `sourceIndex`, and the expression has
to match the whole index name. Its `collection` can use the pattern's capture groups, either by number (`$1`) or
by name (`${app}` for a group written as `(?<app>...)`). To use a literal `$` or `\`, escape it with `\`.

An index first looks for an exact match in `staticCollectionRouting`, which always wins. If there isn't one, the
regex entries are checked in order and the first match is used, so put specific patterns before broad ones.

Routing always uses the source index name. If a transform renames an index or rewrites its documents' `_index`,
the collection is still chosen from the name in the snapshot.

## Validation

The workflow configuration is rejected when any of these is true.

* A target is `collectionRouted` but doesn't use SigV4 with the `aoss` service.
* A migration to a `collectionRouted` target has an entry with no routing entries in either list.
* An entry has a routing list but its target isn't `collectionRouted`.
* The same index name appears more than once in `staticCollectionRouting`.
* A replayer points at a `collectionRouted` target.

When a migration starts, each regex is compiled, and a `collection` that references a group its pattern doesn't
define, such as `$2` for a pattern with one group, stops the migration with an error.

Before writing anything, metadata migration and each backfill worker check that every source index selected by
`indexAllowlist` matches an entry. If any index has no match, the run fails and lists every unmatched index.
Metadata migration also fails if no source index is selected at all, because templates need at least one
collection to go to.

## Templates

Index templates, component templates and legacy templates don't belong to any index. On a collection-routed
target, metadata migration creates each template in every collection that at least one migrated index routes
to. The results list each copy separately, for example `logs-template (collection tenant-a)`.

## Document IDs

Some collection types require the service to generate document IDs. With the default `serverGeneratedIds`
setting of `AUTO`, each backfill work item checks the type of its collection before writing and drops source
document IDs only when that collection needs it. One migration can therefore send indices to collections of
different types, such as a search collection and a vector search collection.

## Running the tools directly

Outside the workflow, pass the same settings to `MetadataMigration` and `RfsMigrateDocuments` as arguments.
Both lists go in one JSON object.

```
--target-host https://123456789012.aoss.us-east-1.on.aws \
--target-aws-region us-east-1 \
--target-aws-service-signing-name aoss \
--target-collection-routed \
--collection-routing '{"staticCollectionRouting": [{"sourceIndex": "shared-config", "collection": "common"}],
  "regexCollectionRouting": [{"sourceIndex": "(.+)[-_]\\d{4}", "collection": "$1"}]}'
```

Each tool requires `--collection-routing` when `--target-collection-routed` is set and rejects it otherwise.
A collection-routed target is always treated as OpenSearch Serverless, so its version isn't probed.
