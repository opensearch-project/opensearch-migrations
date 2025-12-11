# OpenSearch Service Migration CDK for ECS Deployments

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

2- Creating Docker Images: This project needs to build the required Docker images that the CDK will use in its services. These images can be built by running the below script which is located in the same directory as this README
```shell
./buildDockerImages.sh
```
More details can be found [here](../../../TrafficCapture/dockerSolution/README.md)

3- Configure the desired **[AWS credentials](https://docs.aws.amazon.com/cdk/v2/guide/getting_started.html#getting_started_prerequisites)**, as these will dictate the region and account used for deployment.

4- There is a known issue where service linked roles fail to get applied when deploying certain AWS services for the first time in an account. This can be resolved by simply deploying again (for each failing role) or avoided entirely by creating the service linked role initially like seen below:
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

### Which configuration options should I use?
Update the file named `cdk.context.json` in this directory to select migration options for metadata, historical backfill or traffic capture and replay, see details 
[here](./options.md).

### How is the CDK context used in this solution?
This project uses CDK context parameters to configure deployments. These context values will dictate the composition of your stacks as well as which stacks get deployed.

The full list of available configuration options for this project are listed [here](./options.md). Each option can be provided as an empty string `""` or simply not included, and in each of these 'empty' cases the option will use the project default value (if it exists) or CloudFormation's default value.

Depending on your use-case, you may choose to provide options from both the `cdk.context.json` and the CDK CLI, in which case it is important to know the precedence level for context values. The below order shows these levels with values being passed by the CDK CLI having the most importance
1. CDK CLI passed context values, e.g. --c stage=dev2 (highest precedence)
2. Created `cdk.context.json` in the same directory as this README
3. Existing `default-values.json` in the same directory as this README

### Deploying the demo solution
A set of demo context values (using the `demo-deploy` label) has been set in the `cdk.context.json` located in this directory, which can be customized or used as is for a quickstart demo solution.

This demo solution can be deployed with the following command:
```shell
./deploy.sh demo-deploy
```

Additionally, another context block in the `cdk.context.json` could be created with say the label `uat-deploy` with its own custom context configuration and be deployed with the command:
```shell
./deploy.sh uat-deploy
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

## Accessing the Migration Console

The Migration Console is a deployed ECS service container in this solution that should be accessed for managing/executing different phases of a migration

To open a shell on the Migration Console container execute the below command. If the SSM Session Manager plugin is not installed, it should be installed when prompted from the below exec command.
```shell
# ./accessContainer.sh <service-name> <stage> <region>
./accessContainer.sh migration-console dev us-east-1
```
To be able to execute this command the user will need to have their AWS credentials configured for their environment. It is also worth noting that the identity used should have permissions to `ecs:ExecuteCommand` on both the ECS cluster and the given container task, for which an Administrator or Deployment role may already have. An example IAM policy that could be added, which allows accessing any of the containers on the ECS cluster, can be seen below:
```
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

## Appendix

### How to run multiple Traffic Replayer scenarios
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
    "trafficReplayerServiceEnabled": true
  }
```
And then deploy this additional infrastructure with the command:
```shell
./deploy.sh demo-addon1
```

Finally, the additional infrastructure can be removed with:
```shell
cdk destroy "*" --c contextId=demo-addon1
```


### How is an Authorization header set for requests from the Replayer to the target cluster?

The Replayer documentation [here](../../../TrafficCapture/trafficReplayer/README.md#authorization-header-for-replayed-requests) explains the reasoning the Replayer uses to determine what auth header it should use when replaying requests to the target cluster.

As it relates to this CDK, the `targetCluster` configuration option (specifically the `auth` element) that a user provides will dictate which auth the Migration tools will use for communicating with the target cluster

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
