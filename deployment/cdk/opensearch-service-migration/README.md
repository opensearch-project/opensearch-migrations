# OpenSearch Service Migration CDK

This directory contains the IaC CDK solution for deploying an OpenSearch Domain as well as the infrastructure for the Migration solution. Users have the ability to easily deploy their Domain using default values or provide [configuration options](#Configuration-Options) for a more customized setup. The goal of this repo is not to become a one-size-fits-all solution for users. Supporting this would be unrealistic, and likely conflicting at times, when considering the needs of many users. Rather this code base should be viewed as a starting point for users to use and add to individually as their custom use case requires.

## Getting Started

### Install Prerequisites

###### Docker
Docker is used by CDK to build container images. If not installed, follow the steps [here](https://docs.docker.com/engine/install/) to set up. Later versions are recommended.
###### Git
Git is used by the opensearch-migrations repo to fetch associated repositories (such as the traffic-comparator repo) for constructing their respective Dockerfiles. Steps to set up can be found [here](https://github.com/git-guides/install-git).
###### Java 11
Java is used by the opensearch-migrations repo and Gradle, its associated build tool. The current required version is Java 11.

### Project required setup

1- It is necessary to run `npm install` within this current directory to install required packages that this app and CDK need for operation.

2- Creating Dockerfiles, this project needs to build the required Dockerfiles that the CDK will use in its services. From the `TrafficCapture` directory the following command can be ran to build these files
```shell
./gradlew :dockerSolution:buildDockerImages
```
More details can be found [here](../../TrafficCapture/dockerSolution/README.md)

### First time using CDK in this region?

If this is your first experience with CDK, follow the steps below to get started:

1- Install the **CDK CLI** tool by running:
```shell
npm install -g aws-cdk
```

2- Configure the desired **[AWS credentials](https://docs.aws.amazon.com/cdk/v2/guide/getting_started.html#getting_started_prerequisites)**, as these will dictate the region and account used for deployment.

3- **Bootstrap CDK**: if you have not run CDK previously in the configured region of you account, it is necessary to run the following command to set up a small CloudFormation stack of resources that CDK needs to function within your account

```
cdk bootstrap
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
**Note**: Separate deployments within the same account and region should use unique `stage` context values to avoid resource naming conflicts when deploying (Except in the multiple replay scenario stated [here](#how-to-run-multiple-replayer-scenarios)) 

Depending on your use-case, you may choose to provide options from both the `cdk.context.json` and the CDK CLI, in which case it is important to know the precedence level for context values. The below order shows these levels with values being passed by the CDK CLI having the most importance
1. CDK CLI passed context values (highest precedence)
2. Created `cdk.context.json` in the same directory as this README
3. Existing `default-values.json` in the same directory as this README

## Executing Commands on a Deployed Service

Once a service has been deployed, a command shell can be opened for that service's container. If the SSM Session Manager plugin is not installed, it should be when prompted from the below exec command.
```shell
# ./ecsExec.sh <service-name> <stage> <region>
./ecsExec.sh migration-console dev us-east-1
```

## Testing the deployed solution

Once the solution is deployed, the easiest way to test the solution is to exec into the `migration-console` service container and run an opensearch-benchmark workload through to simulate incoming traffic, as the following steps illustrate

```shell
# Exec into container
./ecsExec.sh migration-console dev us-east-1

# Run opensearch-benchmark workload (i.e. geonames, nyc_taxis, http_logs)
./runTestBenchmarks.sh
```

After the benchmark has been run, the indices and documents of the source and target clusters can be checked from the same migration-console container to confirm
```shell
# Check doc counts and indices for both source and target cluster
./catIndices.sh
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
Note that the default retention policy for the OpenSearch Domain is to RETAIN this resource when the stack is deleted, and in order to delete the Domain on stack deletion the `domainRemovalPolicy` would need to be set to `DESTROY`. Otherwise, the Domain can be manually deleted through the AWS console or through other means such as the AWS CLI.

## How to run multiple Replayer scenarios
WIP

## Appendix

### How is an Authorization header set for requests from the Replayer to the target cluster?

See Replayer explanation [here](../../../TrafficCapture/trafficReplayer/README.md#authorization-header-for-replayed-requests)

### Useful CDK commands

* `npm run build`   compile typescript to js
* `npm run watch`   watch for changes and compile
* `npm run test`    perform the jest unit tests
* `cdk ls`          list all stacks in the app
* `cdk deploy "*"`  deploy all stacks to your default AWS account/region
* `cdk diff`        compare deployed stack with current state
* `cdk synth`       emits the synthesized CloudFormation template
