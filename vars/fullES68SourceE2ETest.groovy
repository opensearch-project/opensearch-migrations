import groovy.json.JsonOutput

def call(Map config = [:]) {
    def sourceContextId = 'source-single-node-ec2'
    def migrationContextId = 'full-migration'
    def time = new Date().getTime()
    def testUniqueId = "integ_full_${time}_${currentBuild.number}"

    def rfsJsonTransformations = [
        [
                TypeMappingSanitizationTransformerProvider: [
                        regexMappings: [
                                [
                                        "sourceIndexPattern": "(test_e2e_0001_.*)",
                                        "sourceTypePattern": ".*",
                                        "targetIndexPattern": "\$1_transformed"
                                ],
                                [
                                        "sourceIndexPattern": "(.*)",
                                        "sourceTypePattern": "(.*)",
                                        "targetIndexPattern": "\$1"
                                ]
                        ],
                        sourceProperties: [
                                version: [
                                        major: 6,
                                        minor: 8
                                ]
                        ]
                ]
        ],
    ]
    def rfsJsonString = JsonOutput.toJson(rfsJsonTransformations)
    def rfsTransformersArg = rfsJsonString.bytes.encodeBase64().toString()
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
            "engineVersion": "OS_2.19",
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
            "reindexFromSnapshotExtraArgs": "--doc-transformer-config-base64 $rfsTransformersArg",
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
            testUniqueId: testUniqueId,
            integTestCommand: '/root/lib/integ_test/integ_test/full_tests.py --source_proxy_alb_endpoint https://alb.migration.<STAGE>.local:9201 --target_proxy_alb_endpoint https://alb.migration.<STAGE>.local:9202',
            deployStep: {
                echo "Acquired deployment stage: ${stage}"
                def baseCommand = "./awsE2ESolutionSetup.sh --source-context-file './sourceJenkinsContext.json' " +
                        "--migration-context-file './migrationJenkinsContext.json' " +
                        "--source-context-id ${sourceContextId} " +
                        "--migration-context-id ${migrationContextId} " +
                        "--stage ${stage} " +
                        "--migrations-git-url ${params.GIT_REPO_URL} " +
                        "--migrations-git-branch ${params.GIT_BRANCH} " +
                        "--skip-capture-proxy"
                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 5400, roleSessionName: 'jenkins-session') {
                        sh baseCommand
                        // Workaround: CDK does not set HttpTokens=optional despite requireImdsv2=false in context.
                        // Source EC2 instances need IMDSv1 for the ES repository-s3 plugin to obtain credentials.
                        // Without this, S3 snapshot operations fail with ExpiredToken errors.
                        sh '''
                            for id in $(aws ec2 describe-instances \
                              --region us-east-1 \
                              --filters "Name=tag:Name,Values=*ec2-source-'"${stage}"'*" "Name=instance-state-name,Values=running" \
                              --query "Reservations[].Instances[].InstanceId" \
                              --output text); do
                                echo "Setting IMDSv1 (HttpTokens=optional) on instance $id"
                                aws ec2 modify-instance-metadata-options \
                                  --region us-east-1 \
                                  --instance-id "$id" \
                                  --http-tokens optional \
                                  --http-endpoint enabled
                            done
                        '''
                    }
                }
            }
    )
}
