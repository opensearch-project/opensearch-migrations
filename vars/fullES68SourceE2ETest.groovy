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
            "enableImdsCredentialRefresh": true,
            "requireImdsv2": true
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
            jobName: config.jobName ?: 'full-es68source-e2e-test',
            testUniqueId: testUniqueId,
            defaultGitBranch: config.defaultGitBranch ?: 'main',
            integTestCommand: '/root/lib/integ_test/integ_test/full_tests.py --source_proxy_alb_endpoint https://alb.migration.<STAGE>.local:9201 --target_proxy_alb_endpoint https://alb.migration.<STAGE>.local:9202',
            preDeployStep: { Map args ->
                // Pre-deploy stack deletion is intentionally disabled by default.
                //
                // Tearing down the migration CDK app and then the source (E2E solution)
                // CDK app, and redeploying both from scratch each run, adds ~30-60+
                // minutes per pipeline invocation. Since `cdk deploy` on an already-
                // deployed stack is a no-op for unchanged resources, we reuse the
                // stacks across runs and rely on `preIntegTestStep` to reset
                // run-scoped state (snapshots, S3 objects) between tests.
                //
                // ENABLE THIS BLOCK when making a breaking change to the source or
                // migration CDK stacks (e.g. incompatible context changes, resource
                // replacements that cdk deploy can't perform in-place, IAM/security
                // refactors, or anything that otherwise requires a clean redeploy).
                // To re-enable, uncomment the two `sh` lines below. The destroy order
                // (migration first, then source) must be preserved: `npx cdk destroy`
                // polls CFN synchronously, and --clean-up-migration-only additionally
                // waits on `aws cloudformation wait stack-delete-complete` for any
                // leftover "OSMigrations-<stage>" stacks, so the second call cannot
                // start until the first has fully returned.
                //
                // CloudFormation stacks are named purely by `--stage` (e.g.
                // OSMigrations-<stage>, opensearch-infra-stack-ec2-source-<stage>),
                // so cleanup is already stage-scoped regardless of context content.
                // The context file is only required for `cdk destroy` to synth
                // successfully so it can enumerate its own stack list;
                // awsE2ESolutionSetup.sh substitutes placeholder <VPC_ID> /
                // <SOURCE_CLUSTER_ENDPOINT> values when the real source side is
                // already gone, so a stale or default context is still safe.
                //
                // def commonArgs = "--source-context-file './${args.sourceContextFileName}' " +
                //         "--migration-context-file './${args.migrationContextFileName}' " +
                //         "--source-context-id ${args.sourceContextId} " +
                //         "--migration-context-id ${args.migrationContextId} " +
                //         "--stage ${args.stage}"
                // sh "./awsE2ESolutionSetup.sh ${commonArgs} --clean-up-migration-only"
                // sh "./awsE2ESolutionSetup.sh ${commonArgs} --clean-up-source-only"
                echo "Skipping pre-deploy stack cleanup (reusing existing stacks). " +
                        "Uncomment the destroy block in vars/fullES68SourceE2ETest.groovy " +
                        "when making a breaking change to the CDK stacks."
            },
            preIntegTestStep: { deployStage ->
                def sourceEndpoint = "https://alb.migration.${deployStage}.local:9201"
                def clusterName = "migration-${deployStage}-ecs-cluster"
                def taskArn = sh(script: "aws ecs list-tasks --cluster ${clusterName} --family 'migration-${deployStage}-migration-console' | jq --raw-output '.taskArns[0]'", returnStdout: true).trim()
                def execCmd = { cmd -> sh(script: "aws ecs execute-command --cluster '${clusterName}' --task '${taskArn}' --container 'migration-console' --interactive --command '${cmd}'", returnStatus: true) }
                // Delete snapshot from source cluster
                execCmd("curl -k -X DELETE ${sourceEndpoint}/_snapshot/migration_assistant_repo/rfs-snapshot")
                // Delete snapshot repo from source cluster
                execCmd("curl -k -X DELETE ${sourceEndpoint}/_snapshot/migration_assistant_repo")
                // Delete S3 snapshot files
                def accountId = sh(script: "aws sts get-caller-identity --query Account --output text", returnStdout: true).trim()
                def region = sh(script: "aws configure get region || echo us-east-1", returnStdout: true).trim()
                sh(script: "aws s3 rm s3://migration-artifacts-${accountId}-${deployStage}-${region}/rfs-snapshot-repo --recursive", returnStatus: true)
            }
    )
}
