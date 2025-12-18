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
    - [Performance Limitations](#performance-limitations)
      - [Test Results](#test-results)
  - [Issue Tracking](#issue-tracking)
  - [User Guide Documentation](#user-guide-documentation)
  - [Getting Started](#getting-started)
    - [Local Deployment](#local-deployment)
    - [AWS Deployment](#aws-deployment)
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
  - **[AWS Deployment](https://aws.amazon.com/solutions/implementations/migration-assistant-for-amazon-opensearch-service/)**: Fully automated deployment to AWS.
  - **[Local Docker Deployment](/TrafficCapture/dockerSolution/README.md)**: Run the solution locally in a container for testing and development.
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
<tr><td>Elasticsearch 1.x</td><td>✅</td><td>✅</td><td>✅</td></tr>
<tr><td>Elasticsearch 2.x</td><td>✅</td><td>✅</td><td>✅</td></tr>
<tr><td>Elasticsearch 5.x</td><td>✅</td><td>✅</td><td>✅</td></tr>
<tr><td>Elasticsearch 6.x</td><td>✅</td><td>✅</td><td>✅</td></tr>
<tr><td>Elasticsearch 7.x</td><td>✅</td><td>✅</td><td>✅</td></tr>
<tr><td>Elasticsearch 8.x</td><td></td><td>✅</td><td>✅</td></tr>
<tr><td>OpenSearch 1.x</td><td>✅</td><td>✅</td><td>✅</td></tr>
<tr><td>OpenSearch 2.x</td><td></td><td>✅</td><td>✅</td></tr>
<tr><td>OpenSearch 3.x</td><td></td><td></td><td>🔜 <a href="https://github.com/orgs/opensearch-project/projects/229?pane=issue&itemId=117495207">link</a></td></tr>
</table>

Note that testing is done on specific minor versions, but any minor versions within a listed major version are expected to work.

### Platforms
  - Self-managed (cloud provider hosted)
  - Self-managed (on-premises)
  - Managed cloud offerings (e.g., Amazon OpenSearch)

### Performance Limitations
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

## User Guide Documentation

User guide documentation is available in the [OpenSearch Migration Assistant documentation](https://docs.opensearch.org/latest/migration-assistant/).

## Getting Started

### Local Deployment

 Refer to the [Development Guide](DEVELOPER_GUIDE.md) for more details.

### AWS Deployment

To deploy the solution on AWS, follow the steps outlined in [Migration Assistant for Amazon OpenSearch Service](https://aws.amazon.com/solutions/implementations/migration-assistant-for-amazon-opensearch-service/), specifically [deploying the solution](https://docs.aws.amazon.com/solutions/latest/migration-assistant-for-amazon-opensearch-service/deploy-the-solution.html).


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
