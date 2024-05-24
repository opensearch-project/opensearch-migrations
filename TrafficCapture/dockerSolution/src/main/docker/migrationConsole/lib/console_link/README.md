# Console_link Library

The console link library is designed to provide a unified interface for the many possible backend services involved in a migration. The interface can be used by multiple frontends--a CLI app and a web API, for instance.

![Console_link Library Diagram](console_library_diagram.svg)


The user defines their migration services in a `migration_services.yaml` file, by default found at `/etc/migration_services.yaml`.

Currently the supported services are a source and target cluster and a metrics source. For example:

```yaml
source_cluster:
	endpoint: "https://capture-proxy-es:9200"
	allow_insecure: true
target_cluster:
	endpoint: "https://opensearchtarget:9200"
	allow_insecure: true
	authorization:
		type: "basic"
		details:
			username: "admin"
			password: "myStrongPassword123!"
metrics_source:
	type: "prometheus"
	endpoint: "http://prometheus:9090"
```

### Services.yaml spec

#### Cluster

Source and target clusters have the following options:
- `endpoint`: required, the endpoint to reach the cluster.
- `authorization`: optional, if it is provided, type is required.
	- `type`: required, the only currently implemented option is "basic", but "sigv4" should be available soon
	- `details`: for basic auth, the details should be a `username` and `password`

Having a `source_cluster` and `target_cluster` is required.

#### Metrics Source

Currently, the two supported metrics source types are `prometheus` and `cloudwatch`.

- `type`: required, `prometheus` or `cloudwatch`
- `endpoint`: required for `prometheus` (ignored for `cloudwatch`)
- `aws_region`: optional for `cloudwatch` (ignored for `prometheus`). if not provided, the usual rules are followed for determining aws region (`AWS_DEFAULT_REGION`, `~/.aws/config`)

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

## Testing
```
pip install -r tests/requirements.txt
pytest
```
