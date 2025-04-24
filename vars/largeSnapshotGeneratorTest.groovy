import groovy.json.JsonOutput

def call(Map config = [:]) {
    def migrationContextId = 'document-multiplier-rfs'
    def time = new Date().getTime()
    def testUniqueId = "document_multiplier_${time}_${currentBuild.number}"

    def docTransformerPath = "/shared-logs-output/test-transformations/transformation.json"
    
    // Map the cluster version parameter to the actual engine version
    String engineVersion
    String distVersion
    String dataNodeType
    String dedicatedManagerNodeType
    boolean ebsEnabled = false
    Integer ebsVolumeSize = null
    Integer dataNodeCount = null

    switch (params.CLUSTER_VERSION) {
        case 'es5x':
            engineVersion = "ES_5.6"
            distVersion = "5.6"
            dataNodeType = "r5.4xlarge.search"
            dedicatedManagerNodeType = "m4.xlarge.search"
            ebsEnabled = true
            ebsVolumeSize = 300
            dataNodeCount = 60
            break
        case 'es6x':
            engineVersion = "ES_6.8"
            distVersion = "6.8"
            dataNodeType = "r5.4xlarge.search"
            dedicatedManagerNodeType = "m5.xlarge.search"
            ebsEnabled = true
            ebsVolumeSize = 300
            dataNodeCount = 60
            break
        case 'es7x':
            engineVersion = "ES_7.10"
            distVersion = "7.10"
            dataNodeType = "r6gd.4xlarge.search"
            dedicatedManagerNodeType = "m6g.xlarge.search"
            dataNodeCount = 60
            break
        case 'os1x':
            engineVersion = "OS_1.3"
            distVersion = "1.3"
            dataNodeType = "r6g.4xlarge.search"
            dedicatedManagerNodeType = "m6g.xlarge.search"
            ebsEnabled = true
            ebsVolumeSize = 1024
            dataNodeCount = 15
            break
        case 'os2x':
            engineVersion = "OS_2.17"
            distVersion = "2.17"
            dataNodeType = "r6g.4xlarge.search"
            dedicatedManagerNodeType = "m6g.xlarge.search"
            ebsEnabled = true
            ebsVolumeSize = 1024
            dataNodeCount = 15
            break
        default:
            throw new RuntimeException("Unsupported CLUSTER_VERSION: ${params.CLUSTER_VERSION}")
    }

    // Determine if node-to-node encryption should be enabled based on ES version
    def nodeToNodeEncryption = params.CLUSTER_VERSION != 'es5x'
   
    def migration_cdk_context = """
        {
          "document-multiplier-rfs": {
            "stage": "${params.STAGE}",
            "region": "${params.DEPLOY_REGION}",
            "artifactBucketRemovalPolicy": "DESTROY",
            "captureProxyServiceEnabled": false,
            "targetClusterProxyServiceEnabled": false,
            "trafficReplayerServiceEnabled": false,
            "reindexFromSnapshotServiceEnabled": true,
            "reindexFromSnapshotExtraArgs": "--doc-transformer-config-file ${docTransformerPath} --source-version ${engineVersion}",
            "sourceClusterDeploymentEnabled": false,
            "sourceClusterDisabled": true,
            "vpcEnabled": true,
            "vpcAZCount": 2,
            "migrationAssistanceEnabled": true,
            "replayerOutputEFSRemovalPolicy": "DESTROY",
            "migrationConsoleServiceEnabled": true,
            "otelCollectorEnabled": true,
            "engineVersion": "${engineVersion}",
            "distVersion": "${distVersion}",
            "domainName": "${params.CLUSTER_VERSION}-jenkins-test",
            "dataNodeCount": ${dataNodeCount},
            "dataNodeType": "${dataNodeType}",
            "masterEnabled": true,
            "dedicatedManagerNodeCount": 3,
            "dedicatedManagerNodeType": "${dedicatedManagerNodeType}",
            "ebsEnabled": ${ebsEnabled},
            ${ebsVolumeSize ? '"ebsVolumeSize": ' + ebsVolumeSize + ',' : ''}
            "openAccessPolicyEnabled": false,
            "domainRemovalPolicy": "DESTROY",
            "tlsSecurityPolicy": "TLS_1_2",
            "enforceHTTPS": true,
            "nodeToNodeEncryptionEnabled": ${nodeToNodeEncryption},
            "encryptionAtRestEnabled": true
            }
        }
    """

    def source_cdk_context = """
        {
          "source-single-node-ec2": {
            "suffix": "ec2-source-<STAGE>",
            "networkStackSuffix": "ec2-source-<STAGE>",
            "region": "${params.DEPLOY_REGION}"
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
              DEPLOY_REGION: params.DEPLOY_REGION,
              SNAPSHOT_REGION: params.SNAPSHOT_REGION,
            ]
    )
}
