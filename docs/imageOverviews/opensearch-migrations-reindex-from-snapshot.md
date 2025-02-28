## Quick Reference

- **Maintained by:** [OpenSearch team](https://github.com/opensearch-project)
- **Need help?** Ask questions and discuss on our [community forum](https://forum.opensearch.org/tag/migration)
- **Need to file issues?** Use our [issue tracker](https://github.com/opensearch-project/opensearch-migrations/issues) to report problems with Migration Assistant for Amazon OpenSearch or the Docker images

## What is the Migration Assistant for Amazon OpenSearch?

The Migration Assistant for Amazon OpenSearch is a tool that simplifies the migration of data from Elasticsearch to OpenSearch. It provides a comprehensive solution for migrating historical and/or live data.

Learn more about the Migration Assistant for Amazon OpenSearch in the [Amazon Solutions page](https://aws.amazon.com/solutions/implementations/migration-assistant-for-amazon-opensearch-service/).

## What is OpenSearch Migrations Reindex-from-Snapshot?

The OpenSearch Migrations Reindex-from-Snapshot is the component of the Migration Assistant for Amazon OpenSearch that reads an Elasticsearch snapshot, extracts the source docs, and reindexes it into a target cluster.

## How to Pull This Image

You can pull the OpenSearch Migrations Reindex-from-Snapshot Docker image just like any other image:

```bash
docker pull public.ecr.aws/opensearchproject/opensearch-migrations-reindex-from-snapshot:latest
```

See [ECR](https://gallery.ecr.aws/opensearchproject/opensearch-migrations-reindex-from-snapshot) for a list of all available versions.

## How to Use This Image

OpenSearch Migration Reindex-from-Snapshot is a component of the Migration Assistant for Amazon OpenSearch. We recommend following the instructions in the [wiki documentation](https://github.com/opensearch-project/opensearch-migrations/wiki) to get started.

## Licensing

OpenSearch Migration Reindex-from-Snapshot is licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).

## How to Contribute

OpenSearch Migration Reindex-from-Snapshot is open source under the Apache 2.0 license. We welcome contributions and strive to make it easy for you to get started—no lengthy Contributor License Agreement required. Visit the [OpenSearch website](https://opensearch.org) to learn more and join our community.
