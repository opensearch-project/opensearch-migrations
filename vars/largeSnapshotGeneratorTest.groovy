import groovy.json.JsonOutput

def call(Map config = [:]) {
    def migrationContextId = 'document-multiplier-rfs'
    def time = new Date().getTime()
    def testUniqueId = "document_multiplier_${time}_${currentBuild.number}"

    def docTransformerPath = "/shared-logs-output/test-transformations/transformation.json"
    
    // Map the cluster version parameter to the actual engine version
    def engineVersion = ""
    def distributionVersion = ""
    def distributionType = ""
    switch (params.CLUSTER_VERSION) {
        case 'es5x':
            engineVersion = "ELASTICSEARCH_5.6"
            distributionVersion = "5.6"
            distributionType = "elasticsearch"
            break
        case 'es6x':
            engineVersion = "ELASTICSEARCH_6.7"
            distributionVersion = "6.7"
            distributionType = "elasticsearch"
            break
        case 'es7x':
            engineVersion = "ELASTICSEARCH_7.10"
            distributionVersion = "7.10"
            distributionType = "elasticsearch"
            break
        case 'os2x':
            engineVersion = "OPENSEARCH_2.11"
            distributionVersion = "2.11"
            distributionType = "opensearch"
            break
        default:
            throw new RuntimeException("Unsupported CLUSTER_VERSION: ${params.CLUSTER_VERSION}")
    }

   
    def migration_cdk_context = """
        {
          "document-multiplier-rfs": {
            "stage": "${params.STAGE}",
            "artifactBucketRemovalPolicy": "DESTROY",
            "captureProxyServiceEnabled": false,
            "targetClusterProxyServiceEnabled": false,
            "trafficReplayerServiceEnabled": false,
            "reindexFromSnapshotServiceEnabled": true,
            "reindexFromSnapshotExtraArgs": "--doc-transformer-config-file ${docTransformerPath}",
            "sourceClusterDeploymentEnabled": true,
            "sourceCluster": {
                "endpoint": "https://www.google.com",
                "auth": {
                    "type": "sigv4",
                    "region": "us-east-1",
                    "serviceSigningName": "es"
                },
                "version": "${engineVersion}"
            },
            "vpcEnabled": true,
            "vpcAZCount": 3,
            "migrationAssistanceEnabled": true,
            "replayerOutputEFSRemovalPolicy": "DESTROY",
            "migrationConsoleServiceEnabled": true,
            "otelCollectorEnabled": true,
            "engineVersion": "${engineVersion}",
            "distributionVersion": "${distributionVersion}",
            "osDistribution": "${distributionType}",
            "distributionType": "${distributionType}",
            "domainName": "${params.CLUSTER_VERSION}-test-jenkins-${params.STAGE}",
            "dataNodeCount": 30,
            "dataNodeType": "i4i.8xlarge.search",
            "masterEnabled": true,
            "dedicatedManagerNodeCount": 3,
            "dedicatedManagerNodeType": "m7i.xlarge.search",
            "ebsEnabled": false,
            "openAccessPolicyEnabled": true,
            "domainRemovalPolicy": "DESTROY",
            "tlsSecurityPolicy": "TLS_1_2",
            "enforceHTTPS": true,
            "nodeToNodeEncryptionEnabled": true,
            "encryptionAtRestEnabled": true
            }
        }
    """

    def source_cdk_context = """
        {
          "source-single-node-ec2": {
            "suffix": "ec2-source-<STAGE>",
            "networkStackSuffix": "ec2-source-<STAGE>"
          }
        }
    """

    largeSnapshotPipeline(
            sourceContext: source_cdk_context,
            migrationContext: migration_cdk_context,
            migrationContextId: migrationContextId,
            defaultStageId: 'dev',
            skipCaptureProxyOnNodeSetup: true,
            skipSourceDeploy: false,
            jobName: 'k8s-large-snapshot-test',
            testUniqueId: testUniqueId,
            integTestCommand: '/root/lib/integ_test/integ_test/document_multiplier.py --config-file=/config/migration-services.yaml --log-cli-level=info',
            parameterDefaults: [
              NUM_SHARDS: params.NUM_SHARDS,
              MULTIPLICATION_FACTOR: params.MULTIPLICATION_FACTOR,
              BATCH_COUNT: params.BATCH_COUNT,
              DOCS_PER_BATCH: params.DOCS_PER_BATCH,
              BACKFILL_TIMEOUT_HOURS: params.BACKFILL_TIMEOUT_HOURS,
              LARGE_SNAPSHOT_RATE_MB_PER_NODE: params.LARGE_SNAPSHOT_RATE_MB_PER_NODE,
              RFS_WORKERS: params.RFS_WORKERS,
              CLUSTER_VERSION: params.CLUSTER_VERSION,
            ]
    )
}
