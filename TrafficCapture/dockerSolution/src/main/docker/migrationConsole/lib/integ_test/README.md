## E2E Integration Testing
Developers can run a test script which will verify the end-to-end Docker Solution.

### Compatibility
* Python >= 3.7

### Pre-requisites

* Have all containers from Docker solution running.

To run the test script, users must navigate to this directory,
install the required packages and then run the script:

```
pip install -r requirements.txt
pytest tests.py
```

### Running in Docker setup

From the root of this repository bring up the Docker environment
```shell
./gradlew -p TrafficCapture dockerSolution:ComposeUp -x test -x spotlessCheck --info --stacktrace
```

The Docker compose file being used can be found [here](../../../docker-compose.yml)
* The integ_test `lib` directory can be directly mounted as a volume on the migration console container to spe

To run one of the integration test suites a command like below can be used:
```shell
docker exec $(docker ps --filter "name=migration-console" -q) pipenv run pytest /root/lib/integ_test/integ_test/full_tests.py --unique_id="testindex" -s
```

To teardown, execute the following command at the root of this repository
```shell
./gradlew -p TrafficCapture dockerSolution:ComposeDown
```

#### Notes

##### Ports Setup
The test script, by default, uses the ports assigned to the containers in this
[docker-compose file](../../../docker-compose.yml), so if the Docker solution in
its current setup started with no issues, then the test script will run as is. If for any reason
the user changed the ports in that file, they must also either, provide the following parameters variables:
`proxy_endpoint`, `source_endpoint`, and `target_endpoint` respectively, or update the default value
 for them in [conftest.py](integ_test/conftest.py).


#### Script Parameters

This script accepts various parameters to customize its behavior. Below is a list of available parameters along with their default values and acceptable choices:

- `--unique_id`: The unique identifier to apply to created indices/documents.
    - Default: Generated uuid
- `--config_file_path`: The services yaml config file path for the console library.
    - Default: `/config/migration_services.yaml`


#### Clean Up
The test script is implemented with a setup and teardown functions that are ran after
each and every test where additions made to the endpoints are deleted, *mostly* cleaning up after themselves, however,
as we log all operations going through the proxy (which is capturing the traffic), those are only being
deleted after the Docker solution is shut down.
