import groovy.json.JsonOutput

// Note: This integ test exists to verify that Capture and Replay can be ran independently of other migrations

def call(Map config = [:]) {
    def sourceContextId = 'source-single-node-ec2'
    def migrationContextId = 'migration-default'

    def jsonTransformationConfig = [
            [
                    TypeMappingSanitizationTransformerProvider: [
                            sourceProperties: [
                                    version: [
                                            major: 7,
                                            minor: 10
                                    ]
                            ]
                    ]
            ],
    ]
    def transformationConfigString = JsonOutput.toJson(jsonTransformationConfig)
    def transformersConfigArg = transformationConfigString.bytes.encodeBase64().toString()


    def source_cdk_context = """
    {
      "source-single-node-ec2": {
        "suffix": "ec2-source-<STAGE>",
        "networkStackSuffix": "ec2-source-<STAGE>",
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
        "restrictServerAccessTo": "0.0.0.0/0",
        "enableImdsCredentialRefresh": true
      }
    }
    """
    def migration_cdk_context = """
    {
      "migration-default": {
        "stage": "<STAGE>",
        "vpcId": "<VPC_ID>",
        "engineVersion": "OS_2.19",
        "domainName": "os-cluster-<STAGE>",
        "dataNodeCount": 2,
        "openAccessPolicyEnabled": true,
        "domainRemovalPolicy": "DESTROY",
        "artifactBucketRemovalPolicy": "DESTROY",
        "trafficReplayerServiceEnabled": true,
        "trafficReplayerExtraArgs": "--speedup-factor 10.0 --transformer-config-encoded $transformersConfigArg",
        "reindexFromSnapshotServiceEnabled": true,
        "sourceClusterEndpoint": "<SOURCE_CLUSTER_ENDPOINT>",
        "tlsSecurityPolicy": "TLS_1_2",
        "enforceHTTPS": true,
        "nodeToNodeEncryptionEnabled": true,
        "encryptionAtRestEnabled": true,
        "vpcEnabled": true,
        "vpcAZCount": 2,
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
            defaultStageId: 'aws-integ',
            jobName: 'traffic-replay-default-e2e-test',
            //deployStep: {
            //    echo 'Custom Test Step'
            //}
    )
}
