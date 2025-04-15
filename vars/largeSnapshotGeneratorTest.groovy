import groovy.json.JsonOutput

def call(Map config = [:]) {
    def migrationContextId = 'document-multiplier-rfs'
    def time = new Date().getTime()
    def testUniqueId = "document_multiplier_${time}_${currentBuild.number}"

    def docTransformerPath = "/shared-logs-output/test-transformations/transformation.json"
    
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
            "sourceCluster": {
                "endpoint": "https://search-test-large-snapshot3-es56-r3bsjpvx477msw2t2yreuk77l4.aos.us-east-1.on.aws",
                "auth": {
                    "type": "sigv4",
                    "region": "us-east-1",
                    "serviceSigningName": "es"
                },
                "version": "ES_5.6"
            },
            "targetCluster": {
                "endpoint": "https://search-test-large-snapshot3-es56-r3bsjpvx477msw2t2yreuk77l4.aos.us-east-1.on.aws",
                "auth": {
                    "type": "sigv4",
                    "region": "us-east-1",
                    "serviceSigningName": "es"
                },
                "version": "ES_5.6"
            },
            "vpcEnabled": true,
            "migrationAssistanceEnabled": true,
            "replayerOutputEFSRemovalPolicy": "DESTROY",
            "migrationConsoleServiceEnabled": true,
            "otelCollectorEnabled": true
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
            ]
    )
}
