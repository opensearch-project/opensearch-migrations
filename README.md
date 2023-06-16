## OpenSearch Migrations

```
Data flows to OpenSearch
On wings of the morning breeze
Effortless migration
```

This repo will contain code and documentation to assist in migrations and upgrades of OpenSearch clusters.

## Setup for commits

Developers must run the "install_githooks.sh" script in order to add the pre-commit hook.

## Docker Solution

The TrafficCapture directory hosts a set of projects designed to facilitate the proxying and capturing of HTTP
traffic, which can then be offloaded and replayed to other HTTP server(s).

More documentation on this solution can be found here:
[TrafficCapture README](TrafficCapture/README.md)

### End-to-End Testing

Developers can run a test script which will verify the end-to-end Docker Solution.
#### Pre-requisites

* Have all containers from Docker solution running.

To run the test script, users must navigate to the [test directory](test/),
install the required packages then run the script:

```
cd test
pip install -r requirements.txt
pytest tests.py
```

#### Notes 
##### Ports Setup
The test script, by default, uses the ports assigned to the containers in this
[docker-compose file](TrafficCapture/dockerSolution/src/main/docker/docker-compose.yml), so if the Docker solution in
it's current setup started with no issues, then the test script will run as is. If for any reason
the user changed the ports in that file, they must also either, change the following environment variables:
`PROXY_ENDPOINT`, `SOURCE_ENDPOINT`, `TARGET_ENDPOINT` and `JUPYTER_NOTEBOOK` respectively, or update the default value
(which can be found below) for them in [tests.py](test/tests.py).

The following are the default values for the only endpoints touched by this script:
* `PROXY_ENDPOINT = https://localhost:9200`
* `SOURCE_ENDPOINT = http://localhost:19200`
* `TARGET_ENDPOINT = https://localhost:29200`
* `JUPYTER_NOTEBOOK = http://localhost:8888/api`
#### Clean Up
The test script is implemented with a setup and teardown functions that are ran after 
each and every test where additions made to the endpoints are deleted, *mostly* cleaning up after themselves, however,
as we log all operations going through the proxy (which is capturing the traffic), those are only being 
deleted after the Docker solution is shut down.

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This project is licensed under the Apache-2.0 License.
