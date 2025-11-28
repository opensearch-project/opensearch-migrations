## Migration Assistant E2E Integration Testing
This library contains E2E integration tests to execute against a Migration Assistant deployment

### Installing dependencies
To install the required dependencies
```
pipenv install
```

### Creating an E2E test case
Test cases created within the `test_cases` directory are performed by the `ma_workflow_test.py` test structure. The link between these two are created in the pytest 
configuration `conftest.py` file. Any test class created in an existing file within the `test_cases` directory will automatically be added to the list of test cases
to attempt when the `ma_workflow_test.py` file is executed with pytest. The `conftest.py` file achieves this by collecting all test cases initially, and then filters
out any test cases that don't apply to the given source and target clusters versions, as well as on filters that a user can provide such as `--test_ids`. Once the final
list is determined, the `conftest.py` file will dynamically create a parameterized tag on the `ma_workflow_test.py` test, resulting in multiple executions of this test
based on the final list of test cases to be executed. If a new test file is created within the `test_cases` directory it should be imported into the `conftest.py` file 
like other test files.


### Running tests in K8s setup

Follow the quickstart guide [here](../../../deployment/k8s/quickstart.md) to set up a Migration Assistant environment with source and
target test clusters

Access the migration console:
```shell
kubectl exec --stdin --tty $(kubectl get pods -l app=ma-migration-console --sort-by=.metadata.creationTimestamp -o jsonpath="{.items[-1].metadata.name}") -- /bin/bash
```

Perform pytest:
```shell
pytest ~/lib/integ_test/integ_test/ma_workflow_test.py
```

To tear-down resources, follow the end of the quickstart guide [here](../../../deployment/k8s/quickstart.md#cleanup)


### Pytest parameters

Pytest has been configured to accepts various parameters to customize its behavior. Below is a list of available parameters along with their default values and acceptable choices:

- `--unique_id`: The unique identifier to apply to created indices/documents.
    - Default: Generated uuid
- `--config_file_path`: The services yaml config file path for the console library.
    - Default: `/config/migration_services.yaml`
- `--test_ids`: Specify test IDs like `'0001,0003'` to filter tests to execute.
    - Default: Attempt to execute all tests
