# Routing Indices to OpenSearch Serverless Collections

OpenSearch Serverless gives every account a single endpoint per Region, `<account-id>.aoss.<region>.on.aws`, that
serves all of the account's NextGen collections. Each request names its collection in the
`x-amz-aoss-collection-name` header, and that header has to be part of the SigV4 signature. Migration Assistant
can use this endpoint as one target and send each source index to its own collection, so you don't need a
separate target and snapshot migration for every collection.

The per-account endpoint only works with NextGen collections, which are created in a NextGen collection group
and can be search or vector search collections. Classic collections, including all time series collections, only
have per-collection endpoints, so each one still needs its own target.

Collection routing applies to metadata migration and document backfill. To replay traffic to a collection-routed
target, give the replayer a request transform that adds the `x-amz-aoss-collection-name` header to each request,
and the replayer signs that header along with the rest of the request. The transform has to change the request it
receives and return that same object, because SigV4 signing fails when a transform returns a new object. A request
that spans indices in different collections, such as a mixed `_bulk`, can't be routed this way. The migration
console's cluster commands, such as `connection-check` and `cat-indices`, don't send the collection header, so most
of them don't work against a collection-routed target.

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
collection. Every selected source index must match an entry, and a run with an unmatched index fails before
writing anything, as described in [Validation](#validation).

## How entries match

An entry in `staticCollectionRouting` maps one exact index name to one collection, and its `collection` is used
as written. Each index name can appear there only once.

An entry in `regexCollectionRouting` has a Java regular expression as its `sourceIndex`, and the expression has
to match the whole index name. Its `collection` can use the pattern's capture groups, either by number (`$1`) or
by name (`${app}` for a group written as `(?<app>...)`). To use a literal `$` or `\`, escape it with `\`. A
workflow configuration doesn't accept all Java regex syntax, as described in [Validation](#validation).

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
* A `regexCollectionRouting` `sourceIndex` isn't a valid regular expression.
* A `collection` in `regexCollectionRouting` references a group its pattern doesn't define, such as `$2` for a
  pattern with one group.
* A replayer points at a `collectionRouted` target and has no request transform. The workflow can't check what a
  transform does, so it assumes that one adds the header.

The workflow checks each regex with JavaScript's engine, which doesn't match Java's exactly. It rejects some valid
Java syntax, so inline flags such as `(?i)`, possessive quantifiers such as `a++` and atomic groups such as `(?>a)`
aren't accepted in a workflow configuration, and those patterns need rewriting without them. It also lets some
invalid Java patterns through, such as `logs-{` or an unknown escape like `\y`. When a migration starts, Java checks
each regex and `collection` again and stops the migration with an error if one is invalid.

Before writing anything, metadata migration and each backfill worker check that every source index selected by
`indexAllowlist` matches an entry. If any index has no match, the run fails and lists every unmatched index.

## Templates

Metadata migration doesn't migrate index templates, component templates or legacy templates to a collection-routed
target, because they don't belong to any one index and so have no single collection to go to. Each template appears
in the results as skipped, which doesn't fail the migration. If a collection needs a template, create it there
yourself.

## Document IDs

Vector search collections require the service to generate document IDs, while search collections keep the IDs from
the source. One migration can route indices to both, so the decision is made per collection. With the default
`serverGeneratedIds` setting of `AUTO`, each backfill work item checks the type of its collection before writing and
drops source document IDs only if that collection needs it. `ALWAYS` and `NEVER` apply to every collection, so
`ALWAYS` drops source IDs from search collections too, and `NEVER` can fail on vector search collections.

## Running the tools directly

Outside the workflow, pass the same settings to `MetadataMigration` and `RfsMigrateDocuments` as arguments.
Both lists go in one JSON object.

```
--target-host https://123456789012.aoss.us-east-1.on.aws \
--target-aws-region us-east-1 \
--target-aws-service-signing-name aoss \
--collection-routing '{"staticCollectionRouting": [{"sourceIndex": "shared-config", "collection": "common"}],
  "regexCollectionRouting": [{"sourceIndex": "(.+)[-_]\\d{4}", "collection": "$1"}]}'
```

Setting `--collection-routing` makes the target collection-routed, so it also needs SigV4 auth with the `aoss`
service. A collection-routed target is always treated as OpenSearch Serverless, so its version isn't probed.
