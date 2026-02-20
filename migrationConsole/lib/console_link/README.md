# Console_link Library

- [Console\_link Library](#console_link-library)
  - [Services.yaml spec](#servicesyaml-spec)
    - [Cluster](#cluster)
    - [Metrics Source](#metrics-source)
    - [Backfill](#backfill)
      - [Reindex From Snapshot](#reindex-from-snapshot)
    - [Snapshot](#snapshot)
    - [Metadata Migration](#metadata-migration)
    - [Replay](#replay)
    - [Kafka](#kafka)
    - [Client Options](#client-options)
  - [Usage](#usage)
    - [Library](#library)
    - [CLI](#cli)
      - [Global Options](#global-options)
      - [Objects](#objects)
      - [Commands \& options](#commands--options)
  - [Development](#development)
    - [Unit Tests](#unit-tests)
    - [Coverage](#coverage)
    - [Backend APIs](#backend-apis)
      - [Locally testing](#locally-testing)
      - [Deployment](#deployment)

The console link library is designed to provide a unified interface for the many possible backend services involved in a migration. The interface can be used by multiple frontends--a CLI app and a web API, for instance.

![Console_link Library Diagram](console_library_diagram.svg)

The user defines their migration services in a `migration_services.yaml` file, by default found at `/config/migration_services.yaml`.

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
    reindex_from_snapshot:
        snapshot_repo: "abc"
        snapshot_name: "def"
        scale: 3
        ecs:
            cluster_name: migration-aws-integ-ecs-cluster
            service_name: migration-aws-integ-reindex-from-snapshot
            aws-region: us-east-1
replay:
  ecs:
    cluster-name: "migrations-dev-cluster"
    service-name: "migrations-dev-replayer-service"
snapshot:
  snapshot_name: "snapshot_2023_01_01"
  snapshot_repo_name: "my-snapshot-repo"
  s3:
      repo_uri: "s3://my-snapshot-bucket"
      aws_region: "us-east-2"
metadata_migration:
  from_snapshot:
  cluster_awarness_attributes: 1
kafka:
  broker_endpoints: "kafka:9092"
  standard:
client_options:
  user_agent_extra: "test-user-agent-v1.0"
```

## Services.yaml spec

### Cluster

Source and target clusters have the following options:

- `endpoint`: required, the endpoint to reach the cluster.
- `allow_insecure`: optional, default is false, equivalent to the curl `--insecure` flag, will not verify unsigned or invalid certificates
- `version`: optional, default is to assume a version compatible with ES 7 or OS 1. Format should be `ES_7.10.2` or `OS_2.15`, for instance.

Exactly one of the following blocks must be present:

- `no_auth`: may be empty, no authorization to use.
- `basic_auth`:
    - `username`
    - `password` \
    OR 
    - `user_from_secret_arn`: A secrets manager secret containing both a `username` and `password` key within the secret
- `sigv4`:
    - `region`: Optional, specify a region for the sigv4 signing, the default is the current region.
    - `service`: Optional, specify a service signing name for the cluster, e.g `es` for Amazon OpenSearch Service and `aoss` for Amazon OpenSearch Serverless. Defaults to `es`

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

Backfill can be performed via several mechanisms. The method supported by the console library is  Reindex From Snapshot (RFS).

#### Reindex From Snapshot

Depending on the purpose/deployment strategy, RFS can be used in various environments, including:
1. Docker container environment, e.g. Docker compose setup
2. AWS in an Elastic Container Service (ECS) deployment
3. Kubernetes environment, e.g. Minikube environment

Most of the parameters for these options are the same, with some additional ones specific to the deployment.

- `reindex_from_snapshot`
    - `snapshot_repo`: optional, path to the snapshot repo. If not provided, ???
    - `snapshot_name`: optional, name of the snapshot to use as the source. If not provided, ???
    - `scale`: optional int, number of instances to enable when `backfill start` is run. While running, this is modifiable with `backfill scale X`. Default is 5.

There is also a block that specifies the deployment type. Exactly one of the following blocks must be present:

- `docker`:
    - `socket`: optional, path to mounted docker socket, defaults to `/var/run/docker.sock`

- `ecs`:
    - `cluster_name`: required, name of the ECS cluster containing the RFS service
    - `service_name`: required, name of the ECS service for RFS
    - `aws_region`:  optional. if not provided, the usual rules are followed for determining aws region. (`AWS_DEFAULT_REGION`, `~/.aws/config`, etc.)
- `k8s`:
  - `namespace`: required, name of the Kubernetes namespace the RFS deployment is in
  - `deployment_name`: required, name of the RFS deployment within the Kubernetes cluster

All the following are valid RFS backfill specifications:

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

```yaml
backfill:
  reindex_from_snapshot:
    k8s:
      namespace: "ma"
      deployment_name: "ma-backfill"
```

### Snapshot

The snapshot configuration specifies a local filesystem or an s3 snapshot that will be created by the `snapshot create` command, and (if not overridden) used as the source for the `metadata migrate` command. In a docker migration, it may also be used as the source for backfill via reindex-from-snapshot. If metadata migration and reindex-from-snapshot are not being used, this block is optional.

- `snapshot_name`: required, name of the snapshot
- `snapshot_repo_name`: optional, name of the snapshot repository

Exactly one of the following blocks must be present:

- `s3`:
    - `repo_uri`: required, `s3://` path to where the snapshot repo exists or should be created (the bucket must already exist, and the repo needs to be configured on the source cluster)
    - `aws_region`: required, region for the s3 bucket
    - `role`: optional, required for clusters managed by Amazon OpenSearch Service.  The IAM Role that is passed to the source cluster for the service to assume in order to work with the snapshot bucket.  

- `fs`:
    - `repo_path`: required, path to where the repo exists or should be created on the filesystem (the repo needs to be configured on the source cluster).

### Metadata Migration

The metadata migration moves indices, components, and templates from a snapshot to the target cluster. In the future, there may be a `from_live_cluster` option, but currently all metadata migration must originate from a snapshot. A snapshot can be created via `console snapshot create` or can be pre-existing. The snapshot details are independently defineable, so if a special case is necessary, a snapshot could theoretically be created and used for document, but metadata migration could operate from a separate, pre-existing snapshot. This block is optional if metadata migration isn't being used as part of the migration.

- `cluster_awareness_attributes`: optional, an integer value for the number of awareness attributes that the cluster has (usually this means zones). This is only necessary if routing balancing across attributes is enforced.
- `index_allowlist`: optional, a list of index names. If this key is provided, only the named indices will be migrated. If the field is not provided, all non-system indices will be migrated.
- `index_template_allowlist`: optional, a list of index template names. If this key is provided, only the named templates will be migrated. If the field is not provided, all templates will be migrated.
- `component_template_allowlist`: optional, a list of component template names. If this key is provided, only the named component templates will be migrated. If the field is not provided, all component templates will be migrated.
- `source_cluster_version`: optional, if not provided the specified version in the source_cluster object will be used. Version of the source cluster from which the snapshot was taken and used for handling incompatible settings between versions.
- `transformer_config_base64`: optional, the transformation config (as a base64 encoded string) to apply during a metadata migration.
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
  
- `k8s`:
  - `namespace`: required, name of the Kubernetes namespace the Replayer deployment is in
  - `deployment_name`: required, name of the Replayer deployment within the Kubernetes cluster

### Kafka

A Kafka cluster is used in the capture and replay stage of the migration to store recorded requests and responses before they're replayed. While it's not necessary for a user to directly interact with the Kafka cluster in most cases, there are a handful of commands that can be helpful for checking on the status or resetting state that are exposed by the Console CLI.

- `broker_endpoints`: required, comma-separated list of kafka broker endpoints

Exactly one of the following keys must be present, but both are nullable (they don't have or need any additional parameters).

- `msk`: the Kafka instance is deployed as AWS Managed Service Kafka
- `standard`: the Kafka instance is deployed as a standard Kafka cluster (e.g. on Docker)

### Client Options

Client options are global settings that are applied to different clients used throughout this library

- `user_agent_extra`: optional, a user agent string that will be appended to the `User-Agent` header of all requests from this library

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

- `--config-file FILE` to specify the path to a config file (default is `/config/migration_services.yaml`)
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
pipenv run test
```

There are a handful of tests which involve creating an Elasticsearch/OpenSearch container and testing against it, which comes with a higher startup time. They're marked as "slow" tests. To skip those tests, run:

```shell
pipenv run test -m "not slow"
```

To run only those tests,

```shell
pipenv run test -m "slow"
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

### Backend APIs

As part of the Migration console many console commands are available for use by the frontend website or by workflow management tools.  This is a sub-set of the console_link library, ensuring the command line and backend functionality is passing through the same systems. 

#### Locally testing

For local development, you can use the API development script:

```shell
pipenv run api-dev
```

*Website passthrough*

To test the api when the the web frontend is running without deploying in AWS or kubernetes, make the following updates:

1. Update `frontend/nginx.conf` to allow communication to the local host
`        proxy_pass         http://127.0.0.1:8000/;` -> 
`        proxy_pass         http://host.docker.internal:8000/;`

1. Rebuild the website docker image
```shell
./gradlew :frontend:buildDockerImage
```

1. Run the website with the additional host
```shell
docker run -p 8080:80 --add-host=host.docker.internal:host-gateway migrations/website
```

1. Access the api through the website passthrough
```shell
curl http://localhost:8080/api/docs
```

#### Deployment

Consult the [frontend readme](../../../frontend/README.md) for access when hosted in AWS or kubernetes.
