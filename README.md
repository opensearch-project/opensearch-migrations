# OpenSearch Migrations Engine

## Table of Contents
1. [Overview](#overview)
2. [Key Features](#key-features)
3. [Supported Versions and Platforms](#supported-versions-and-platforms)
4. [Issue Tracking](#issue-tracking)
5. [Project Structure](#project-structure)
6. [Documentation](#documentation)
7. [Getting Started](#getting-started)
    - [Local Deployment](#local-deployment)
    - [AWS Deployment](#aws-deployment)
8. [Development](#development)
    - [Prerequisites](#prerequisites)
    - [Building the Project](#building-the-project)
    - [Running Tests](#running-tests)
    - [Code Style](#code-style)
    - [Pre-commit Hooks](#pre-commit-hooks)
9. [Contributing](#contributing)
10. [Publishing](#publishing)
11. [Security](#security)
12. [License](#license)
13. [Acknowledgments](#acknowledgments)

## Overview

The OpenSearch Migrations Engine is a comprehensive set of tools designed to facilitate upgrades, migrations, and comparisons for OpenSearch and Elasticsearch clusters. This project aims to simplify the process of moving between different versions and platforms while ensuring data integrity and performance.

Here's an updated and simplified version of the **Key Features** section to improve clarity and readability:

---

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

## Supported Versions and Platforms

- **Tested Migration Paths**:
  - Elasticsearch 6.8 to OpenSearch 1.3, 2.14
  - Elasticsearch 7.10.2 to OpenSearch 1.3, 2.14
  - Elasticsearch 7.17 to OpenSearch 1.3, 2.14
  - OpenSearch 1.3 to OpenSearch 2.14

Note that testing is done on specific minor versions, but any minor versions within a listed major version are expected to work.

- **Platforms**:
  - Self-managed (cloud provider hosted)
  - Self-managed (on-premises)
  - Managed cloud offerings (e.g., Amazon OpenSearch, Amazon OpenSearch Serverless)

While untested, alternative cloud providers are expected to work.

## Issue Tracking

We encourage users to open bugs and feature requests in this GitHub repository. 

**Encountering a compatibility issue or missing feature?**

- [Search existing issues](https://github.com/opensearch-project/opensearch-migrations/issues) to see if it’s already reported. If it is, feel free to **upvote** and **comment**.
- Can’t find it? [Create a new issue](https://github.com/opensearch-project/opensearch-migrations/issues/new/choose) to let us know.

For issue prioritization and management, the migrations team uses Jira, but uses GitHub issues for community intake:

https://opensearch.atlassian.net/

## Project Structure

- [`CreateSnapshot`](CreateSnapshot/README.md): Tools for creating cluster snapshots.
- [`DocumentsFromSnapshotMigration`](DocumentsFromSnapshotMigration/README.md): Utilities for migrating documents from snapshots.
- [`MetadataMigration`](MetadataMigration/README.md): Core functionality for migrating cluster metadata.
- [`RFS`](RFS/README.md) (Reindex-From-Snapshot):
  - Migration utilities for document reindexing and metadata migration.
  - Includes tracing contexts for both document and metadata migrations.
- [`TrafficCapture`](TrafficCapture/README.md) (Capture-and-Replay): Projects for proxying, capturing, and replaying HTTP traffic.
- [`migrationConsole`](TrafficCapture/dockerSolution/src/main/docker/migrationConsole/README.md): A comprehensive CLI tool for executing the migration workflow.
  - [`console_api`](TrafficCapture/dockerSolution/src/main/docker/migrationConsole/console_api/README.md) (experimental): Django-based API for orchestrating migration tasks.
  - [`lib/console_link`](TrafficCapture/dockerSolution/src/main/docker/migrationConsole/lib/console_link/README.md): Core library for migration operations.
    - Provides CLI interface (`cli.py`) for user interactions.
    - Implements various middleware components for error handling, metadata management, metrics collection, and more.
    - Includes models for clusters, backfill operations, replay functionality, and other migration-related tasks.
  - Supports various migration scenarios including backfill, replay, and metrics collection.
  - Integrates with AWS services like ECS and CloudWatch for deployment and monitoring.
- [`deployment`](deployment/README.md): AWS deployment scripts and configurations.
- `dev-tools`: Development utilities and API request templates.
- `docs`: Project documentation and architecture diagrams.
- `libraries`: Shared libraries used across the project.
- [`test`](test/README.md): End-to-end testing scripts and configurations.
- `transformation`: Data transformation utilities for migration processes.
- [`dashboardsSanitizer`](dashboardsSanitizer/README.md): CLI tool for sanitizing dashboard configurations.
- `testHelperFixtures`: Test utilities including HTTP client for testing.

The migration console CLI provides users with a centralized interface to execute and manage the entire migration workflow, including:
- Configuring source and target clusters
- Managing backfill operations
- Controlling traffic replay
- Monitoring migration progress through metrics
- Handling snapshots and metadata
- Integrating with various deployment environments (Docker locally and AWS ECS)

Users can interact with the migration process through the CLI, which orchestrates the different components of the migration toolkit to perform a seamless migration between Elasticsearch and OpenSearch clusters.

## Documentation

User guide documentation is available in the [OpenSearch Migrations Wiki](https://github.com/opensearch-project/opensearch-migrations/wiki).

## Getting Started

### Local Deployment

For local development and testing, use the Docker solution:

 ```
 cd TrafficCapture/dockerSolution
 # Follow instructions in the README.md file
 ```

### AWS Deployment

To deploy the solution on AWS, follow the steps outlined in [Migration Assistant for Amazon OpenSearch Service](https://aws.amazon.com/solutions/implementations/migration-assistant-for-amazon-opensearch-service/), specifically [deploying the solution](https://docs.aws.amazon.com/solutions/latest/migration-assistant-for-amazon-opensearch-service/deploy-the-solution.html).


## Development

### Prerequisites

- Java Development Kit (JDK)
- Gradle
- Python
- Docker and Docker Compose (for local deployment)
- AWS CLI (for AWS deployment)
- CDK (for AWS deployment)
- Node (for AWS deployment)

### Building the Project

```bash
./gradlew build
```

### Running Tests

```bash
./gradlew test
```

### Continuous Integration/ Continuous Deployment
We use a combination of github actions and jenkins so that we can publish released on a weekly basis and allow users to provide attestation for users interested in migration tooling.

Jenkins pipelines are available [here](https://migrations.ci.opensearch.org/)


### Code Style

We use Spotless for code formatting. To check and apply the code style:

```bash
./gradlew spotlessCheck
./gradlew spotlessApply
```

### Pre-commit Hooks

Install the pre-commit hooks:

```bash
./install_githooks.sh
```

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details on our code of conduct and the process for submitting pull requests.

## Publishing

This project can be published to a local Maven repository with:
```sh
./gradlew publishToMavenLocal
```

And subsequently imported into a separate Gradle project with (replacing name with any subProject name)
```groovy
repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation group: "org.opensearch.migrations.trafficcapture", name: "captureKafkaOffloader", version: "0.1.0-SNAPSHOT"
    //... other dependencies
}
```

The entire list of published subprojects can be viewed with     
```sh
./gradlew listPublishedArtifacts
```


To include a test Fixture dependency, define the import like

```groovy
testImplementation testFixtures('org.opensearch.migrations.trafficcapture:trafficReplayer:0.1.0-SNAPSHOT')
```
## Security

See [SECURITY.md](SECURITY.md) for information about reporting security vulnerabilities.

## License

This project is licensed under the Apache-2.0 License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- OpenSearch Community
- Contributors and maintainers

For more detailed information about specific components, please refer to the README files in the respective directories.
