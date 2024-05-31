## End-to-End Testing

### AWS E2E Testing
Featured in this directory is a `./awsE2ESolutionSetup` script whose goal is to encapsulate deploying an E2E environment in AWS. This script will perform the following key actions:
1. Deploy an Elasticsearch source cluster to EC2 using the following [CDK](https://github.com/lewijacn/opensearch-cluster-cdk/tree/migration-es)
   * The CDK context options for this CDK can be used to customize this deployment and can be provided with a custom context file using the `--context-file` option
   * A sample CDK context file can be seen [here](defaultSourceContext.json)
2. Build the Docker Images
   * This includes all images related to Traffic Capture in the top-level `TrafficCapture` directory as well as Reindex from Snapshot images in the top-level `RFS` directory
3. Deploy the Migration Assistant CDK (containing the Migration tooling and potentially an OpenSearch Domain) located in this repo [here](../deployment/cdk/opensearch-service-migration)
   * The CDK context options for this CDK can be used to customize this deployment and can be provided with a custom context file using the `--context-file` option
   * A sample CDK context file can be seen [here](defaultMigrationContext.json)
4. Add the traffic stream source security group created by the Migration Assistant CDK to each source cluster node
   * This is to allow source cluster nodes to send captured traffic from the Capture Proxy to the given traffic stream source, which is normally Kafka or AWS MSK
5. Run the `./startCaptureProxy.sh` script for starting the Capture Proxy process on each source cluster node
   * This script is automatically added to each EC2 instance on creation

#### Configuring a Historical Migration focused E2E environment
A more focused experience for only testing historical migrations can be achieved with this script by customizing the CDK context options to minimize live capture resources like so:
```
Source Context File
{
  "source-single-node-ec2": {
    "suffix": "ec2-source-<STAGE>",
    "networkStackSuffix": "ec2-source-<STAGE>",
    "distVersion": "7.10.2",
    "distributionUrl": "https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-oss-7.10.2-linux-x86_64.tar.gz",
    "captureProxyEnabled": false,
    "securityDisabled": true,
    "minDistribution": false,
    "cpuArch": "x64",
    "isInternal": true,
    "singleNodeCluster": true,
    "networkAvailabilityZones": 2,
    "dataNodeCount": 1,
    "managerNodeCount": 0,
    "serverAccessType": "ipv4",
    "restrictServerAccessTo": "0.0.0.0/0"
  }
}

Migration Context File
{
  "rfs-backfill": {
    "stage": "<STAGE>",
    "vpcId": "<VPC_ID>",
    "engineVersion": "OS_2.11",
    "domainName": "os-cluster-<STAGE>",
    "dataNodeCount": 2,
    "openAccessPolicyEnabled": true,
    "domainRemovalPolicy": "DESTROY",
    "artifactBucketRemovalPolicy": "DESTROY",
    "kafkaBrokerServiceEnabled": true,
    "trafficReplayerServiceEnabled": false,
    "reindexFromSnapshotServiceEnabled": true,
    "sourceClusterEndpoint": "<SOURCE_CLUSTER_ENDPOINT>"
  }
}
```
Note: That this deployment will still deploy an unused Kafka broker ECS service, as a traffic stream source is still required for Migration Assistant deployments, but this linkage should be untangled with: https://opensearch.atlassian.net/browse/MIGRATIONS-1532

In the same vein, the `--skip-capture-proxy` option can be provided to skip any capture proxy setup on source cluster nodes
```
./awsE2ESolutionSetup.sh --source-context-file <SOURCE_CONTEXT_FILE> --migration-context-file <MIGRATION_CONTEXT_FILE> --source-context-id source-single-node-ec2 --migration-context-id rfs-backfill --skip-capture-proxy
```

#### Placeholder substitution with awsE2ESolutionSetup.sh

The following CDK context values will be replaced by the `awsE2ESolutionSetup.sh` script if specified

Migration Context substitutable values
* `<STAGE>` replaces with default stage or stage provided with `--stage` argument
* `<VPC_ID>` replaces with VPC ID created or used by source cluster
* `<SOURCE_CLUSTER_ENDPOINT>` replaces with source cluster load balancer endpoint from source deployment

Source Context substitutable values
* `<STAGE>` replaces with default stage or stage provided with `--stage` argument




### Docker E2E Testing
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