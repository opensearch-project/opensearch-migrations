# OpenSearch Migrations Engine Test

## Table of Contents
1. [Overview](#overview)
2. [Key Features](#key-features)
3. [Supported Versions and Platforms](#supported-versions-and-platforms)
4. [Issue Tracking](#issue-tracking)
5. [User Guide Documentation](#user-guide-documentation)
6. [Getting Started](#getting-started)
   - [Local Deployment](#local-deployment)
   - [AWS Deployment](#aws-deployment)
7. [Continuous Integration and Deployment](#continuous-integration-and-deployment)
8. [Contributing](#contributing)
9. [Security](#security)
10. [License](#license)
11. [Acknowledgments](#acknowledgments)


## Overview

The OpenSearch Migrations Engine is a comprehensive set of tools designed to facilitate upgrades, migrations, and comparisons for OpenSearch and Elasticsearch clusters. This project aims to simplify the process of moving between different versions and platforms while ensuring data integrity and performance.

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

| **Source Version**          | **Target Version**               | Status|
|-----------------------------|----------------------------------|---------|
| Elasticsearch 6.8           | OpenSearch 1.3                   |Supported|
| Elasticsearch 6.8           | OpenSearch 2.14                  |Supported|
| Elasticsearch 7.10.2        | OpenSearch 1.3                   |Supported|
| Elasticsearch 7.10.2        | OpenSearch 2.14                  |Supported|
| Elasticsearch 7.17          | OpenSearch 1.3                   |Supported|
| Elasticsearch 7.17          | OpenSearch 2.14                  |Supported|
| OpenSearch 1.3              | OpenSearch 2.14                  |Supported|
| Elasticsearch 8.x           | OpenSearch 2.x                   |[Prioritized](https://github.com/opensearch-project/opensearch-migrations/issues/1071)|
| Elasticsearch 5.6           | OpenSearch 2.x                   |[Prioritized](https://github.com/opensearch-project/opensearch-migrations/issues/1067)|
| Elasticsearch 2.3           | OpenSearch 2.<latest>            |[Prioritized](https://github.com/opensearch-project/opensearch-migrations/issues/1069)|
| Elasitcsearch 1.5           | OpenSearch 2.x .                 |[Prioritized](https://github.com/opensearch-project/opensearch-migrations/issues/1070)|
| OpenSearch 2.x              | OpenSearch 2.x .                  |[Requested](https://github.com/opensearch-project/opensearch-migrations/issues/1038)|

Note that testing is done on specific minor versions, but any minor versions within a listed major version are expected to work.

### Platforms
  - Self-managed (cloud provider hosted)
  - Self-managed (on-premises)
  - Managed cloud offerings (e.g., Amazon OpenSearch, Amazon OpenSearch Serverless)

## Issue Tracking

We encourage users to open bugs and feature requests in this GitHub repository. 

**Encountering a compatibility issue or missing feature?**

- [Search existing issues](https://github.com/opensearch-project/opensearch-migrations/issues) to see if it’s already reported. If it is, feel free to **upvote** and **comment**.
- Can’t find it? [Create a new issue](https://github.com/opensearch-project/opensearch-migrations/issues/new/choose) to let us know.

For issue prioritization and management, the migrations team uses Jira, but uses GitHub issues for community intake:

https://opensearch.atlassian.net/

## User Guide Documentation

User guide documentation is available in the [OpenSearch Migrations Wiki](https://github.com/opensearch-project/opensearch-migrations/wiki).

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
