# Development Guide

## Table of Contents
- [Table of Contents](#table-of-contents)
- [Prerequisites](#prerequisites)
- [Quick Start](#kubernetes-quick-start)
- [Project Structure](#project-structure)
- [Building the Project](#building-the-project)
- [Running Tests](#running-tests)
- [Code Style](#code-style)
- [Pre-Commit Hooks](#pre-commit-hooks)
- [Publishing](#publishing)
- [Development Environments](#development-environments)
  - [VSCode](#vscode)
    - [Python](#python)

## Prerequisites

- Java Development Kit (JDK) 11-17
- Python3
- Docker/Minikube/K3s/etc (for local deployment)
- [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html#getting-started-install-instructions) (for AWS deployment)
- Node.js v22 (downloaded automatically by Gradle)
- [AWS Cloud Development Kit (CDK)](https://docs.aws.amazon.com/cdk/v2/guide/getting_started.html) (for AWS deployment, downloaded automatically by Gradle)

## Kubernetes Quick Start

* This [Kubernetes Guide](deployment/k8s/README.md) shows you how to create a minikube cluster locally and deploy the Migration Assistant to it.
* This [AWS EKS Guide](deployment/k8s/aws/README.md) shows you how to deploy an EKS cluster and deploy the Migration Assistant to it.

See the [project wiki](https://github.com/opensearch-project/opensearch-migrations/wiki)
to learn more about how to use the migration console and its workflow commands.

## Project Structure

- [`CreateSnapshot`](CreateSnapshot/README.md): Tools for creating cluster snapshots.
- [`DocumentsFromSnapshotMigration`](DocumentsFromSnapshotMigration/README.md): Utilities for migrating documents from snapshots.
- [`MetadataMigration`](MetadataMigration/README.md): Core functionality for migrating cluster metadata.
- [`RFS`](RFS/README.md) (Reindex-From-Snapshot):
  - Migration utilities for document reindexing and metadata migration.
  - Includes tracing contexts for both document and metadata migrations.
- [`TrafficCapture`](TrafficCapture/README.md) (Capture-and-Replay): Projects for proxying, capturing, and replaying HTTP traffic.
- [`migrationConsole`](migrationConsole/README.md): A comprehensive CLI tool for executing the migration workflow.
  - [`lib/console_link`](migrationConsole/lib/console_link/README.md): Core library for migration operations.
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
- Integrating with various deployment environments (Docker locally, AWS ECS, EKS, K8s)

Users can interact with the migration process through the CLI, which orchestrates the different components of the migration toolkit to perform a seamless migration between Elasticsearch and OpenSearch clusters.

## Building the Project

```bash
./gradlew build
```

## Running Tests

```bash
./gradlew test
```

## Building Images

Build images with docker (requires docker)

```bash
../gradlew buildDockerImages
```

Build images with buildkit and jib. 
See [buildImages](buildImages/README-K8s.md) for instructions to set 
that up.

```bash
../gradlew :buildImages:buildImagesToRegistry
```

## Running the Project

* Running the project in [Kubernetes](deployment/k8s/README.md) 
* Running the legacy solution with [Docker Compose](TrafficCapture/dockerSolution/README.md)

## Code Style

We use Spotless for code formatting. To check and apply the code style:

```bash
./gradlew spotlessCheck
./gradlew spotlessApply
```

## Pre-Commit Hooks

Install the pre-commit hooks:

```bash
./install_githooks.sh
```

## Publishing Images

This project can be published to a local Maven repository with:
```sh
./gradlew publishToMavenLocal
```

And subsequently imported into a separate Gradle project with (replacing `name` with any subProject name):
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

The entire list of published subprojects can be viewed as follows:     
```sh
./gradlew listPublishedArtifacts
```


To include a test Fixture dependency, define the import similar to the following:

```groovy
testImplementation testFixtures('org.opensearch.migrations.trafficcapture:trafficReplayer:0.1.0-SNAPSHOT')
```

## Development Environments

### VSCode

#### Python

Settings.json files are already set up in the project, make sure that venv environments are created in all folders with pipfile.  Bootstrap your environment by running the following command that creates all the environments.

```bash
find . -name Pipfile -not -path "*/cdk.out/*"  | while read pipfile; do
  dir=$(dirname "$pipfile")
  echo "Setting up .venv in $dir"
  (cd "$dir" && PIPENV_IGNORE_VIRTUALENVS=1 PIPENV_VENV_IN_PROJECT=1 pipenv install)
done
```