# Console_link Library

The console link library is designed to provide a unified interface for the many possible backend services involved in a migration. The interface can be used by multiple frontends--a CLI app and a web API, for instance.

![Console_link Library Diagram](console_library_diagram.svg)


The user defines their migration services in a `migration_services.yaml` file, by default found at `/etc/migration_services.yaml`.

Currently, the supported services are:
* `source_cluster`: Source cluster details
* `target_cluster`: Target cluster details
* `metrics_source`: Metrics source details
* `backfill`: Backfill migration details

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
```

### Services.yaml spec

#### Cluster

Source and target clusters have the following options:
- `endpoint`: required, the endpoint to reach the cluster.
- `authorization`: required, the auth method to use, if no auth the no_auth type must be specified.
    - `type`: required, the only currently implemented options are "no_auth" and "basic_auth", but "sigv4" should be available soon
    - `details`: for basic auth, the details should be a `username` and `password`

Having a `source_cluster` and `target_cluster` is required.

#### Metrics Source

Currently, the two supported metrics source types are `prometheus` and `cloudwatch`.

- `type`: required, `prometheus` or `cloudwatch`
- `endpoint`: required for `prometheus` (ignored for `cloudwatch`)
- `aws_region`: optional for `cloudwatch` (ignored for `prometheus`). if not provided, the usual rules are followed for determining aws region (`AWS_DEFAULT_REGION`, `~/.aws/config`)

#### Backfill

Currently, the only supported backfill migration type is `opensearch_ingestion`.

#### OpenSearch Ingestion
- `pipeline_role_arn`: required, IAM pipeline role containing permissions to read from source and read/write to target, more details [here](https://docs.aws.amazon.com/opensearch-service/latest/developerguide/pipeline-security-overview.html#pipeline-security-sink)
- `vpc_subnet_ids`: required, VPC subnets to place the OSI pipeline in
- `security_group_ids`: required, security groups to apply to OSI pipeline for accessing source and target clusters
- `aws_region`: required, AWS region to look for pipeline role and secrets for cluster
- `pipeline_name`: optional, name of OSI pipeline
- `index_regex_selection`: optional, list of index inclusion regex strings for selecting indices to migrate
- `log_group_name`: optional, name of existing CloudWatch log group to use for OSI logs
- `tags`: optional, list of tags to apply to OSI pipeline

# Usage
### Library
The library can be imported and used within another application.
Use `pip install .` from the top-level `console_link` directory to install it locally and then import it as, e.g. `from console_link.models.metrics_source import MetricsSource`

#### CLI
The CLI comes installed on the migration console. If you'd like to install it elsewhere, `pip install .` from the top-level `console_link` directory will install it and setup a `console` executable to access it.

Autocomplete can be enabled by adding `eval "$(_CONSOLE_COMPLETE=bash_source console)"` to your `.bashrc` file, or `eval "$(_FOO_BAR_COMPLETE=zsh_source foo-bar)"` to your `.zshrc` and re-sourcing your shell.

The structure of cli commands is:
`console [--global-options] OBJECT COMMAND [--options]`

##### Global Options
The available global options are:
- `--config-file FILE` to specify the path to a config file (default is `/etc/migration_services.yaml`)
- `--json` to get output in JSON designed for machine consumption instead of printing to the console

##### Objects
Currently, the two objects available are `cluster` and `metrics`.

##### Commands & options
Each object has its own commands available, and each command has its own options. To see the available commands and options, use:
```
console OBJECT --help
```

### Unit Tests

Unit tests can be run from this current `console_link/` by first installing dependencies then running pytest:

```shell
pip install -r tests/requirements.txt
python -m coverage run -m pytest
```

### Coverage

_Code coverage_ metrics can be generated after a unit-test run. A report can either be printed on the command line:

```shell
python -m coverage report
```

or generated as HTML:

```shell
python -m coverage html
```
