# Migration Assistant command-line tools

This package provides the command-line interfaces installed in the Migration
Console image:

| Command | Purpose |
| --- | --- |
| `workflow` | Configure, submit, monitor, approve, inspect, and reset workflow-based migrations. |
| `console` | Inspect and operate individual migration components. |
| `loadtest` | Run and inspect migration load tests. |

The **Migration Console** is the container that packages these tools and their
runtime dependencies. It is not a separate migration control plane. For current
Kubernetes deployments, `workflow` is the primary user interface and the
workflow resources in the cluster are the source of truth.

Use `console` for lower-level diagnostics and component operations, such as
checking cluster connectivity, inspecting Kafka, or examining failed documents.
The available commands depend on the resources configured for the deployment,
so command help is the authoritative reference:

```shell
workflow --help
workflow COMMAND --help
console --help
console OBJECT --help
```

## Installation

The commands are preinstalled in the Migration Console image. To install them
in another Python environment, run the following from this directory:

```shell
pip install .
```

The machine running the commands must have network access and credentials for
the Kubernetes cluster and any migration components it needs to reach.

## Workflow CLI

The `workflow` CLI manages the migration as a set of Kubernetes and Argo
Workflow resources. When it runs inside the Migration Console pod, it discovers
the current namespace. Outside the pod, it uses the active Kubernetes context
and defaults to the `ma` namespace.

### Typical migration workflow

Create or edit the migration configuration:

```shell
workflow configure edit
```

For automation, provide the configuration on standard input:

```shell
workflow configure edit --stdin < migration.yaml
```

Review the stored configuration and submit it:

```shell
workflow configure view
workflow submit
```

`workflow submit` returns after submission. Add `--wait` to wait for completion:

```shell
workflow submit --wait
workflow submit --wait --timeout 300
```

Check progress with either a one-time status view or the interactive manager:

```shell
workflow status
workflow status --resource-view
workflow manage
```

List gates when a workflow needs approval, then approve the appropriate gate:

```shell
workflow approve --list
workflow approve step --list
workflow approve step GATE_NAME
```

Use `workflow approve change` for a gated configuration change. Use
`workflow approve retry` only after completing the recovery described by the
gate:

```shell
workflow approve change --list
workflow approve retry --list
```

Inspect logs and retained task output:

```shell
workflow log all
workflow log resource --list
workflow show --list
workflow show --all
```

List resettable resources before deleting any workflow-managed state:

```shell
workflow reset --list
workflow reset RESOURCE_NAME
```

`workflow reset` does not undo changes to external resources such as a target
cluster. Run `workflow reset --help` and the relevant component commands before
resetting a migration.

### Command summary

| Command | Purpose |
| --- | --- |
| `workflow configure` | Edit, validate, view, and manage the migration configuration and credentials. |
| `workflow submit` | Generate and submit the migration workflow. |
| `workflow status` | Print workflow or migration-resource status. |
| `workflow manage` | Open an interactive status, log, and approval interface. |
| `workflow approve` | List or approve step, change, and recovery gates. |
| `workflow log` | View or follow logs for a workflow, resource, or label filter. |
| `workflow show` | Read current or retained task output artifacts. |
| `workflow reset` | Delete selected workflow-managed migration resources. |

Most commands print a next-step hint after completing an action. Use
`workflow COMMAND --help` for the full options and examples for a command.

### Shell completion

Generate a completion script with:

```shell
workflow util completions bash
workflow util completions zsh
workflow util completions fish
```

Install the output using the conventions for your shell.

## Component CLI

The `console` CLI provides component-level operations. Its command structure is:

```text
console [GLOBAL_OPTIONS] OBJECT COMMAND [OPTIONS]
```

Common global options include:

- `--json` for machine-readable output
- `--version` to print the Migration Assistant version
- `-v` or `-vv` for additional logging

In a Kubernetes deployment, `console` discovers migration resources from the
cluster. A configuration file can be selected explicitly when working in an
environment that does not expose that resource state:

```shell
console --config-file /path/to/migration_services.yaml OBJECT COMMAND
```

Always-available command groups include clusters, Kafka, failed-document
streams, and captured tuples. Some deployments also enable direct snapshot,
backfill, replay, metadata, and metrics commands. Run `console --help` to see
the command groups available in the current environment.

## Library structure

The `console_link` Python package also contains the implementation used by the
CLIs:

- `environment.py` resolves the migration resources and component
  configuration available to a command.
- `middleware/` implements component operations and normalizes their results.
- `models/` contains clients and deployment-specific component behavior.
- `workflow/` implements workflow orchestration and its terminal interface.

Configuration formats and supported fields change with the deployment model.
The schemas and resource adapters in the implementation are authoritative;
this README intentionally does not duplicate a static configuration-file
schema.

## Development

Create the development environment from this directory:

```shell
pipenv install --dev
```

Run the unit tests:

```shell
pipenv run test
```

Tests that create an Elasticsearch or OpenSearch container are marked `slow`:

```shell
pipenv run test -m "not slow"
pipenv run test -m "slow"
```

### Workflow test cluster

The Kubernetes workflow tests under `tests/workflow-tests/` use a dedicated
Kind cluster:

```shell
./scripts/setup-workflow-test-cluster.sh
pipenv run pytest -vv -s tests/workflow-tests/test_workflow_integration.py -k submit_hello_world
./scripts/teardown-workflow-test-cluster.sh
```

The defaults are cluster `console-link-test` and context
`kind-console-link-test`. Override them with
`WORKFLOW_TEST_KIND_CLUSTER_NAME` and `WORKFLOW_TEST_KUBE_CONTEXT`.

### Coverage

After a unit-test run, print or generate the coverage report:

```shell
pipenv run coverage report
pipenv run coverage html
```
