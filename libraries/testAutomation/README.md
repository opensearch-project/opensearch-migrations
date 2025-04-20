# Test Automation Layer

This library provides an integration testing automation layer for Kubernetes

## Development

To install the library for development purposes, create a virtual env and install the library. It will automatically install its dependencies as well.

```shell
pipenv install
```

To run the test automation library, a command like below can be used:
```shell
pipenv run app
```

Or to execute a specific test
```shell
pipenv run app --test-ids=0004
```