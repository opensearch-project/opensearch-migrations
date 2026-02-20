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
    "engineVersion": "OS_2.19",
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



### Running Integration Tests
Details can be found in the integration testing README [here](../migrationConsole/lib/integ_test/README.md)
