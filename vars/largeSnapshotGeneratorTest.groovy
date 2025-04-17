import groovy.json.JsonOutput

def call(Map config = [:]) {
    def migrationContextId = 'document-multiplier-rfs'
    def time = new Date().getTime()
    def testUniqueId = "document_multiplier_${time}_${currentBuild.number}"

    def docTransformerPath = "/shared-logs-output/test-transformations/transformation.json"
    
    // Map the cluster version parameter to the actual engine version
    def engineVersion = ""
    def distVersion = ""
    switch (params.CLUSTER_VERSION) {
        case 'es5x':
            engineVersion = "ES_5.6"
            distVersion = "5.6"
            break
        case 'es6x':
            engineVersion = "ES_6.7"
            distVersion = "6.7"
            break
        case 'es7x':
            engineVersion = "ES_7.10"
            distVersion = "7.10"
            break
        case 'os2x':
            engineVersion = "OS_2.11"
            distVersion = "2.11"
            break
        default:
            throw new RuntimeException("Unsupported CLUSTER_VERSION: ${params.CLUSTER_VERSION}")
    }

   
    def migration_cdk_context = """
        {
          "document-multiplier-rfs": {
            "stage": "${params.STAGE}",
            "region": "${params.REGION}",
            "artifactBucketRemovalPolicy": "DESTROY",
            "captureProxyServiceEnabled": false,
            "targetClusterProxyServiceEnabled": false,
            "trafficReplayerServiceEnabled": false,
            "reindexFromSnapshotServiceEnabled": true,
            "reindexFromSnapshotExtraArgs": "--doc-transformer-config-file ${docTransformerPath}",
            "sourceClusterDeploymentEnabled": false,
            "vpcEnabled": true,
            "vpcAZCount": 2,
            "migrationAssistanceEnabled": true,
            "replayerOutputEFSRemovalPolicy": "DESTROY",
            "migrationConsoleServiceEnabled": true,
            "otelCollectorEnabled": true,
            "engineVersion": "${engineVersion}",
            "distVersion": "${distVersion}",
            "domainName": "${params.CLUSTER_VERSION}-jenkins-test",
            "dataNodeCount": 10,
            "dataNodeType": "i4i.16xlarge.search",
            "masterEnabled": true,
            "dedicatedManagerNodeCount": 3,
            "dedicatedManagerNodeType": "m7i.xlarge.search",
            "ebsEnabled": false,
            "openAccessPolicyEnabled": false,
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
            "networkStackSuffix": "ec2-source-<STAGE>",
            "region": "${params.REGION}"
          }
        }
    """

    largeSnapshotPipeline(
            sourceContext: source_cdk_context,
            migrationContext: migration_cdk_context,
            migrationContextId: migrationContextId,
            defaultStageId: 'dev',
            skipCaptureProxyOnNodeSetup: true,
            skipSourceDeploy: true,
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
              REGION: params.REGION,
            ]
    )
}
