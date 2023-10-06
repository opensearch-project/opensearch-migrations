### Deployment
This directory is aimed at housing deployment/distribution methods for various migration related images and infrastructure. It is not specific to any given platform and should be expanded to more platforms as needed. 


### Deploying Migration solution to AWS

**Note**: These features are still under development and subject to change

Detailed instructions for deploying the CDK and setting up its prerequisites can be found in the opensearch-service-migration [README](./cdk/opensearch-service-migration/README.md). This could involve setting CDK context parameters to customize your OpenSearch Service Domain and VPC as well as setting needed migration parameters. A sample **testing** `cdk.context.json` for an E2E migration setup could be:
```
{
  "engineVersion": "OS_1.3",
  "domainName": "aos-test-domain",
  "dataNodeCount": 2,
  "vpcEnabled": true,
  "availabilityZoneCount": 2,
  "openAccessPolicyEnabled": true,
  "domainRemovalPolicy": "DESTROY",
  "migrationAssistanceEnabled": true,
  "MSKARN": "arn:aws:kafka:us-east-1:123456789123:cluster/logging-msk-cluster/123456789-12bd-4f34-932b-52060474aa0f-7",
  "MSKBrokers": [
    "b-2-public.loggingmskcluster.abc123.c7.kafka.us-east-1.amazonaws.com:9198",
    "b-1-public.loggingmskcluster.abc123.c7.kafka.us-east-1.amazonaws.com:9198"
  ],
  "MSKTopic": "logging-traffic-topic"
}
```



Once prerequisites are met and context parameters are set, deploying the resources to AWS can be done simply by running the following command:
```
cdk deploy "*"
```