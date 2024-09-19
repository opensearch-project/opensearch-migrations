
## Metadata Migration
When performing a migration of a search cluster, the metadata items such as indexes, templates, configuration and processes need to be in place before document data can be moved.  The metadata migration tool provides insight into what can be moved, if there are any issues moving data to the target cluster, and can deploy those changes.  By inspecting and analyzing the metadata, issues can be discovered early in the overall migration timeline.

- [Metadata Migration](#metadata-migration)
- [Run Metadata Migration](#run-metadata-migration)
  - [Metadata verification with evaluate command](#metadata-verification-with-evaluate-command)
  - [Metadata migration with migrate command](#metadata-migration-with-migrate-command)
  - [Metadata verification process](#metadata-verification-process)
- [How the tool works](#how-the-tool-works)
  - [Breaking change compatibility](#breaking-change-compatibility)
    - [Deprecation of Mapping Types](#deprecation-of-mapping-types)

## Run Metadata Migration

Metadata migration is part of the Migration Assistant and can be accessed through the migration console. This is the recommended way to run this tool.  If you are feeling adventurous, the tool can be run locally by following the instructions in the [DEVELOPER_GUIDE.md](./DEVELOPER_GUIDE.md).

Metadata migration is a relatively fast process to execute so we recommend attempting this workflow as quickly as possible to discover any issues which could impact longer running migration steps.

### Metadata verification with evaluate command

By scanning the contents of the source cluster, applying filtering, and applying modifications a list of all items that will be migrated will be created.  Any items not seen in this output will not be migrated onto the target cluster if the migrate command was to be run.  This is a safety check before making modifications on the target cluster.

```shell
console metadata evaluate
```

<details>
<summary>
Example evaluate command output
</summary>

```
Starting Metadata Evaluation
Clusters:
   Source:
      Remote Cluster: OpenSearch 1.3.16 ConnectionContext(uri=http://localhost:33039, protocol=HTTP, insecure=false, compressionSupported=false)

   Target:
      Remote Cluster: OpenSearch 2.14.0 ConnectionContext(uri=http://localhost:33037, protocol=HTTP, insecure=false, compressionSupported=false)


Migration Candidates:
   Index Templates:
      simple_index_template

   Component Templates:
      simple_component_template

   Indexes:
      blog_2023, movies_2023

   Aliases:
      alias1, movies-alias


Results:
   0 issue(s) detected
```
</details>

### Metadata migration with migrate command

Running through the same data as the evaluate command all of the migrated items will be applied onto the target cluster.  If re-run multiple times items that were previously migrated will not be recreated.  If any items do need to be remigrated, please delete them from the target cluster and then rerun the evaluate then migrate commands to ensure the desired changes are made.

```shell
console metadata migrate
```

<details>
<summary>
Example migrate command output
</summary>

```
Starting Metadata Migration

Clusters:
   Source:
      Snapshot: OpenSearch 1.3.16 FileSystemRepo(repoRootDir=/tmp/junit10626813752669559861)

   Target:
      Remote Cluster: OpenSearch 2.14.0 ConnectionContext(uri=http://localhost:33042, protocol=HTTP, insecure=false, compressionSupported=false)


Migrated Items:
   Index Templates:
      simple_index_template

   Component Templates:
      simple_component_template

   Indexes:
      blog_2023, movies_2023

   Aliases:
      alias1, movies-alias


Results:
   0 issue(s) detected
```
</details>

### Metadata verification process

Before moving on to additional migration steps, it is recommended to confirm details of your cluster.  Depending on your configuration, this could be checking the sharding strategy or making sure index mappings are correctly defined by ingesting a test document.

## How the tool works

This tool gathers information from a source cluster, through a snapshot or through HTTP requests against the source cluster.  These snapshots are fully compatible with Reindex-From-Snapshot (RFS) scenarios, [learn more](../DocumentsFromSnapshotMigration/README.md).

After collecting information on the source cluster comparisons are made on the target cluster.  If running a migration, any metadata items do not already exist will be created on the target cluster.

### Breaking change compatibility

Metadata migration needs to modify data from the source to the target versions to recreate items.  Sometimes these features are no longer supported and have been removed from the target version.  Sometimes these features are not available on the target version, which is especially true when downgrading.  While this tool is meant to make this process easier, it is not exhaustive in its support.  When encountering a compatibility issue or an important feature gap for your migration, please [search the issues](https://github.com/opensearch-project/opensearch-migrations/issues) and comment + upvote or a [create a new](https://github.com/opensearch-project/opensearch-migrations/issues/new/choose) issue if one cannot be found.

#### Deprecation of Mapping Types
In Elasticsearch 6.8 the mapping types feature was discontinued in Elasticsearch 7.0+ which has created complexity in migrating to newer versions of Elasticsearch and OpenSearch, [learn more](https://www.elastic.co/guide/en/elasticsearch/reference/7.17/removal-of-types.html).

As Metadata migration supports migrating from ES 6.8 on to the latest versions of OpenSearch this scenario is handled by removing the type mapping types and restructuring the template or index properties.  Note that, at the time of this writing multiple type mappings are not supported, [tracking task](https://opensearch.atlassian.net/browse/MIGRATIONS-1778).


**Example starting state with mapping type foo (ES 6):**
```json
{
  "mappings": [
    {
      "foo": {
        "properties": {
          "field1": { "type": "text" },
          "field2": { "type": "keyword" }
        }
      }
    }
  ]
}
```

**Example ending state with foo removed (ES 7):**
```json
{
  "mappings": {
    "properties": {
      "field1": { "type": "text" },
      "field2": { "type": "keyword" },
    }
  }
}
```

*Technical details are available, [view source code](../transformation/src/main/java/org/opensearch/migrations/transformation/rules/IndexMappingTypeRemoval.java).*