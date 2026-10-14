# OpenSearch Migration Assistant

[![codecov](https://codecov.io/gh/opensearch-project/opensearch-migrations/graph/badge.svg)](https://codecov.io/gh/opensearch-project/opensearch-migrations)

## Table of Contents
- [OpenSearch Migration Assistant](#opensearch-migration-assistant)
  - [Table of Contents](#table-of-contents)
  - [Overview](#overview)
  - [Key Features](#key-features)
  - [Supported Migration Paths and Platforms](#supported-migration-paths-and-platforms)
    - [Migration Paths](#migration-paths)
    - [Platforms](#platforms)
  - [Performance](#performance)
    - [Capture and Replay](#capture-and-replay)
    - [Reindex-From-Snapshot and Traffic Replay throughput](#reindex-from-snapshot-and-traffic-replay-throughput)
      - [Test Results](#test-results)
  - [Issue Tracking](#issue-tracking)
  - [Roadmap](#roadmap)
  - [User Guide Documentation](#user-guide-documentation)
  - [Getting Started](#getting-started)
    - [Local Deployment](#local-deployment)
    - [AWS Deployment](#aws-deployment)
    - [GCP Deployment](#gcp-deployment)
  - [Continuous Integration and Deployment](#continuous-integration-and-deployment)
  - [Contributing](#contributing)
  - [Security](#security)
  - [License](#license)
  - [Acknowledgments](#acknowledgments)


## Overview

OpenSearch Migration Assistant is a comprehensive set of tools designed to facilitate upgrades, migrations, and comparisons for OpenSearch and Elasticsearch clusters. This project aims to simplify the process of moving between different versions and platforms while ensuring data integrity and performance.

## Key Features

- **Upgrade and Migration Support**: Provides tools for migrating between different versions of Elasticsearch and OpenSearch.
  - **[Metadata Migration](MetadataMigration/README.md)**: Migrate essential cluster components such as configuration, settings, templates, and aliases.
  - **Multi-Version Upgrade**: Easily migrate across major versions (e.g., from Elasticsearch 6.8 to OpenSearch 2.15), skipping intermediate upgrades and reducing time and risk.
  - **Downgrade Support**: Downgrade to an earlier version if needed (e.g., from Elasticsearch 7.17 to 7.10.2).
  - **Existing Data Migration with [Reindex-from-Snapshot](RFS/docs/DESIGN.md)**: Migrate indices and documents using snapshots, updating your data to the latest Lucene version quickly without impacting the target cluster.
  - **Live Traffic Capture with [Capture-and-Replay](docs/TrafficCaptureAndReplayDesign.md)**: Capture live traffic from the source cluster and replay it on the target cluster for validation. This ensures the target cluster can handle real-world traffic patterns before fully migrating.
  
- **Zero-Downtime Migration with [Live Traffic Routing](docs/ClientTrafficSwinging.md)**: Tools to seamlessly switch client traffic between clusters while keeping services fully operational.

- **Migration Rollback**: Keep your source cluster synchronized during the migration, allowing you to monitor the target cluster's performance before fully committing to the switch. You can safely revert if needed.

- **User-Friendly Interface via [Migration Console](https://github.com/opensearch-project/opensearch-migrations/blob/main/docs/migration-console.md)**: Command Line Interface (CLI) that guides you through each migration step.

- **Flexible Deployment Options**:
  - **[AWS Deployment](deployment/terraform/aws/README.md)**: Provision EKS Auto Mode infrastructure with Terraform, or use the existing [AWS Solution CloudFormation deployment](https://aws.amazon.com/solutions/implementations/migration-assistant-for-amazon-opensearch-service/).
  - **[GCP Deployment](deployment/terraform/gcp/README.md)**: Terraform module that provisions a GKE cluster, GCS snapshot bucket, and the Workload Identity bindings required by the migration workflows, deployed via the [GKE Helm overlay](deployment/k8s/charts/aggregates/migrationAssistantWithArgo/valuesGke.yaml).
  - **[Local Docker Deployment](./TrafficCapture/dockerSolution/README.md)**: Run the solution locally in a container for testing and development.
  - Contribute to add more deployment options.

## Supported Migration Paths and Platforms

### Migration Paths

<table>
<tr>
  <th rowspan="2">Source Cluster</th>
  <th colspan="3">Target Cluster</th>
</tr>
<tr>
  <th>OpenSearch 1.x</th>
  <th>OpenSearch 2.x</th>
  <th>OpenSearch 3.x</th>
</tr>
<tr><td>Elasticsearch 1.x–2.x*</td><td>✅</td><td>✅</td><td>✅</td></tr>
<tr><td>Elasticsearch 5.x–7.x</td><td>✅</td><td>✅</td><td>✅</td></tr>
<tr><td>Elasticsearch 8.x</td><td></td><td>✅</td><td>✅</td></tr>
<tr><td>OpenSearch 1.x–2.x</td><td></td><td>✅</td><td>✅</td></tr>
<tr><td>Apache Solr 6.x–9.x*</td><td></td><td></td><td>✅</td></tr>
</table>

\* Backfill only — Capture and Replay is not supported for these source versions. See
[Solr Backup Layouts](docs/SolrBackupLayouts.md) for the supported backup directory structures
(no manual reshaping required).

Note that testing is done on specific minor versions, but any minor versions within a listed major version are expected to work.

### Platforms
  - Self-managed (cloud provider hosted)
  - Self-managed (on-premises)
  - Managed cloud offerings (e.g., Amazon OpenSearch)
  - Managed serverless cloud offerings (e.g. Amazon OpenSearch Serverless)

## Performance

### Capture and Replay
A two-hour benchmark (see [details](docs/captureAndReplay/loadTestRun-2026-10-13-160k.md)) recorded a full-rate ingest workload as it was written to an
Elasticsearch 7.10 source and replayed it live onto an OpenSearch 3.7 target, both Amazon OpenSearch
Service domains. The load was single-document `_bulk` requests of about 2 KiB, with about a quarter of 
them opening a new TLS connection.

| What to expect                              | Result                                                                          |
| ------------------------------------------- | ------------------------------------------------------------------------------- |
| Sustained live capture                      | ~152K requests/s for two hours (every minute between 149K and 156K)             |
| Sustained live replay                       | ~154K target requests/s at speedup 1.1, keeping pace with capture               |
| Replay lag behind live traffic              | 0.26 s median minute, 6.9 s 95th percentile; caught up within a minute of the load stopping |
| Status codes matching between source and target | 99.995% of 1.1 billion requests                                             |
| Restarts during the run                     | none                                                                            |
| Capture proxies                             | 128 × 5 vCPU                                                                    |
| Kafka                                       | 12 brokers × 12 vCPU, 512 partitions, 3 TiB provisioned-throughput gp3 each     |
| Traffic Replayers                           | 64 × 8 vCPU                                                                     |
| Source and target domains (each)            | 48 data nodes (c8g.4xlarge) + 48 coordinators (c8g.8xlarge); ~33% coordinator CPU |

See the [full benchmark report](docs/captureAndReplay/loadTestRun-2026-10-13-160k.md) for the workload,
configuration, per-minute statistics, errors and retries, and the settings this rate depends on.

### Reindex-From-Snapshot and Traffic Replay throughput
A performance test was performed on 03/10/25 alongside [PR 1337](https://github.com/opensearch-project/opensearch-migrations/pull/1337)
to identify general indexing throughput with Reindex-From-Snapshot and Traffic Replay. While Reindex-From-Snapshot
includes periods of ingestion inactivity (e.g. while downloading the shard data), this was not factored into the ingestion rates.
Test used docs with an average uncompressed size 2.39 KB (source doc and index command) which corresponded to 1.54 KB of primary shard size per doc on OS 2.17 with default settings

For real-word use, throughput can be increased by vertically scaling Reindex-From-Snapshot and Traffic Replay instances or horizontally scaling
the Reindex-From-Snapshot workers that are running on AWS Fargate. Outside the exception indicated, all cases are CPU limited for throughput.
All cases were using uncompressed network traffic generated by the applications, results will vary if using client compression.

Throughput here is measured by the rate of increase in primary shard data with bulk ingestion on the target cluster alongside
the uncompressed size of the source data ingested.

Tests were ran with and without the [Type Mapping Sanitization Transformer](./transformation/transformationPlugins/jsonMessageTransformers/jsonTypeMappingsSanitizationTransformer/README.md).

#### Test Results
| Service               | vCPU | Memory (GB) | Type Mapping Sanitization | Peak Docs Ingested per minute | Primary Shard Data Ingestion Rate (MBps) | Uncompressed Source Data Ingestion Rate (MBps) |
| --------------------- | ---- | ----------- | ------------------------- | ----------------------------- | ---------------------------------------- | ---------------------------------------------- |
| Reindex-From-Snapshot | 2    | 4           | Disabled                  | 590,000                       | 15.1                                     | 23.5                                           |
| Reindex-From-Snapshot | 2    | 4           | Enabled                   | 546,000                       | 14.0                                     | 21.7                                           |
| Traffic Replay        | 8    | 48          | Disabled                  | 1,694,000 *[1]*               | 43.5  *[1]*                              | 67.5   *[1]*                                   |
| Traffic Replay        | 8    | 48          | Enabled                   | 1,645,000                     | 42.2                                     | 65.5                                           |

**[1] Network Bandwidth Limitations Observed**

## Issue Tracking

We encourage users to open bugs and feature requests in this GitHub repository. 

**Encountering a compatibility issue or missing feature?**

- [Search existing issues](https://github.com/opensearch-project/opensearch-migrations/issues) to see if it’s already reported. If it is, feel free to **upvote** and **comment**.
- Can’t find it? [Create a new issue](https://github.com/opensearch-project/opensearch-migrations/issues/new/choose) to let us know.

For issue prioritization and management, the migrations team uses Jira, but uses GitHub issues for community intake:

https://opensearch.atlassian.net/

## Roadmap

For upcoming features, enhancements, and priorities, check out the [OpenSearch Migrations Project Board](https://github.com/orgs/opensearch-project/projects/229/views/1).

## User Guide Documentation

User guide documentation is available in the [OpenSearch Migration Assistant documentation](https://docs.opensearch.org/latest/migration-assistant/).

## Getting Started

### Local Deployment

 Refer to the [Development Guide](DEVELOPER_GUIDE.md) for more details.

### AWS Deployment

To deploy EKS infrastructure on AWS with Terraform, see the [AWS Terraform README](deployment/terraform/aws/README.md). The existing CloudFormation-based AWS Solution remains available through [Migration Assistant for Amazon OpenSearch Service](https://aws.amazon.com/solutions/implementations/migration-assistant-for-amazon-opensearch-service/) and its [deployment guide](https://docs.aws.amazon.com/solutions/latest/migration-assistant-for-amazon-opensearch-service/deploy-the-solution.html).

### GCP Deployment

To deploy the solution on Google Cloud, use the GCP Terraform module to provision a GKE cluster with GCS-backed snapshots via Workload Identity. See the [GCP Terraform README](deployment/terraform/gcp/README.md) for full instructions, variables, and sizing guidance.


## Continuous Integration and Deployment
We use a combination of GitHub actions and Jenkins so that we can publish releases on a weekly basis and allow users to provide attestation for migration tooling.

Jenkins pipelines are available [here](https://migrations.ci.opensearch.org/)

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details on our code of conduct and the process for submitting pull requests.

Please refer to the [Development Guide](DEVELOPER_GUIDE.md) for details on building and deploying.

## Security

See [SECURITY.md](SECURITY.md) for information about reporting security vulnerabilities.

## License

This project is licensed under the Apache-2.0 License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- OpenSearch Community
- Contributors and maintainers

For more detailed information about specific components, please refer to the README files in the respective directories.
