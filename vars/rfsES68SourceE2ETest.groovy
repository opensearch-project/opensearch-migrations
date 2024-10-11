
def call(Map config = [:]) {
    def sourceContextId = 'source-single-node-ec2'
    def migrationContextId = 'migration-rfs'
    def source_cdk_context = """
        {
          "source-single-node-ec2": {
            "suffix": "ec2-source-<STAGE>",
            "networkStackSuffix": "ec2-source-<STAGE>",
            "distVersion": "6.8.23",
            "distributionUrl": "https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-oss-6.8.23.tar.gz",
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
    """
    def migration_cdk_context = """
        {
          "migration-rfs": {
            "stage": "<STAGE>",
            "vpcId": "<VPC_ID>",
            "engineVersion": "OS_2.11",
            "domainName": "os-cluster-<STAGE>",
            "dataNodeCount": 2,
            "openAccessPolicyEnabled": true,
            "domainRemovalPolicy": "DESTROY",
            "artifactBucketRemovalPolicy": "DESTROY",
            "trafficReplayerServiceEnabled": false,
            "reindexFromSnapshotServiceEnabled": true,
            "sourceClusterEndpoint": "<SOURCE_CLUSTER_ENDPOINT>",
            "sourceCluster": {
                "endpoint": "<SOURCE_CLUSTER_ENDPOINT>",
                "auth": {"type": "none"},
                "version": "ES_6.8.23"
            },
            "tlsSecurityPolicy": "TLS_1_2",
            "enforceHTTPS": true,
            "nodeToNodeEncryptionEnabled": true,
            "encryptionAtRestEnabled": true,
            "vpcEnabled": true,
            "vpcAZCount": 2,
            "domainAZCount": 2,
            "mskAZCount": 2,
            "migrationAssistanceEnabled": true,
            "replayerOutputEFSRemovalPolicy": "DESTROY",
            "migrationConsoleServiceEnabled": true,
            "otelCollectorEnabled": true
          }
        }
    """

    defaultIntegPipeline(
            sourceContext: source_cdk_context,
            migrationContext: migration_cdk_context,
            sourceContextId: sourceContextId,
            migrationContextId: migrationContextId,
            defaultStageId: 'rfs-integ-es68',
            skipCaptureProxyOnNodeSetup: true,
            jobName: 'rfs-es68source-e2e-test',
            integTestCommand: '/root/lib/integ_test/integ_test/backfill_tests_es68.py'
    )
}
