# OpenSearch Service Migration CDK

This directory contains the infrastructure-as-code CDK solution for deploying an OpenSearch Domain as well as the infrastructure for the Migration solution. Users have the ability to easily deploy their infrastructure using default values or provide [configuration options](./options.md) for a more customized setup. The goal of this repo is not to become a one-size-fits-all solution for users- rather, this code base should be viewed as a starting point for users to use and add to individually as their custom use case requires.

## Infrastructure Requirements
### VPC Requirements
There are certain requirements for imported and created VPCs used in this solution that come from its dependencies on other AWS services such as MSK(Kafka), OpenSearch Service, and ECS. These requirements should be met before using an imported VPC with this solution.
* At least 2 private subnets should be available which span at least 2 Availability Zones

## Getting Started

### Install Prerequisites

###### Docker
Docker is used by CDK to build container images. If not installed, follow the steps [here](https://docs.docker.com/engine/install/) to set up. Later versions are recommended.
###### Git
Git is used by the opensearch-migrations repo to fetch associated repositories. Steps to set up can be found [here](https://github.com/git-guides/install-git).
###### Java 11
Java is used by the opensearch-migrations repo and Gradle, its associated build tool. The current required version is Java 11.

### Project required setup

1- It is necessary to run `npm install` within this current directory to install required packages that this app and CDK need for operation.

2- Creating Dockerfiles, this project needs to build the required Dockerfiles that the CDK will use in its services. These Dockerfiles can be built by running the below script which is located in the same directory as this README
```shell
./buildDockerImages.sh
```
More details can be found [here](../../../TrafficCapture/dockerSolution/README.md)

3- Fetch Migration Setup, in order to make use of Fetch Migration for historical data capture, a user should make any modifications necessary to the `dp_pipeline_template.yaml` file located in the same directory as this README before deploying. More information around the parameters used in the pipeline file can be found [here](https://opensearch.org/docs/latest/data-prepper/pipelines/pipelines/).

The existing pipeline template works for the `demo-deploy` stack without any further modifications.

Further steps on starting Fetch Migration after deployment can be found [here](#kicking-off-fetch-migration)

4- Configure the desired **[AWS credentials](https://docs.aws.amazon.com/cdk/v2/guide/getting_started.html#getting_started_prerequisites)**, as these will dictate the region and account used for deployment.

5- There is a known issue where service linked roles fail to get applied when deploying certain AWS services for the first time in an account. This can be resolved by simply deploying again (for each failing role) or avoided entirely by creating the service linked role initially like seen below:
```shell
aws iam create-service-linked-role --aws-service-name opensearchservice.amazonaws.com; aws iam create-service-linked-role --aws-service-name ecs.amazonaws.com
```

### First time using CDK in this region?

If this is your first experience with CDK, follow the steps below to get started:

1- Install the **CDK CLI** tool, if you haven't already, by running:
```shell
npm install -g aws-cdk
```

2- **Bootstrap CDK**: if you have not run CDK previously in the configured region of you account, it is necessary to run the following command from the same directory as this README to set up a small CloudFormation stack of resources that CDK needs to function within your account

```shell
# Execute from the deployment/cdk/opensearch-service-migration directory
cdk bootstrap --c contextId=demo-deploy
```

Further CDK documentation [here](https://docs.aws.amazon.com/cdk/v2/guide/cli.html)

## Deploying the CDK
This project uses CDK context parameters to configure deployments. These context values will dictate the composition of your stacks as well as which stacks get deployed.

The full list of available configuration options for this project are listed [here](./options.md). Each option can be provided as an empty string `""` or simply not included, and in each of these 'empty' cases the option will use the project default value (if it exists) or CloudFormation's default value.

A set of demo context values (using the `demo-deploy` label) has been set in the `cdk.context.json` located in this directory, which can be customized or used as is for a quickstart demo solution.

This demo solution can be deployed with the following command:
```shell
cdk deploy "*" --c contextId=demo-deploy --require-approval never --concurrency 3
```

Additionally, another context block in the `cdk.context.json` could be created with say the label `uat-deploy` with its own custom context configuration and be deployed with the command:
```shell
cdk deploy "*" --c contextId=uat-deploy --require-approval never --concurrency 3
```
**Note**: Separate deployments within the same account and region should use unique `stage` context values to avoid resource naming conflicts when deploying (**Except** in the multiple replay scenario stated [here](#how-to-run-multiple-traffic-replayer-scenarios)) 

Stacks can also be redeployed individually, with any required stacks also being deployed initially, e.g. the following command would deploy the migration-console stack
```shell
cdk deploy "migration-console" --c contextId=demo-deploy
```

To get a list of all the available stack ids that can be deployed/redeployed for a particular `contextId`:
```shell
cdk ls --c contextId=demo-deploy
```


Depending on your use-case, you may choose to provide options from both the `cdk.context.json` and the CDK CLI, in which case it is important to know the precedence level for context values. The below order shows these levels with values being passed by the CDK CLI having the most importance
1. CDK CLI passed context values (highest precedence)
2. Created `cdk.context.json` in the same directory as this README
3. Existing `default-values.json` in the same directory as this README

## Executing Commands on a Deployed Service

Once a service has been deployed, a command shell can be opened for that service's container. If the SSM Session Manager plugin is not installed, it should be installed when prompted from the below exec command.
```shell
# ./accessContainer.sh <service-name> <stage> <region>
./accessContainer.sh migration-console dev us-east-1
```
To be able to execute this command the user will need to have their AWS credentials configured for their environment. It is also worth noting that the identity used should have permissions to `ecs:ExecuteCommand` on both the ECS cluster and the given container task, for which an Administrator or Deployment role may already have. An example IAM policy that could be added, which allows accessing any of the containers on the ECS cluster, can be seen below:
```shell
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": "ecs:ExecuteCommand",
            "Resource": [
                "arn:aws:ecs:<REGION>:<ACCOUNT-ID>:cluster/migration-<STAGE>-ecs-cluster",
                "arn:aws:ecs:<REGION>:<ACCOUNT-ID>:task/migration-<STAGE>-ecs-cluster/*"
            ]
        }
    ]
}
```

## Starting the Traffic Replayer
When the Migration solution is deployed, the Traffic Replayer does not immediately begin replaying. This is designed to allow users time to do any historical backfill (e.g. Fetch Migration service) that is needed as well as setup the Capture Proxy on their source coordinating nodes. When the user is ready they can then run the following command from the Migration Console service and begin replaying the traffic that has been captured by the Capture Proxy

```shell
aws ecs update-service --cluster migration-<STAGE>-ecs-cluster --service migration-<STAGE>-traffic-replayer-default --desired-count 1
```

With this same command, a user could stop replaying capture traffic by removing the Traffic Replayer instance if they set `--desired-count 0` 

## Testing the deployed solution

Once the solution is deployed, the easiest way to test the solution is to access the `migration-console` service container and run an opensearch-benchmark workload through to simulate incoming traffic, as the following steps illustrate

```shell
# Exec into container
./accessContainer.sh migration-console dev us-east-1

# Run opensearch-benchmark workload (i.e. geonames, nyc_taxis, http_logs)
./runTestBenchmarks.sh
```

After the benchmark has been run, the indices and documents of the source and target clusters can be checked from the same migration-console container to confirm
```shell
# Check doc counts and indices for both source and target cluster
./catIndices.sh
```

## Importing Target Clusters
By default, if a `targetClusterEndpoint` option isn't provided, this CDK will create an OpenSearch Service Domain (using provided options) to be the target cluster of this solution. While setting up this Domain, the CDK will also configure a relevant security group and allows options to configure an access policy on the Domain (`accessPolicies` and `openAccessPolicyEnabled` options) such that the Domain is fully setup for use at deployment. 

In the case of an imported target cluster, there are normally some modifications that need to be made on the existing target cluster to allow proper functioning of this solution after deployment which the below subsections elaborate on.

#### OpenSearch Service
For a Domain, there are typically two items that need to be configured to allow proper functioning of this solution
1. The Domain should have a security group that allows communication from the applicable Migration services (Traffic Replayer, Migration Console, Fetch Migration). This CDK will automatically create an `osClusterAccessSG` security group, which has already been applied to the Migration services, that a user should then add to their existing Domain to allow this access.
2. The access policy on the Domain should be an open access policy that allows all access or an access policy that at least allows the IAM task roles for the applicable Migration services (Traffic Replayer, Migration Console, Fetch Migration)

#### OpenSearch Serverless
A Collection, will need to configure a Network and Data Access policy to allow proper functioning of this solution
1. The Collection should have a network policy that has a `VPC` access type by creating a VPC endpoint on the VPC used for this solution. This VPC endpoint should be configured for the private subnets of the VPC and attach the `osClusterAccessSG` security group.
2. The data access policy needed should grant permission to perform all [index operations](https://docs.aws.amazon.com/opensearch-service/latest/developerguide/serverless-data-access.html#serverless-data-supported-permissions) (`aoss:*`) for all indexes in the given collection, and use the task roles of the applicable Migration services (Traffic Replayer, Migration Console, Fetch Migration) as the principals for this data access policy.

See [Configuring SigV4 Replayer Requests](#configuring-sigv4-replayer-requests) for details on enabling SigV4 requests from the Traffic Replayer to the target cluster

## Configuring SigV4 Replayer Requests
With the [required setup](#importing-target-clusters) on the target cluster having been completed, a user can then use the `trafficReplayerExtraArgs` option to specify the Traffic Replayer service argument for enabling SigV4 authentication, which the below sections show. **Note**: As only one authorization header can be specified, the `trafficReplayerEnableClusterFGACAuth` option should not be used if enabling SigV4 authentication for the Traffic Replayer. See [here](#how-is-an-authorization-header-set-for-requests-from-the-replayer-to-the-target-cluster) for more details on how the Traffic Replayer sets its authorization header.
#### OpenSearch Service
```shell
# e.g. --sigv4-auth-header-service-region es,us-east-1
"trafficReplayerExtraArgs": "--sigv4-auth-header-service-region es,<REGION>"
```

#### OpenSearch Serverless
```shell
# e.g. --sigv4-auth-header-service-region aoss,us-east-1
"trafficReplayerExtraArgs": "--sigv4-auth-header-service-region aoss,<REGION>"
```

## Kicking off Fetch Migration

* First, access the Migration Console container

```shell
# ./accessContainer.sh migration-console STAGE REGION
./accessContainer.sh migration-console dev us-east-1
```

* Execute the ECS run task command generated by `showFetchMigrationCommand.sh` script. 
    * The status of the ECS Task can be monitored from the AWS Console. Once the task is in the `Running` state, logs and progress can be viewed via CloudWatch.
```shell
# This will execute the script and print the required ECS run task command
./showFetchMigrationCommand.sh

# Paste command output by the script into the terminal to kick off Fetch Migration 
```

The pipeline configuration file can be viewed (and updated) via AWS Secrets Manager.
Please note that it will be base64 encoded.

## Kicking off OpenSearch Ingestion Service

**Note**: Using OpenSearch Ingestion Service is currently an experimental feature that must be enabled with the `migrationConsoleEnableOSI` option. Currently only Managed OpenSearch service as a source to Managed OpenSearch service as a target migrations are supported

After enabling and deploying the CDK, log into the Migration Console
```shell
# ./accessContainer.sh migration-console STAGE REGION
./accessContainer.sh migration-console dev us-east-1
```
Make any modifications to the `osiPipelineTemplate.yaml` on the Migration Console, if needed. Note: Placeholder values exist in the file to automatically populate source/target endpoints and corresponding auth options by the python tool that uses this yaml file.

The OpenSearch Ingestion pipeline can then be created by giving an existing source cluster endpoint and running the below command
```shell
./osiMigration.py create-pipeline-from-solution --source-endpoint=<SOURCE_ENDPOINT>
```

When OpenSearch Ingestion pipelines are created they begin running immediately and can be stopped with the following command
```shell
./osiMigration.py stop-pipeline
```
Or restarted with the following command
```shell
./osiMigration.py start-pipeline
```

## Kicking off Reindex from Snapshot (RFS)

When the RFS service gets deployed, it does not start running immediately. Instead, the user controls when they want to kick off a historical data migration.

The following command can be run from the Migration Console to initiate the RFS historical data migration
```shell
aws ecs update-service --cluster migration-<STAGE>-ecs-cluster --service migration-<STAGE>-reindex-from-snapshot --desired-count 1
```

Currently, the RFS application will enter an idle state with the ECS container still running upon completion. This can be cleaned up by using the same command with `--desired-count 0`


## Monitoring Progress via Instrumentation

The replayer and capture proxy (if started with the `--otelCollectorEndpoint` argument) emit metrics through an 
otel-collector endpoint, which is deployed within Migrations Assistant tasks as a sidecar container. The
otel-collectors will publish metrics and traces to Amazon CloudWatch and AWS X-Ray.

Some of these metrics will show simple progress, such as bytes or records transmitted.  Other records can show higher
level information, such the number of responses with status codes that match vs those that don't.  To observe those,
search for `statusCodesMatch` in the CloudWatch Console.  That's emitted as an attribute along with the method and
the source/target status code (rounded down to the last hundred; i.e. a status code of 201 has a 200 attribute).

Other metrics will show latencies, the number of requests, unique connections at a time and more.  Low-level and 
high-level metrics are being improved and added.  For the latest information, see the
[README.md](../../../coreUtilities/README.md).

Along with metrics, traces are emitted by the replayer and the proxy (when proxy is run with metrics enabled, e.g. by 
launching with --otelCollectorEndpoint set to the otel-collector sidecar).  Traces will include very granular data for
each connection, including how long the TCP connections are open, how long the source and target clusters took to send 
a response, as well as other internal details that can explain the progress of each request.  

Notice that traces for the replayer will show connections and Kafka records open, in some cases, much longer than their
representative HTTP transactions.  This is because records are considered 'active' to the replayer until they are 
committed and records are only committed once _all_ previous records have also been committed.  Details such as that
are defensive for when further diagnosis is necessary. 

## Configuring Capture Proxy IAM and Security Groups
Although this CDK does not set up the Capture Proxy on source cluster nodes (except in the case of the demo solution), the Capture Proxy instances do need to communicate with resources deployed by this CDK (e.g. Kafka) which this section covers

#### Capture Proxy on OpenSearch/Elasticsearch nodes
Before [setting up Capture Proxy instances](../../../TrafficCapture/trafficCaptureProxyServer/README.md#how-to-attach-a-capture-proxy-on-a-coordinator-node) on the source cluster, the IAM policies and Security Groups for the nodes should allow access to the Migration tooling:
1. The coordinator nodes should add the `trafficStreamSourceSG` security group to allow access to Kafka
2. The IAM role used by the coordinator nodes should have permissions to publish captured traffic to Kafka. A template policy to use, can be seen below
   * This can be added through the AWS Console (IAM Role -> Add permissions -> Create inline policy -> JSON view)
```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Action": "kafka-cluster:Connect",
            "Resource": "arn:aws:kafka:<REGION>:<ACCOUNT-ID>:cluster/migration-msk-cluster-<STAGE>/*",
            "Effect": "Allow"
        },
        {
            "Action": [
                "kafka-cluster:CreateTopic",
                "kafka-cluster:DescribeTopic",
                "kafka-cluster:WriteData"
            ],
            "Resource": "arn:aws:kafka:<REGION>:<ACCOUNT-ID>:topic/migration-msk-cluster-<STAGE>/*",
            "Effect": "Allow"
        }
    ]
}
```

## Tearing down CDK
To remove all the CDK stack(s) which get created during a deployment we can execute a command similar to below
```shell
# For demo context
cdk destroy "*" --c contextId=demo-deploy
```
Or to remove an individual stack from the deployment we can execute
```shell
# For demo context
cdk destroy migration-console --c contextId=demo-deploy
```
**Note**: The `demo-deploy`contextId has the retention policy for the OpenSearch Domain set to `DESTROY`, which will remove this resource and all its data when the stack is deleted. In order to retain the Domain on stack deletion the `domainRemovalPolicy` would need to be set to `RETAIN`.

## How to run multiple Traffic Replayer scenarios
The project supports running distinct Replayers in parallel, with each Replayer sending traffic to a different target cluster. This functionality allows users to test replaying captured traffic to multiple different target clusters in parallel. Users are able to provide the desired configuration options to spin up a new OpenSearch Domain and Traffic Replayer while using the existing Migration infrastructure that has already been deployed.

To give an example of this process, a user could decide to configure an additional Replayer and Domain for the demo setup in the `cdk.context.json` by configuring a new context block like below. **Note**: `addOnMigrationDeployId` is a required field to allow proper naming of these additional resources.
```shell
  "demo-addon1": {
    "addOnMigrationDeployId": "demo-addon1",
    "stage": "dev",
    "engineVersion": "OS_1.3",
    "domainName": "demo-cluster-1-3",
    "dataNodeCount": 2,
    "openAccessPolicyEnabled": true,
    "domainRemovalPolicy": "DESTROY",
    "enableDemoAdmin": true,
    "trafficReplayerServiceEnabled": true,
    "trafficReplayerEnableClusterFGACAuth": true
  }
```
And then deploy this additional infrastructure with the command:
```shell
cdk deploy "*" --c contextId=demo-addon1 --require-approval never --concurrency 3
```

Finally, the additional infrastructure can be removed with:
```shell
cdk destroy "*" --c contextId=demo-addon1
```

## Appendix

### How is an Authorization header set for requests from the Replayer to the target cluster?

The Replayer documentation [here](../../../TrafficCapture/trafficReplayer/README.md#authorization-header-for-replayed-requests) explains the reasoning the Replayer uses to determine what auth header it should use when replaying requests to the target cluster.

As it relates to this CDK, the two main avenues for setting an explicit auth header for the Replayer are through the `trafficReplayerEnableClusterFGACAuth` and `trafficReplayerExtraArgs` options
1. The `trafficReplayerEnableClusterFGACAuth` option will utilize the `--auth-header-user-and-secret` parameter of the Replayer service to create a basic auth header with a username and AWS Secrets Manager secret value. This option requires that a Fine Grained Access Control (FGAC) user be configured (see `fineGrainedManagerUserName` and `fineGrainedManagerUserSecretManagerKeyARN` CDK context options [here](./options.md)) or is running in demo mode (see `enableDemoAdmin` CDK context option).
2. The `trafficReplayerExtraArgs` option allows a user to directly specify the Replayer parameter they want to use for setting the auth header. For example to enable SigV4 as the auth header for an OpenSearch service in us-east-1, a user could set this option to `--sigv4-auth-header-service-region es,us-east-1`

### Common Deployment Errors

**Problem**: 
```
ERROR: failed to solve: public.ecr.aws/sam/build-nodejs18.x: pulling from host public.ecr.aws failed with status code [manifests latest]: 403 Forbidden
```
**Resolution**: </br>
Follow the warning steps here: https://docs.aws.amazon.com/AmazonECR/latest/public/public-registries.html#public-registry-concepts
```
If you've previously authenticated to Amazon ECR Public, if your auth token has expired you may receive an authentication error when attempting to do unauthenticated docker pulls from Amazon ECR Public. 
To resolve this issue, it may be necessary to run docker logout public.ecr.aws to avoid the error. This will result in an unauthenticated pull. 
```

**Problem**:
```
error TS2345: Argument of type '{ clusterName: string; vpc: IVpc; }' is not assignable to parameter of type 'ClusterAttributes'.
```
**Resolution**: </br>
An older version of the CDK CLI is being used which required additional ClusterAttributes. This error should resolve upon updating the CDK CLI
```
npm install -g aws-cdk@latest
```

### Useful CDK commands

* `npm run build`   compile typescript to js
* `npm run watch`   watch for changes and compile
* `npm run test`    perform the jest unit tests
* `cdk ls`          list all stacks in the app
* `cdk deploy "*"`  deploy all stacks to your default AWS account/region
* `cdk diff`        compare deployed stack with current state
* `cdk synth`       emits the synthesized CloudFormation template
