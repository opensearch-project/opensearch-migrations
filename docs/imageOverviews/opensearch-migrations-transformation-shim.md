## Quick Reference

- **Maintained by:** [OpenSearch team](https://github.com/opensearch-project)
- **Need help?** Ask questions and discuss on our [community forum](https://forum.opensearch.org/tag/migration)
- **Need to file issues?** Use our [issue tracker](https://github.com/opensearch-project/opensearch-migrations/issues) to report problems with Migration Assistant for OpenSearch or the Docker images

## What is the Migration Assistant for OpenSearch?

The Migration Assistant for OpenSearch is a tool that simplifies the migration of data from Elasticsearch to OpenSearch. It provides a comprehensive solution for migrating historical and/or live data.

Learn more at the documentation for the [Migration Assistant for OpenSearch](https://docs.opensearch.org/docs/latest/migration-assistant).

## What is OpenSearch Migrations Transformation Shim?

The OpenSearch Migrations Transformation Shim is a multi-target HTTP proxy component of the Migration Assistant for OpenSearch that sits between clients and backend search engines. It provides request/response transformation, parallel dispatch, and in-flight response validation to support phased migrations from Solr to OpenSearch. The shim can operate as a passthrough proxy, a protocol-translating transform proxy (e.g., Solr to OpenSearch), or a dual-target validation proxy that compares responses across backends.

## How to Pull This Image

You can pull the OpenSearch Migrations Transformation Shim Docker image just like any other image:

```bash
docker pull public.ecr.aws/opensearchproject/opensearch-migrations-transformation-shim:latest
```

See [ECR](https://gallery.ecr.aws/opensearchproject/opensearch-migrations-transformation-shim) for a list of all available versions.

## How to Use This Image

OpenSearch Migrations Transformation Shim is a component of the Migration Assistant for OpenSearch. We recommend following the instructions in the [OpenSearch documentation](https://docs.opensearch.org/docs/latest/migration-assistant) to get started.

## Licensing

OpenSearch Migrations Transformation Shim is licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).

## How to Contribute

OpenSearch Migrations Transformation Shim is open source under the Apache 2.0 license. We welcome contributions and strive to make it easy for you to get started—no lengthy Contributor License Agreement required. Visit the [OpenSearch website](https://opensearch.org) to learn more and join our community.
