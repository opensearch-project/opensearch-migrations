# Kubernetes Config Map Utility Library

This library provides utilities for working with Kubernetes ConfigMaps

The `config_watcher.py` utility is the translation layer between ConfigMaps and the `migration_services.yaml` file used by the Migration Console. It generates a `migration_services.yaml` file from existing ConfigMap values and continuously monitors those values using the Kubernetes client, ensuring that any updates are automatically reflected in the file.
A key use case for this utility is within the Migration Console service, where a sidecar container runs `config_watcher.py` to create the `migration_services.yaml` file on a shared mounted volume. The Migration Console itself also mounts this volume, allowing it to access the configuration file for its console library commands.
For `config_watcher.py` to function properly, the required ConfigMap values must already exist. This is mainly handled by the `charts/sharedResources/sharedConfigs` Helm chart, which generates shared ConfigMaps based on provided values. These values are defined and provided to the `sharedConfigs` chart by the main `charts/aggregates/migrationAssistant` chart.


## Development

To install the library for development purposes, create a virtual env and install the library. It will automatically install its dependencies as well.

```shell
pipenv install
```

To run `config_watcher.py` locally, a command like below can be used:
```shell
pipenv run config_watcher --outfile ./test.yaml
```

### Unit Tests

Unit tests can be run from this current `k8sConfigMapUtilScripts/` directory by first installing dependencies then running pytest:

```shell
pipenv install --dev
pipenv run test
```

### Coverage

_Code coverage_ metrics can be generated after a unit-test run. A report can either be printed on the command line:

```shell
pipenv run coverage report
```
