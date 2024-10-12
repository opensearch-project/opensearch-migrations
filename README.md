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

## Key Features

- **Upgrade and Migration Support**: Tools for migrating between Elasticsearch and OpenSearch versions.
  - **[Metadata Migration](MetadataMigration/README.md)**: Utilities for migrating cluster metadata, including cluster configuration, settings, templates, and aliases.
  - **Multi-version hop**: Jump more than one major Version (For example, from Elasticsearch 6.8 to OpenSearch 2.15), reducing effort and risk by bypassing the sequential major version upgrade requirements of snapshot/restore and rolling upgrades.
  - **Downgrade Support**: Move from a more recent version (for example, Elasticsearch 7.17 to Elasticsearch 7.10.2)
  - **Existing Data Migration with [Reindex-from-Snapshot](RFS/docs/DESIGN.md)**: Tools for migrating existing indices and documents from snapshots.
  - **Live Traffic Capture/Change Data Capture with [Capture-and-Replay](TrafficCaptureAndReplayDesign.md)**: Change data capture, or live capture tooling to capture source cluster traffic and replay it on a target cluster for comparison.
- **Zero-downtime with [live traffic routing options](docs/ClientTrafficSwinging.md)**: Load balancer tooling to faciliate client traffic switchover while maintaining service availability.
- **Back-out-of-migration capabilities**: This solution is designed to synchronize source and target clusters, leaving the source cluster intact while exact traffic patterns can be replicated to target cluster. The target cluster performance and behavior can be analyzed before switching over client traffic.
- **User Interface via a [Migration Console](https://github.com/opensearch-project/opensearch-migrations/blob/main/docs/migration-console.md)**: Command Line Interface (CLI) used to facilitate the steps in a migration.
- **Local and Cloud Deployment Options**
  - **[AWS Deployment](https://aws.amazon.com/solutions/implementations/migration-assistant-for-amazon-opensearch-service/)**: Automated cloud deployment to AWS.
  - **[Local Docker Deployment](/TrafficCapture/dockerSolution/README.md)**: Containerized solution for local testing and development.

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

We encourage users to open bugs and feature requests in this GitHub repository. For issue prioritization and management, the migrations team uses Jira:

https://opensearch.atlassian.net/

## Project Structure

- `CreateSnapshot`: Tools for creating cluster snapshots.
- `DocumentsFromSnapshotMigration`: Utilities for migrating documents from snapshots.
- `MetadataMigration`: Core functionality for migrating cluster metadata.
- `RFS` (Reindex-From-Snapshot):
  - Migration utilities for document reindexing and metadata migration.
  - Includes tracing contexts for both document and metadata migrations.
- `TrafficCapture`: Projects for proxying, capturing, and replaying HTTP traffic.
- `migrationConsole`: A comprehensive CLI tool for executing the migration workflow.
  - `console_api`: Django-based API for orchestrating migration tasks.
  - `lib/console_link`: Core library for migration operations.
    - Provides CLI interface (`cli.py`) for user interactions.
    - Implements various middleware components for error handling, metadata management, metrics collection, and more.
    - Includes models for clusters, backfill operations, replay functionality, and other migration-related tasks.
  - Supports various migration scenarios including backfill, replay, and metrics collection.
  - Integrates with AWS services like ECS and CloudWatch for deployment and monitoring.
- `deployment`: AWS deployment scripts and configurations.
- `dev-tools`: Development utilities and API request templates.
- `docs`: Project documentation and architecture diagrams.
- `libraries`: Shared libraries used across the project.
- `test`: End-to-end testing scripts and configurations.
- `transformation`: Data transformation utilities for migration processes.
- `dashboardsSanitizer`: CLI tool for sanitizing dashboard configurations.
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
- Docker and Docker Compose (for local deployment)
- AWS CLI (for AWS deployment)

### Building the Project

```bash
./gradlew build
```

### Running Tests

```bash
./gradlew test
```

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