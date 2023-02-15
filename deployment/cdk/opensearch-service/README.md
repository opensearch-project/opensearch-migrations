# OpenSearch Service Domain CDK

### Getting Started

If this is your first time using CDK in this region, will need to `cdk bootstrap` to setup required CDK resources for deployment

Also ensure you have configured the desired [AWS credentials](https://docs.aws.amazon.com/cdk/v2/guide/getting_started.html#getting_started_prerequisites), as these will dictate the region and account used for deployment

A `CDK_DEPLOYMENT_STAGE` environment variable should also be set to assist in naming resources and preventing collisions. Typically, this would be set to values such as `dev`, `gamma`, or `PROD` and will be used to distinguish AWS resources for a given region and deployment stage. For example the CloudFormation stack may be named like `OpenSearchServiceDomain-dev-us-east-1`

### Deploying your Domain Stack
Before deploying your Domain stack you should fill in any desired context parameters that will dictate the composition of your OpenSearch Service Domain

This can be accomplished by providing these options in a `cdk.context.json` file

As well as by passing the context options you want to change as options in the CDK CLI
```
cdk deploy --c domainName='cdk-os-service-domain' --c engineVersion="OS_1_3_6" --c dataNodeType="r6g.large.search" --c dataNodeCount=1
```
* Note that these context parameters can also be passed to `cdk synth` and `cdk bootstrap` commands to simulate similar scenarios

Depending on your use-case, you may choose to provide options from both the `cdk.context.json` and the CDK CLI, in which case it is important to know the precedence level for context values. The below order shows these levels with values placed in the `cdk.context.json` having the most importance
1. Created `cdk.context.json` in the same directory as this README
2. CDK CLI passed context values
3. Existing `default-values.json` in the same directory as this README


### Configuration Options

The available configuration options are listed below. The vast majority of these options do not need to be provided, with only `domainName` and `engineVersion` being required. All non-required options can be provided as an empty string `""` or simply not included, and in each of these cases the option will be allocated with the CDK Domain default value

Users are encouraged to customize the deployment by changing the CDK TypeScript as needed. The configuration-by-context option that is depicted here is primarily provided for testing/development purposes, and users may find it easier to adjust the TS here rather than say wrangling a complex JSON object through a context option

Additional context on some of these options, can also be found in the Domain construct [documentation](https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.aws_opensearchservice.Domain.html)

**It should be noted that limited testing has been conducted solely in the us-east-1 region, and some items like instance-type might be biased**

| Name                                      | Required | Type         | Example                                                                                                                                                                                                                      | Description                                                                                                                                                                                                          |
|-------------------------------------------|----------|--------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| engineVersion                             | true     | string       | "OS_1.3"                                                                                                                                                                                                                     |                                                                                                                                                                                                                      |
| domainName                                | true     | string       | "cdk-os-service-domain"                                                                                                                                                                                                      | Name to use for the OpenSearch Service Domain                                                                                                                                                                        |
| dataNodeType                              | false    | string       | "r6g.large.search"                                                                                                                                                                                                           |                                                                                                                                                                                                                      |
| dataNodeCount                             | false    | number       | 1                                                                                                                                                                                                                            |                                                                                                                                                                                                                      |
| dedicatedManagerNodeType                  | false    | string       | "r6g.large.search"                                                                                                                                                                                                           |                                                                                                                                                                                                                      |
| dedicatedManagerNodeCount                 | false    | number       | 3                                                                                                                                                                                                                            |                                                                                                                                                                                                                      |
| warmNodeType                              | false    | string       | "ultrawarm1.medium.search"                                                                                                                                                                                                   |                                                                                                                                                                                                                      |
| warmNodeCount                             | false    | number       | 3                                                                                                                                                                                                                            |                                                                                                                                                                                                                      |
| accessPolicies                            | false    | JSON         | {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::123456789123:user/test-user"},"Action":"es:ESHttp*","Resource":"arn:aws:es:us-east-1:123456789123:domain/cdk-os-service-domain/*"}]} |                                                                                                                                                                                                                      |
| useUnsignedBasicAuth                      | false    | boolean      | false                                                                                                                                                                                                                        |                                                                                                                                                                                                                      |
| fineGrainedManagerUserARN                 | false    | string       | "arn:aws:iam::123456789123:user/test-user"                                                                                                                                                                                   | Fine grained access control also requires nodeToNodeEncryptionEnabled and encryptionAtRestEnabled to be enabled. <br/> Either fineGrainedMasterUserARN or fineGrainedMasterUserName should be enabled, but not both. |
| fineGrainedManagerUserName                | false    | string       | "admin"                                                                                                                                                                                                                      |                                                                                                                                                                                                                      |
| fineGrainedManagerUserSecretManagerKeyARN | false    | string       | "arn:aws:secretsmanager:us-east-1:123456789123:secret:master-user-os-pass-123abc"                                                                                                                                            |                                                                                                                                                                                                                      |
| enforceHTTPS                              | false    | boolean      | true                                                                                                                                                                                                                         |                                                                                                                                                                                                                      |
| tlsSecurityPolicy                         | false    | string       | "TLS_1_2"                                                                                                                                                                                                                    |                                                                                                                                                                                                                      |
| ebsEnabled                                | false    | boolean      | true                                                                                                                                                                                                                         | Some instance types (i.e. r6gd) require that EBS be disabled                                                                                                                                                         |
| ebsIops                                   | false    | number       | 4000                                                                                                                                                                                                                         |                                                                                                                                                                                                                      |
| ebsVolumeSize                             | false    | number       | 15                                                                                                                                                                                                                           |                                                                                                                                                                                                                      |
| ebsVolumeType                             | false    | string       | "GP3"                                                                                                                                                                                                                        |                                                                                                                                                                                                                      |
| encryptionAtRestEnabled                   | false    | boolean      | true                                                                                                                                                                                                                         |                                                                                                                                                                                                                      |
| encryptionAtRestKmsKeyARN                 | false    | string       | "arn:aws:kms:us-east-1:123456789123:key/abc123de-4888-4fa7-a508-3811e2d49fc3"                                                                                                                                                | If encryptionAtRestEnabled is enabled and this value is not provided, the default KMS key for OpenSearch Service will be used                                                                                        |
| loggingAppLogEnabled                      | false    | boolean      | true                                                                                                                                                                                                                         |                                                                                                                                                                                                                      |
| loggingAppLogGroupARN                     | false    | string       | "arn:aws:logs:us-east-1:123456789123:log-group:test-log-group:*"                                                                                                                                                             | If not provided and logs are enabled, a CloudWatch log group will be created                                                                                                                                         |
| nodeToNodeEncryptionEnabled               | false    | boolean      | true                                                                                                                                                                                                                         |                                                                                                                                                                                                                      |
| vpcId                                     | false    | string       | "vpc-123456789abcdefgh"                                                                                                                                                                                                      |                                                                                                                                                                                                                      |
| domainRemovalPolicy                       | false    | string       | "RETAIN"                                                                                                                                                                                                                     |                                                                                                                                                                                                                      |


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
  "vpcId": "",
  "domainRemovalPolicy": ""
}

```
Some configuration options available in other solutions (listed below) which enable/disable specific features do not exist in the current native CDK Domain construct. These options are inferred based on the presence or absence of related fields (i.e. if dedicatedMasterNodeCount is set to 1 it is inferred that dedicated master nodes should be enabled). These options are normally disabled by default, allowing for this inference.
```
"dedicatedMasterNodeEnabled": "X",
"warmNodeEnabled": "X",
"fineGrainedAccessControlEnabled": "X",
"internalUserDatabaseEnabled": "X"
```

### Tearing down CDK Stack
To remove the stack which gets created during deployment, which contains our created resources like our Domain and any other resources created from enabled features (such as a CloudWatch log group), we can execute
```
cdk destroy
```
Note that the default retention policy for the OpenSearch Domain is to RETAIN this resource when the stack is deleted, and in order to delete the Domain on stack deletion the `domainRemovalPolicy` would need to be set to `DESTROY`. Otherwise, the Domain can be manually deleted through the AWS console or through other means such as the AWS CLI.

### Useful CDK commands

* `npm run build`   compile typescript to js
* `npm run watch`   watch for changes and compile
* `npm run test`    perform the jest unit tests
* `cdk deploy`      deploy this stack to your default AWS account/region
* `cdk diff`        compare deployed stack with current state
* `cdk synth`       emits the synthesized CloudFormation template
