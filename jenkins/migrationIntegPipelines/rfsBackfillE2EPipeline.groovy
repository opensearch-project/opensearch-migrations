// Note:
// 1. We are using an existing common VPC that we provide through a 'vpcId' parameter on the pipeline for now until we move
//    to a proper Jenkins accounts and can create a setup without public subnets as well as request an extension to allow more than 5 VPCs per region
// 2. There is a still a manual step needed on the EC2 source load balancer to replace its security group rule which allows all traffic (0.0.0.0/0) to
//    allow traffic for the relevant service security group. This needs a better story around accepting user security groups in our Migration CDK.

def sourceContextId = 'source-single-node-ec2'
def migrationContextId = 'migration-rfs'
def gitUrl = 'https://github.com/opensearch-project/opensearch-migrations.git'
def gitBranch = 'main'
def stageId = 'rfs-integ'
def source_cdk_context = """
    {
      "source-single-node-ec2": {
        "suffix": "ec2-source-<STAGE>",
        "networkStackSuffix": "ec2-source-<STAGE>",
        "vpcId": "$vpcId",
        "distVersion": "7.10.2",
        "distributionUrl": "https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-oss-7.10.2-linux-x86_64.tar.gz",
        "captureProxyEnabled": false,
        "securityDisabled": true,
        "minDistribution": false,
        "cpuArch": "x64",
        "isInternal": false,
        "singleNodeCluster": true,
        "networkAvailabilityZones": 2,
        "dataNodeCount": 1,
        "managerNodeCount": 0,
        "serverAccessType": "securityGroupId",
        "restrictServerAccessTo": "sg-07ff03367fb0c372f"
      }
    }
"""
def migration_cdk_context = """
    {
      "migration-rfs": {
        "stage": "<STAGE>",
        "vpcId": "$vpcId",
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
"""
// This library declaration likely needs to change in the near future to be more dynamic in picking up library
// changes that may have happened on the given branch being tested
@Library("migrations-shared-lib@checkin-jenkinsfile")_

defaultIntegPipeline(
        sourceContext: source_cdk_context,
        migrationContext: migration_cdk_context,
        sourceContextId: sourceContextId,
        migrationContextId: migrationContextId,
        gitUrl: gitUrl,
        gitBranch: gitBranch,
        stageId: stageId
        //deployStep: {
        //    echo 'Custom Test Step'
        //}
)
