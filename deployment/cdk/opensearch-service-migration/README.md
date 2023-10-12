# OpenSearch Service Domain CDK

This repo contains an IaC CDK solution for deploying an OpenSearch Service Domain. Users have the ability to easily deploy their Domain using default values or provide [configuration options](#Configuration-Options) for a more customized setup. The goal of this repo is not to become a one-size-fits-all solution for users. Supporting this would be unrealistic, and likely conflicting at times, when considering the needs of many users. Rather this code base should be viewed as a starting point for users to use and add to individually as their custom use case requires.

### Getting Started

#### Project required setup

1- It is necessary to run `npm install` within this current directory to install required packages that this app and CDK need for operation.

2- Set the `CDK_DEPLOYMENT_STAGE` environment variable to assist in naming resources and preventing collisions. Typically, this would be set to a value such as `dev`, `gamma`, `Wave1`, `PROD` and will be used to distinguish AWS resources for a given region and deployment stage. For example the CloudFormation stack may be named like `OSServiceDomain-dev-us-east-1`. This stage environment variable should only be used for the disambiguation of user resources.

#### First time using CDK?

If this is your first experience with CDK, follow the steps below to get started:

1- Install the **CDK CLI** tool by running:
```
npm install -g aws-cdk
```

2- Configure the desired **[AWS credentials](https://docs.aws.amazon.com/cdk/v2/guide/getting_started.html#getting_started_prerequisites)**, as these will dictate the region and account used for deployment.

3- **Bootstrap CDK**: if you have not run CDK previously in the configured region of you account, it is necessary to run the following command to set up a small CloudFormation stack of resources that CDK needs to function within your account

```
cdk bootstrap
```

Further CDK documentation [here](https://docs.aws.amazon.com/cdk/v2/guide/cli.html)

### Deploying your CDK
Before deploying your CDK you should fill in any desired context parameters that will dictate the composition of your OpenSearch Service Domain

This can be accomplished by providing these options in a `cdk.context.json` file and then deploying like so:
```
cdk deploy "*"
```

Or by passing the context options you want to change as options in the CDK CLI
```
cdk deploy "*" --c domainName="os-service-domain" --c engineVersion="OS_1_3_6" --c dataNodeType="r6g.large.search" --c dataNodeCount=1
```
* Note that these context parameters can also be passed to `cdk synth` and `cdk bootstrap` commands to simulate similar scenarios

Depending on your use-case, you may choose to provide options from both the `cdk.context.json` and the CDK CLI, in which case it is important to know the precedence level for context values. The below order shows these levels with values being passed by the CDK CLI having the most importance
1. CDK CLI passed context values (highest precedence)
2. Created `cdk.context.json` in the same directory as this README
3. Existing `default-values.json` in the same directory as this README

### Stack Breakdown
This CDK has been structured to allow multiple stacks to be deployed out-of-the-box, which allows an easy entrance door for users to get started and add additional stacks as they need. Each of these stacks are deployed independently in CloudFormation, with only the Domain stack being required.

#### Domain Stack (OSServiceDomainCDKStack-STAGE-REGION)
This is the core required stack of this CDK which is responsible for deploying the OpenSearch Service Domain and associated resources such as CloudWatch log groups for Domain logging.

#### Network Stack (OSServiceNetworkCDKStack-STAGE-REGION)
This optional stack will be used when the Domain is configured to be placed inside a VPC and will contain resources related to the networking of this VPC such as Security Groups and Subnets. It has a dependency on the Domain stack.

#### Migration Assistance Stack (OSServiceMigrationCDKStack-STAGE-REGION)
This optional stack is used to house the migration assistance resources which are in the process of being developed to assist in migrating to an OpenSearch domain. It has dependencies on both the Domain and Network stacks.

#### Historical Capture Stack (OSServiceHistoricalCDKStack-STAGE-REGION)
This optional exploratory stack sets up a deployable Logstash ECS cluster for historical data migration. It is experimental and should only be used for development purposes. It has dependencies on both the Domain and Network stacks.

### Configuration Options

The available configuration options are listed [here](./options.md). The vast majority of these options do not need to be provided, with only `domainName` and `engineVersion` being required. All non-required options can be provided as an empty string `""` or simply not included, and in each of these cases the option will be allocated with the CDK Domain default value

Users are encouraged to customize the deployment by changing the CDK TypeScript as needed. The configuration-by-context option that is depicted here is primarily provided for testing/development purposes, and users may find it easier to adjust the TS here rather than say wrangling a complex JSON object through a context option

Additional context on some of these options, can also be found in the Domain construct [documentation](https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.aws_opensearchservice.Domain.html)

**It should be noted that limited testing has been conducted solely in the us-east-1 region, and some items like instance-type examples might be biased**

A template `cdk.context.json` to be used to fill in these values is below:
```
{
  "engineVersion": "",
  "domainName": "",
  "dataNodeType": "",
  "dataNodeCount": "",
  "dedicatedManagerNodeType": "",
  "dedicatedManagerNodeCount": "",
  "warmNodeType": "",
  "warmNodeCount": "",
  "accessPolicies": "",
  "useUnsignedBasicAuth": "",
  "fineGrainedManagerUserARN": "",
  "fineGrainedManagerUserName": "",
  "fineGrainedManagerUserSecretManagerKeyARN": "",
  "enableDemoAdmin": "",
  "enforceHTTPS": "",
  "tlsSecurityPolicy": "",
  "ebsEnabled": "",
  "ebsIops": "",
  "ebsVolumeSize": "",
  "ebsVolumeType": "",
  "encryptionAtRestEnabled": "",
  "encryptionAtRestKmsKeyARN": "",
  "loggingAppLogEnabled": "",
  "loggingAppLogGroupARN": "",
  "nodeToNodeEncryptionEnabled": "",
  "vpcEnabled": "",
  "vpcId": "",
  "vpcSubnetIds": "",
  "vpcSecurityGroupIds": "",
  "availabilityZoneCount": "",
  "openAccessPolicyEnabled": "",
  "domainRemovalPolicy": "",
  "mskARN": "",
  "mskEnablePublicEndpoints": "",
  "mskBrokerNodeCount": ""
}

```
Some configuration options available in other solutions (listed below) which enable/disable specific features do not exist in the current native CDK Domain construct. These options are inferred based on the presence or absence of related fields (i.e. if dedicatedMasterNodeCount is set to 1 it is inferred that dedicated master nodes should be enabled). These options are normally disabled by default, allowing for this inference.
```
"dedicatedMasterNodeEnabled": "X",
"warmNodeEnabled": "X",
"fineGrainedAccessControlEnabled": "X",
"internalUserDatabaseEnabled": "X"
```

### Tearing down CDK
To remove all the CDK stack(s) which get created during deployment we can execute
```
cdk destroy "*"
```
Or to remove an individual stack we can execute
```
cdk destroy opensearchDomainStack
```
Note that the default retention policy for the OpenSearch Domain is to RETAIN this resource when the stack is deleted, and in order to delete the Domain on stack deletion the `domainRemovalPolicy` would need to be set to `DESTROY`. Otherwise, the Domain can be manually deleted through the AWS console or through other means such as the AWS CLI.

### Useful CDK commands

* `npm run build`   compile typescript to js
* `npm run watch`   watch for changes and compile
* `npm run test`    perform the jest unit tests
* `cdk ls`          list all stacks in the app
* `cdk deploy "*"`  deploy all stacks to your default AWS account/region
* `cdk diff`        compare deployed stack with current state
* `cdk synth`       emits the synthesized CloudFormation template
