# Kubernetes Config Map Utility Library

This library provides utilities for working with Kubernetes ConfigMaps and specifically generating a `migration_services.yaml` usable by the Migration Console from ConfigMap values 

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
