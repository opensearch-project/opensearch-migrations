# Test Automation Layer

This library provides an integration testing automation layer for Kubernetes

## Development

To install the library for development purposes, create a virtual env and install the library. It will automatically install its dependencies as well.

```shell
pipenv install
```

To run the test automation library, a command like below can be used to use the defaults for source and target version:
```shell
pipenv run app
```

Or alternatively specific versions for the source and target cluster can be specified, which will result in executing tests against this specific version combination:
```shell
pipenv run app --source-version=ES_8.x --target-version=OS_2.x
```

Or to execute a specific test
```shell
pipenv run app --test-ids=0004
```