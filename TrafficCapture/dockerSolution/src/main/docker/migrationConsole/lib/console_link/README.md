# Console_link Library

- [Services.yaml spec](#servicesyaml-spec)
    - [Cluster](#cluster)
    - [Metrics Source](#metrics-source)
    - [Backfill](#backfill)
        - [Reindex From Snapshot](#reindex-from-snapshot)
        - [OpenSearch Ingestion](#opensearch-ingestion)
    - [Snapshot](#snapshot)
    - [Metadata Migration](#metadata-migration)
    - [Replay](#replay)
    - [Kafka](#kafka)
- [Usage](#usage)
    - [Library](#library)
    - [CLI](#cli)
        - [Global Options](#global-options)
        - [Objects](#objects)
        - [Commands \& options](#commands--options)
- [Development](#development)
    - [Unit Tests](#unit-tests)
    - [Coverage](#coverage)

The console link library is designed to provide a unified interface for the many possible backend services involved in a migration. The interface can be used by multiple frontends--a CLI app and a web API, for instance.

![Console_link Library Diagram](console_library_diagram.svg)

The user defines their migration services in a `migration_services.yaml` file, by default found at `/etc/migration_services.yaml`.

Currently, the supported services are:

- `source_cluster`: Source cluster details
- `target_cluster`: Target cluster details
- `backfill`: Backfill migration details
- `snapshot`: Details for snapshot source/creation for backfill
- `metadata_migration`: Metadata migration source/config details
- `kafka`: Connection info for the Kafka cluster used in capture-replay
- `replay`: Replayer deployment and config details
- `metrics_source`: Metrics source details

For example:

```yaml
source_cluster:
    endpoint: "https://capture-proxy-es:9200"
    allow_insecure: true
    no_auth:
target_cluster:
    endpoint: "https://opensearchtarget:9200"
    allow_insecure: true
    basic_auth:
        username: "admin"
        password: "myStrongPassword123!"
metrics_source:
    prometheus:
        endpoint: "http://prometheus:9090"
backfill:
    opensearch_ingestion:
        pipeline_role_arn: "arn:aws:iam::123456789012:role/OSMigrations-aws-integ-us--pipelineRole"
        vpc_subnet_ids:
            - "subnet-123456789"
        security_group_ids:
            - "sg-123456789"
        aws_region: "us-west-2"
        pipeline_name: "test-cli-pipeline"
        index_regex_selection:
            - "test-index*"
        log_group_name: "/aws/vendedlogs/osi-aws-integ-default"
        tags:
            - "migration_deployment=1.0.6"
replay:
  ecs:
    cluster-name: "migrations-dev-cluster"
    service-name: "migrations-dev-replayer-service"
snapshot:
  snapshot_name: "snapshot_2023_01_01"
  s3:
      repo_uri: "s3://my-snapshot-bucket"
      aws_region: "us-east-2"
metadata_migration:
  from_snapshot:
  min_replicas: 0
kafka:
  broker_endpoints: "kafka:9092"
  standard:
```

## Services.yaml spec

### Cluster

Source and target clusters have the following options:

- `endpoint`: required, the endpoint to reach the cluster.
- `allow_insecure`: optional, default is false, equivalent to the curl `--insecure` flag, will not verify unsigned or invalid certificates

Exactly one of the following blocks must be present:

- `no_auth`: may be empty, no authorization to use.
- `basic_auth`:
    - `username`
    - `password` OR `password_from_secret_arn`
- `sigv4`: may be empty, not yet implemented

Within a `basic_auth` block, either a password can be provided directly, or it can contain the ARN of a secret in secrets manager which contains the password to use.

Having a `source_cluster` and `target_cluster` is required.

### Metrics Source

Currently, the two supported metrics source types are `prometheus` and `cloudwatch`.
Exactly one of the following blocks must be present:

- `prometheus`:
    - `endpoint`: required

- `cloudwatch`: may be empty if region is not specified
    - `aws_region`:  optional. if not provided, the usual rules are followed for determining aws region. (`AWS_DEFAULT_REGION`, `~/.aws/config`, etc.)

### Backfill

Backfill can be performed via several mechansims. The primary two supported by the console library are Reindex From Snapshot (RFS) and
OpenSearch Ingestion Pipeline (OSI).

#### Reindex From Snapshot

Depending on the purpose/deployment strategy, RFS can be used in Docker or on AWS in an Elastic Container Service (ECS) deployment.
Most of the parameters for these two are the same, with some additional ones specific to the deployment.

- `reindex_from_snapshot`
    - `snapshot_repo`: optional, path to the snapshot repo. If not provided, ???
    - `snapshot_name`: optional, name of the snapshot to use as the source. If not provided, ???
    - `scale`: optional int, number of instances to enable when `backfill start` is run. While running, this is modifiable with `backfill scale X`. Default is 1.

There is also a block that specifies the deployment type. Exactly one of the following blocks must be present:

- `docker`:
    - `socket`: optional, path to mounted docker socket, defaults to `/var/run/docker.sock`

- `ecs`:
    - `cluster_name`: required, name of the ECS cluster containing the RFS service
    - `service_name`: required, name of the ECS service for RFS
    - `aws_region`:  optional. if not provided, the usual rules are followed for determining aws region. (`AWS_DEFAULT_REGION`, `~/.aws/config`, etc.)

Both of the following are valid RFS backfill specifications:

```yaml
backfill:
    reindex_from_snapshot:
        docker:
```

```yaml
backfill:
    reindex_from_snapshot:
        snapshot_repo: "abc"
        snapshot_name: "def"
        scale: 3
        ecs:
            cluster_name: migration-aws-integ-ecs-cluster
            service_name: migration-aws-integ-reindex-from-snapshot
            aws-region: us-east-1
```

#### OpenSearch Ingestion

- `opensearch_ingestion`
    - `pipeline_role_arn`: required, IAM pipeline role containing permissions to read from source and read/write to target, more details [here](https://docs.aws.amazon.com/opensearch-service/latest/developerguide/pipeline-security-overview.html#pipeline-security-sink)
    - `vpc_subnet_ids`: required, VPC subnets to place the OSI pipeline in
    - `security_group_ids`: required, security groups to apply to OSI pipeline for accessing source and target clusters
    - `aws_region`: required, AWS region to look for pipeline role and secrets for cluster
    - `pipeline_name`: optional, name of OSI pipeline
    - `index_regex_selection`: optional, list of index inclusion regex strings for selecting indices to migrate
    - `log_group_name`: optional, name of existing CloudWatch log group to use for OSI logs
    - `tags`: optional, list of tags to apply to OSI pipeline

### Snapshot

The snapshot configuration specifies a local filesystem or an s3 snapshot that will be created by the `snapshot create` command, and (if not overridden) used as the source for the `metadata migrate` command. In a docker migration, it may also be used as the source for backfill via reindex-from-snapshot. If metadata migration and reindex-from-snapshot are not being used, this block is optional.

- `snapshot_name`: required, name of the snapshot

Exactly one of the following blocks must be present:

- `s3`:
    - `repo_uri`: required, `s3://` path to where the snapshot repo exists or should be created (the bucket must already exist, and the repo needs to be configured on the source cluster)
    - `aws_region`: required, region for the s3 bucket

- `fs`:
    - `repo_path`: required, path to where the repo exists or should be created on the filesystem (the repo needs to be configured on the source cluster).

### Metadata Migration

The metadata migration moves indices, components, and templates from a snapshot to the target cluster. In the future, there may be a `from_live_cluster` option, but currently all metadata migration must originate from a snapshot. A snapshot can be created via `console snapshot create` or can be pre-existing. The snapshot details are independently defineable, so if a special case is necessary, a snapshot could theoretically be created and used for document, but metadata migration could operate from a separate, pre-existing snapshot. This block is optional if metadata migration isn't being used as part of the migration.

- `min_replicas`: optional, an integer value for the number of replicas to create. The default value is 0.
- `index_allowlist`: optional, a list of index names. If this key is provided, only the named indices will be migrated. If the field is not provided, all non-system indices will be migrated.
- `index_template_allowlist`: optional, a list of index template names. If this key is provided, only the named templates will be migrated. If the field is not provided, all templates will be migrated.
- `component_template_allowlist`: optional, a list of component template names. If this key is provided, only the named component templates will be migrated. If the field is not provided, all component templates will be migrated.
- `from_snapshot`: required. As mentioned above, `from_snapshot` is the only allowable source for a metadata migration at this point. This key must be present, but if it's value is null/empty, the snapshot details will be pulled from the top-level `snapshot` object. If a `snapshot` object does not exist, this block must be populated.
    - `snapshot_name`: required, as described in the Snapshot section
    - `s3` or `fs` block: exactly one must be present, as described in the Snapshot section
    - `local_dir`: optional, specifies a location on the local filesystem for s3 snpashot files to be downloaded to. If not specified, a temp directory will be created and used.

### Replay

If capture and replay is included in the migration, the replay block defines where it is deployed and some run config details.

- `scale`: optional int, number of instances to enable when `replay start` is run. While running, this is modifiable with `replay scale X`. Default is 1.

Exactly one of the following blocks must be present:

- `docker`:
    - `socket`: optional, path to mounted docker socket, defaults to `/var/run/docker.sock`

- `ecs`:
    - `cluster_name`: required, name of the ECS cluster containing the replayer service
    - `service_name`: required, name of the ECS replayer service
    - `aws_region`:  optional. if not provided, the usual rules are followed for determining aws region. (`AWS_DEFAULT_REGION`, `~/.aws/config`, etc.)

### Kafka

A Kafka cluster is used in the capture and replay stage of the migration to store recorded requests and responses before they're replayed. While it's not necessary for a user to directly interact with the Kafka cluster in most cases, there are a handful of commands that can be helpful for checking on the status or resetting state that are exposed by the Console CLI.

- `broker_endpoints`: required, comma-separated list of kafaka broker endpoints

Exactly one of the following keys must be present, but both are nullable (they don't have or need any additional parameters).

- `msk`: the Kafka instance is deployed as AWS Managed Service Kafka
- `standard`: the Kafka instance is deployed as a standard Kafka cluster (e.g. on Docker)

## Usage

### Library

The library can be imported and used within another application.
Use `pip install .` from the top-level `console_link` directory to install it locally and then import it as, e.g. `from console_link.models.metrics_source import MetricsSource`

### CLI

The CLI comes installed on the migration console. If you'd like to install it elsewhere, `pip install .` from the top-level `console_link` directory will install it and setup a `console` executable to access it.

Autocomplete can be enabled by adding `eval "$(_CONSOLE_COMPLETE=bash_source console)"` to your `.bashrc` file, or `eval "$(_FOO_BAR_COMPLETE=zsh_source foo-bar)"` to your `.zshrc` and re-sourcing your shell.

The structure of cli commands is:
`console [--global-options] OBJECT COMMAND [--options]`

#### Global Options

The available global options are:

- `--config-file FILE` to specify the path to a config file (default is `/etc/migration_services.yaml`)
- `--json` to get output in JSON designed for machine consumption instead of printing to the console

#### Objects

Currently, the two objects available are `cluster` and `metrics`.

#### Commands & options

Each object has its own commands available, and each command has its own options. To see the available commands and options, use:

```sh
console OBJECT --help
```

## Development

To install the library for development purposes, create a virtual env and install the library. It will automatically install its dependencies as well.

```shell
pipenv install
```

### Unit Tests

Unit tests can be run from this current `console_link/` by first installing dependencies then running pytest:

```shell
pipenv install --dev
pipenv run coverage run -m pytest
```

### Coverage

_Code coverage_ metrics can be generated after a unit-test run. A report can either be printed on the command line:

```shell
pipenv run coverage report
```

or generated as HTML:

```shell
pipenv run coverage html
```
