## End-to-End Testing

Developers can run a test script which will verify the end-to-end Docker Solution.

#### Compatibility
* Python >= 3.7

#### Pre-requisites

* Have all containers from Docker solution running.

To run the test script, users must navigate to this directory,
install the required packages and then run the script:

```
cd test
pip install -r requirements.txt
pytest tests.py
```

#### Notes

##### Ports Setup
The test script, by default, uses the ports assigned to the containers in this
[docker-compose file](../TrafficCapture/dockerSolution/src/main/docker/docker-compose.yml), so if the Docker solution in
its current setup started with no issues, then the test script will run as is. If for any reason
the user changed the ports in that file, they must also either, change the following environment variables:
`PROXY_ENDPOINT`, `SOURCE_ENDPOINT`, and `TARGET_ENDPOINT` respectively, or update the default value
(which can be found below) for them in [tests.py](tests.py).

The following are the default values for the only endpoints touched by this script:
* `PROXY_ENDPOINT = https://localhost:9200`
* `SOURCE_ENDPOINT = http://localhost:19200`
* `TARGET_ENDPOINT = https://localhost:29200`

#### Clean Up
The test script is implemented with a setup and teardown functions that are ran after
each and every test where additions made to the endpoints are deleted, *mostly* cleaning up after themselves, however,
as we log all operations going through the proxy (which is capturing the traffic), those are only being
deleted after the Docker solution is shut down.