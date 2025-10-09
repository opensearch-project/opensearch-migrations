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
pipenv run app --source-version=ES_5.6 --target-version=OS_2.19
```

Or to execute a specific test
```shell
pipenv run app --test-ids=0004
```

For development testing, it is often convenient to reuse an existing set of clusters and keep the Migration Assistant helm chart installed between executions. The `--dev` flag is an aggregate flag that achieves this by enabling the following flags `[--skip-delete, --reuse-clusters, --keep-workflows, --developer-mode]`. This command can then be run repeatedly for faster executions.
```shell
pipenv run app --dev --source-version=ES_7.10 --target-version=OS_2.19 --test-ids=0001
```