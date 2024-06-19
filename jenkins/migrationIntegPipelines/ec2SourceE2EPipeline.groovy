// Note:
// 1. We are using an existing common VPC for now until we move to a proper Jenkins accounts and can create a setup without
//    public subnets as well as request an extension to allow more than 5 VPCs per region
// 2. There is a still a manual step needed on the EC2 source load balancer to replace its security group rule which allows all traffic (0.0.0.0/0) to
//    allow traffic for the relevant service security group. This needs a better story around accepting user security groups in our Migration CDK.

@Library('migrations-shared-lib@checkin-jenkinsfile')_

def source_cdk_context = """
    {
      "source-single-node-ec2": {
        "suffix": "ec2-source-<STAGE>",
        "networkStackSuffix": "ec2-source-<STAGE>",
        "vpcId": "vpc-07d3e089b8e9139e4",
        "distVersion": "7.10.2",
        "cidr": "12.0.0.0/16",
        "distributionUrl": "https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-oss-7.10.2-linux-x86_64.tar.gz",
        "captureProxyEnabled": true,
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
    """
def migration_cdk_context = """
    {
      "migration-default": {
        "stage": "<STAGE>",
        "vpcId": "vpc-07d3e089b8e9139e4",
        "engineVersion": "OS_2.11",
        "domainName": "os-cluster-<STAGE>",
        "dataNodeCount": 2,
        "openAccessPolicyEnabled": true,
        "domainRemovalPolicy": "DESTROY",
        "artifactBucketRemovalPolicy": "DESTROY",
        "trafficReplayerExtraArgs": "--speedup-factor 10.0",
        "fetchMigrationEnabled": true,
        "reindexFromSnapshotServiceEnabled": true,
        "sourceClusterEndpoint": "<SOURCE_CLUSTER_ENDPOINT>",
        "dpPipelineTemplatePath": "../../../test/dp_pipeline_aws_integ.yaml",
        "migrationConsoleEnableOSI": true,
        "migrationAPIEnabled": true
      }
    }
"""

defaultIntegPipeline(
        sourceContext: source_cdk_context,
        migrationContext: migration_cdk_context,
        sourceContextId: 'source-single-node-ec2',
        migrationContextId: 'migration-default',
        gitUrl: 'https://github.com/lewijacn/opensearch-migrations.git',
        gitBranch: 'checkin-jenkinsfile',
        stageId: 'test'
//        checkout: {
//            echo 'Custom Test Step'
//            git branch: "${env.GIT_BRANCH}", url: "${env.GIT_URL}"
//        }
)
