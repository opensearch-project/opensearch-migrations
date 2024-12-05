import groovy.json.JsonOutput

def call(Map config = [:]) {
    def sourceContextId = 'source-single-node-ec2'
    def migrationContextId = 'full-migration'
    def time = new Date().getTime()
    def uniqueId = "integ_min_${time}_${currentBuild.number}"
    def jsonTransformers = [
        [
            JsonConditionalTransformerProvider: [
                [
                    JsonJMESPathPredicateProvider: [
                        script: "name == 'test_e2e_0001_$uniqueId'"
                    ]
                ],
                [
                    [
                        JsonJoltTransformerProvider: [
                            script: [
                                operation: "modify-overwrite-beta",
                                spec: [
                                    name: "transformed_index"
                                ]
                            ]
                        ]
                    ],
                    [
                        JsonJoltTransformerProvider: [
                            script: [
                                operation: "modify-overwrite-beta",
                                spec: [
                                    settings: [
                                        index: [
                                            number_of_replicas: 3
                                        ]
                                    ]
                                ]
                            ]
                        ]
                    ]
                ]
            ]
        ]
    ]
    def jsonString = JsonOutput.toJson(jsonTransformers)
    def transformersArg = jsonString.bytes.encodeBase64().toString()
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
            "restrictServerAccessTo": "0.0.0.0/0",
            "requireImdsv2": false
          }
        }
    """
    def migration_cdk_context = """
        {
          "full-migration": {
            "stage": "<STAGE>",
            "vpcId": "<VPC_ID>",
            "engineVersion": "OS_2.11",
            "domainName": "os-cluster-<STAGE>",
            "dataNodeCount": 2,
            "openAccessPolicyEnabled": true,
            "domainRemovalPolicy": "DESTROY",
            "artifactBucketRemovalPolicy": "DESTROY",
            "captureProxyServiceEnabled": true,
            "targetClusterProxyServiceEnabled": true,
            "trafficReplayerServiceEnabled": true,
            "trafficReplayerExtraArgs": "--speedup-factor 10.0",
            "reindexFromSnapshotServiceEnabled": true,
            "reindexFromSnapshotExtraArgs": "--transformer-config-base64 $transformersArg"
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
            defaultStageId: 'full-es68',
            skipCaptureProxyOnNodeSetup: true,
            jobName: 'full-es68source-e2e-test',
            integTestCommand: '/root/lib/integ_test/integ_test/full_tests.py --source_proxy_alb_endpoint https://alb.migration.<STAGE>.local:9201 --target_proxy_alb_endpoint https://alb.migration.<STAGE>.local:9202'
    )
}
