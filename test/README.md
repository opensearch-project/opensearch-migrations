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
the user changed the ports in that file, they must also either, provide the following parameters variables:
`proxy_endpoint`, `source_endpoint`, and `target_endpoint` respectively, or update the default value
 for them in [conftest.py](conftest.py).


#### Script Parameters

This script accepts various parameters to customize its behavior. Below is a list of available parameters along with their default values and acceptable choices:

- `--proxy_endpoint`: The endpoint for the proxy endpoint.
    - Default: `https://localhost:9200`

- `--source_endpoint`: The endpoint for the source endpoint.
    - Default: `https://localhost:19200`

- `--target_endpoint`: The endpoint for the target endpoint.
    - Default: `https://localhost:29200`

- `--source_auth_type`: Specifies the authentication type for the source endpoint.
    - Default: `basic`
    - Choices: `none`, `basic`, `sigv4`

- `--source_verify_ssl`: Determines whether to verify the SSL certificate for the source endpoint.
    - Default: `False`
    - Choices: `True`, `False`

- `--target_auth_type`: Specifies the authentication type for the target endpoint.
    - Default: `basic`
    - Choices: `none`, `basic`, `sigv4`

- `--target_verify_ssl`: Determines whether to verify the SSL certificate for the target endpoint.
    - Default: `False`
    - Choices: `True`, `False`

- `--source_username`: Username for authentication with the source endpoint.
    - Default: `admin`

- `--source_password`: Password for authentication with the source endpoint.
    - Default: `admin`

- `--target_username`: Username for authentication with the target endpoint.
    - Default: `admin`

- `--target_password`: Password for authentication with the target endpoint.
    - Default: `myStrongPassword123!`


#### Clean Up
The test script is implemented with a setup and teardown functions that are ran after
each and every test where additions made to the endpoints are deleted, *mostly* cleaning up after themselves, however,
as we log all operations going through the proxy (which is capturing the traffic), those are only being
deleted after the Docker solution is shut down.