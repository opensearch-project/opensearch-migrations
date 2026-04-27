/**
 * EKS BYOS (Bring Your Own Snapshot) Integration Pipeline
 * 
 * This pipeline tests migration from an existing S3 snapshot to a target OpenSearch cluster.
 * No source cluster is deployed - only a target cluster in Amazon OpenSearch Service.
 * 
 * Required parameters:
 * - S3_REPO_URI: Full S3 URI to snapshot repository (example: s3://bucket/folder/)
 * - SNAPSHOT_NAME: Name of the snapshot
 * - SOURCE_VERSION: Version of the cluster that created the snapshot (ES_5.6, ES_6.8, ES_7.10)
 * - TARGET_VERSION: Target OpenSearch version (OS_2.19, OS_3.1)
 */

import groovy.json.JsonOutput

def call(Map config = [:]) {
    def defaultStageId = config.defaultStageId ?: "eksbyos"
    def gitBranchDefault = config.gitBranchDefault ?: 'main'
    def jobName = config.jobName ?: "eks-byos-integ-test"
    def lockLabel = config.lockLabel ?: (jobName.startsWith("pr-") ? "aws-pr-slot" : "aws-main-slot")
    def clusterContextFilePath = "tmp/cluster-context-byos-${currentBuild.number}.json"
    def testIds = config.testIds ?: "0010"
    def sourceVersion = config.sourceVersion ?: ""
    def targetVersion = config.targetVersion ?: ""
    def targetClusterSize = config.targetClusterSize ?: ""
    def targetClusterSizes = [
        'default': [
            dataNodeType: "r8g.large.search",
            dedicatedManagerNodeType: "m6g.large.search",
            dataNodeCount: 2,
            dedicatedManagerNodeCount: 0,
            ebsVolumeSize: 100
        ],
        'large': [
            dataNodeType: "r8g.8xlarge.search",
            dedicatedManagerNodeType: "m6g.2xlarge.search",
            dataNodeCount: 24,
            dedicatedManagerNodeCount: 3,
            ebsVolumeSize: 2048,
            ebsThroughput: 1000,  // gp3 max: 1000 MB/s (CDK validation limit)
            ebsIops: 10000
        ]
    ]
    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }
        parameters {
            choice(
                name: 'TEST_PRESET',
                choices: ['custom', 'large-es7x-24B', 'large-es6x-20B', 'large-es5x', 'small-es5x-300k', 'small-es7x-osb'],
                description: '''<b>Presets</b> (overrides S3_REPO_URI, SNAPSHOT_NAME, SOURCE_VERSION, RFS_WORKERS, TARGET_CLUSTER_SIZE):<br/>
<b>large-es7x-24B</b> — ES 7.10, 24B docs / 5.8TB, 90 workers, large cluster<br/>
<b>large-es6x-20B</b> — ES 6.8, 20B docs / 5.2TB, 90 workers, large cluster<br/>
<b>large-es5x</b> — ES 5.6, 90 workers, large cluster<br/>
<b>small-es5x-300k</b> — ES 5.6, 300K docs, 1 worker, default cluster<br/>
<b>small-es7x-osb</b> — ES 7.10, OSB data, 1 worker, default cluster<br/>
<b>custom</b> — uses the parameter fields below as-is'''
            )
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: gitBranchDefault, description: 'Git branch to use for repository')
            string(name: 'GIT_COMMIT', defaultValue: '', description: '(Optional) Specific commit to checkout after cloning branch')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            string(name: 'RFS_WORKERS', defaultValue: '1', description: 'Number of RFS worker pods for document backfill (podReplicas)')
            // Snapshot configuration
            string(name: 'S3_REPO_URI', defaultValue: 's3://migrations-snapshots-library-us-east-1/ma_osb_data/es7x-osb-data/', description: 'Full S3 URI to snapshot repository (e.g., s3://bucket/folder/)')
            string(name: 'REGION', defaultValue: 'us-east-1', description: 'AWS region for deployment and snapshot bucket')
            string(name: 'SNAPSHOT_NAME', defaultValue: 'es7x-osb-data', description: 'Name of the snapshot')
            string(name: 'TEST_IDS', defaultValue: '0010', description: 'Test IDs to execute (comma separated, e.g., "0010" or "0010,0011")')
            string(name: 'MONITOR_RETRY_LIMIT', defaultValue: '1000000', description: 'Max retries for workflow monitoring (~1/min). Default effectively infinite — Argo handles retries.')
            choice(
                name: 'SOURCE_VERSION',
                choices: ['ES_7.10', 'ES_1.5', 'ES_2.4', 'ES_5.6', 'ES_6.8', 'ES_8.19', 'OS_1.3', 'OS_2.19'],
                description: 'Version of the cluster that created the snapshot'
            )
            // Target cluster configuration
            choice(
                name: 'TARGET_VERSION',
                choices: ['OS_2.19', 'OS_3.1'],
                description: 'Target OpenSearch version'
            )
            choice(
                name: 'TARGET_CLUSTER_SIZE',
                choices: ['default', 'large'],
                description: 'Target cluster size (default: 2x r8g.large, large: 24x r8g.8xlarge with dedicated masters)'
            )
            booleanParam(name: 'BUILD', defaultValue: true, description: 'Build all artifacts from source (images, CFN, chart). When false, downloads published release artifacts.')
            booleanParam(name: 'USE_RELEASE_BOOTSTRAP', defaultValue: false, description: 'Download aws-bootstrap.sh from the latest GitHub release instead of using the source checkout version')
            string(name: 'VERSION', defaultValue: 'latest', description: 'Release version to deploy (e.g. "2.8.2" or "latest"). Determines which release artifacts to download for images, chart, and CFN templates.')
        }
        options {
            lock(label: lockLabel, quantity: 1)
            timeout(time: 18, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '30'))
            skipDefaultCheckout(true)
        }
        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'GIT_REPO_URL', value: '$.GIT_REPO_URL'],
                    [key: 'GIT_BRANCH', value: '$.GIT_BRANCH'],
                    [key: 'GIT_COMMIT', value: '$.GIT_COMMIT'],
                    [key: 'job_name', value: '$.job_name']
                ],
                tokenCredentialId: 'jenkins-migrations-generic-webhook-token',
                causeString: 'Triggered by webhook',
                regexpFilterExpression: "^$jobName\$",
                regexpFilterText: "\$job_name",
            )
            cron(periodicCron(jobName))
        }
        stages {
            stage('Checkout & Print Params') {
                steps {
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL, commit: params.GIT_COMMIT)
                    script {
                        def pool = jobName.startsWith("main-") ? "m" : jobName.startsWith("release-") ? "r" : "p"
                        env.maStageName = "${params.STAGE}-${pool}${currentBuild.number}"
                        echo """
    ================================================================
    EKS BYOS Integration Test
    ================================================================
    Git:                    ${params.GIT_REPO_URL} @ ${params.GIT_BRANCH}
    Stage:                  ${env.maStageName}
    Region:                 ${params.REGION}
    Build:                  ${params.BUILD}
    Use Release Bootstrap:  ${params.USE_RELEASE_BOOTSTRAP}
    Version:                ${params.VERSION}
    ================================================================
"""
                        // Resolve TEST_PRESET → effective parameter values
                        def testPresets = [
                            'large-es7x-24B': [s3RepoUri: 's3://migrations-snapshots-library-us-east-1/large-snapshot-es7x/', snapshotName: 'large-snapshot', sourceVersion: 'ES_7.10', rfsWorkers: '90', targetClusterSize: 'large'],
                            'large-es6x-20B': [s3RepoUri: 's3://migrations-snapshots-library-us-east-1/large-snapshot-es6x/', snapshotName: 'large-snapshot', sourceVersion: 'ES_6.8', rfsWorkers: '90', targetClusterSize: 'large'],
                            'large-es5x':     [s3RepoUri: 's3://migrations-snapshots-library-us-east-1/large-snapshot-es5x/', snapshotName: 'large-snapshot', sourceVersion: 'ES_5.6', rfsWorkers: '90', targetClusterSize: 'large'],
                            'small-es5x-300k': [s3RepoUri: 's3://migrations-snapshots-library-us-east-1/another_set/large-snapshot-es5x/', snapshotName: 'large-snapshot', sourceVersion: 'ES_5.6', rfsWorkers: '1', targetClusterSize: 'default'],
                            'small-es7x-osb': [s3RepoUri: 's3://migrations-snapshots-library-us-east-1/ma_osb_data/es7x-osb-data/', snapshotName: 'es7x-osb-data', sourceVersion: 'ES_7.10', rfsWorkers: '1', targetClusterSize: 'default']
                        ]
                        def preset = testPresets[params.TEST_PRESET]
                        if (preset) {
                            env.resolvedS3RepoUri         = preset.s3RepoUri
                            env.resolvedSnapshotName      = preset.snapshotName
                            env.resolvedSourceVersion     = preset.sourceVersion
                            env.resolvedRfsWorkers        = preset.rfsWorkers
                            env.resolvedTargetClusterSize = preset.targetClusterSize
                        } else {
                            env.resolvedS3RepoUri         = params.S3_REPO_URI
                            env.resolvedSnapshotName      = params.SNAPSHOT_NAME
                            env.resolvedSourceVersion     = params.SOURCE_VERSION
                            env.resolvedRfsWorkers        = params.RFS_WORKERS
                            env.resolvedTargetClusterSize = params.TARGET_CLUSTER_SIZE
                        }

                        echo """
                            ================================================================
                            BYOS Migration Pipeline Configuration
                            ================================================================
                            Test Preset:         ${params.TEST_PRESET}

                            Git Configuration:
                              Repository:        ${params.GIT_REPO_URL}
                              Branch:            ${params.GIT_BRANCH}
                              Stage:             ${maStageName}
                            
                            Snapshot Configuration:
                              S3 URI:            ${env.resolvedS3RepoUri}
                              Region:            ${params.REGION}
                              Snapshot Name:     ${env.resolvedSnapshotName}
                              Source Version:    ${env.resolvedSourceVersion}
                            
                            Target Cluster Configuration:
                              Target Version:    ${params.TARGET_VERSION}
                              Cluster Size:      ${env.resolvedTargetClusterSize}
                            
                            Migration Configuration:
                              RFS Workers:       ${env.resolvedRfsWorkers}
                              Test IDs:          ${params.TEST_IDS}
                            ================================================================
                        """
                    }
                }
            }

            stage('Test Caller Identity') {
                steps {
                    sh 'aws sts get-caller-identity'
                }
            }

            // Skip source build when using release bootstrap or when not building
            // any artifacts from source (images/chart).
            stage('Build') {
                when { expression { !params.USE_RELEASE_BOOTSTRAP && params.BUILD } }
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        sh './gradlew clean build -x test --no-daemon --stacktrace'
                    }
                }
            }

            stage('Deploy & Bootstrap MA') {
                steps {
                    timeout(time: 150, unit: 'MINUTES') {
                        script {
                            env.MA_STACK_NAME = "Migration-Assistant-Infra-Create-VPC-eks-${maStageName}-${params.REGION}"

                            withMigrationsTestAccount(region: params.REGION) { accountId ->
                                def bootstrap = resolveBootstrap(
                                    useReleaseBootstrap: params.USE_RELEASE_BOOTSTRAP,
                                    build: params.BUILD,
                                    skipTestImages: true,
                                    version: params.VERSION,
                                    useGeneralNodePool: true
                                )
                                bootstrapMA(
                                    stackName: env.MA_STACK_NAME,
                                    stage: maStageName,
                                    region: params.REGION,
                                    bootstrap: bootstrap,
                                    eksAccessPrincipalArn: "arn:aws:iam::${accountId}:role/JenkinsDeploymentRole",
                                    kubectlContext: "migration-eks-${maStageName}"
                                )
                            }
                        }
                    }
                }
            }

            stage('Deploy AOS Target Cluster') {
                steps {
                    timeout(time: 45, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                env.sourceVer = sourceVersion ?: env.resolvedSourceVersion
                                env.targetVer = targetVersion ?: params.TARGET_VERSION
                                env.targetClusterSize = targetClusterSize ?: env.resolvedTargetClusterSize
                                
                                def sizeConfig = targetClusterSizes[env.targetClusterSize]
                                deployTargetClusterOnly(
                                    stage: "${maStageName}",
                                    clusterContextFilePath: "${clusterContextFilePath}",
                                    targetVer: env.targetVer,
                                    sizeConfig: sizeConfig,
                                    vpcId: env.maVpcId
                                )
                            }
                        }
                    }
                }
            }

            stage('Post-Cluster Setup') {
                steps {
                    timeout(time: 15, unit: 'MINUTES') {
                        script {
                            withMigrationsTestAccount(region: params.REGION, duration: 1200) { accountId ->
                                    def clusterDetails = readJSON text: env.clusterDetailsJson
                                    def targetCluster = clusterDetails.target
                                    def targetVersionExpanded = expandVersionString("${env.targetVer}")

                                    // Target configmap (no source cluster for BYOS)
                                    writeJSON file: '/tmp/target-cluster-config.json', json: [
                                            endpoint: targetCluster.endpoint,
                                            allow_insecure: true,
                                            sigv4: [
                                                region: params.REGION,
                                                service: "es"
                                            ]
                                    ]
                                    sh """
                                        kubectl --context=${env.eksKubeContext} create configmap target-${targetVersionExpanded}-migration-config \
                                            --from-file=cluster-config=/tmp/target-cluster-config.json \
                                            --namespace ma --dry-run=client -o yaml | kubectl --context=${env.eksKubeContext} apply -f -
                                    """

                                    // Add SG rule to allow EKS cluster traffic to reach the target OpenSearch domain
                                    if (targetCluster.securityGroupId) {
                                        sh """
                                          exists=\$(aws ec2 describe-security-groups \
                                            --group-ids $targetCluster.securityGroupId \
                                            --query "SecurityGroups[0].IpPermissions[?UserIdGroupPairs[?GroupId=='$env.clusterSecurityGroup']]" \
                                            --output text)

                                          if [ -z "\$exists" ]; then
                                            echo "Ingress rule not found. Adding EKS cluster SG to target OpenSearch SG..."
                                            aws ec2 authorize-security-group-ingress \
                                              --group-id $targetCluster.securityGroupId \
                                              --protocol -1 \
                                              --port -1 \
                                              --source-group $env.clusterSecurityGroup
                                          else
                                            echo "Ingress rule already exists. Skipping."
                                          fi
                                        """
                                    }

                                    // Set BYOS env vars on migration-console so pytest can read them
                                    def s3RepoUri = env.resolvedS3RepoUri
                                    if (!s3RepoUri.endsWith('/')) { s3RepoUri = s3RepoUri + '/' }
                                    sh """
                                        kubectl --context=${env.eksKubeContext} -n ma set env statefulset/migration-console \
                                            BYOS_SNAPSHOT_NAME='${env.resolvedSnapshotName}' \
                                            BYOS_S3_REPO_URI='${s3RepoUri}' \
                                            BYOS_S3_REGION='${params.REGION}' \
                                            BYOS_POD_REPLICAS='${env.resolvedRfsWorkers}' \
                                            BYOS_MONITOR_RETRY_LIMIT='${params.MONITOR_RETRY_LIMIT}'
                                        kubectl --context=${env.eksKubeContext} -n ma rollout status statefulset/migration-console --timeout=120s
                                    """

                                }
                        }
                    }
                }
            }

            stage('Run BYOS Migration Test') {
                steps {
                    timeout(time: 12, unit: 'HOURS') {
                        dir('libraries/testAutomation') {
                            script {
                                def testIdsArg = ""
                                def testIdsResolved = testIds ?: params.TEST_IDS
                                if (testIdsResolved != "" && testIdsResolved != "all") {
                                    testIdsArg = "--test-ids='$testIdsResolved'"
                                }
                                sh "pipenv install --deploy"
                                withMigrationsTestAccount(region: params.REGION, duration: 43200) { accountId ->
                                    sh "pipenv run app --source-version=${env.sourceVer} --target-version=${env.targetVer} $testIdsArg --reuse-clusters --skip-delete --skip-install --kube-context=${env.eksKubeContext}"
                                }
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                timeout(time: 75, unit: 'MINUTES') {
                    script {
                        def region = params.REGION ?: 'us-east-1'
                        def maStackName = env.MA_STACK_NAME ?: "Migration-Assistant-Infra-Create-VPC-eks-${maStageName}-${region}"


                        withMigrationsTestAccount(region: region, duration: 4500) { accountId ->
                                sh "mkdir -p libraries/testAutomation/logs"
                                archiveArtifacts artifacts: 'libraries/testAutomation/logs/**', allowEmptyArchive: true

                                // EKS/k8s cleanup (only if EKS was deployed)
                                if (env.eksClusterName) {
                                    dir('libraries/testAutomation') {
                                        sh "pipenv install --deploy"
                                        sh "kubectl --context=${env.eksKubeContext} -n ma get pods || true"
                                        sh "pipenv run app --delete-only --kube-context=${env.eksKubeContext}"
                                    }

                                    // Revoke security group rule added during setup
                                    if (env.clusterDetailsJson && env.clusterSecurityGroup) {
                                        def clusterDetails = readJSON text: env.clusterDetailsJson
                                        def targetCluster = clusterDetails.target
                                        sh """
                                          if aws ec2 describe-security-groups --group-ids $targetCluster.securityGroupId >/dev/null 2>&1; then
                                            exists=\$(aws ec2 describe-security-groups \
                                              --group-ids $targetCluster.securityGroupId \
                                              --query "SecurityGroups[0].IpPermissions[?UserIdGroupPairs[?GroupId=='$env.clusterSecurityGroup']]" \
                                              --output json)
                                            if [ "\$exists" != "[]" ]; then
                                              echo "CLEANUP: Revoking EKS SG ingress rule"
                                              aws ec2 revoke-security-group-ingress \
                                                --group-id $targetCluster.securityGroupId \
                                                --protocol -1 --port -1 \
                                                --source-group $env.clusterSecurityGroup || true
                                            fi
                                          fi
                                        """
                                    }

                                    eksCleanupStep(
                                        stackName: maStackName,
                                        eksClusterName: env.eksClusterName,
                                        kubeContext: env.eksKubeContext
                                    )
                                }

                                // Destroy domain stacks via CDK (uses same context file from deploy)
                                dir('test') {
                                    echo "CLEANUP: Destroying domain stacks via CDK"
                                    sh "./awsDeployCluster.sh --stage ${maStageName} --context-file ${clusterContextFilePath} --destroy || true"
                                }

                                // Delete MA CloudFormation stack directly
                                echo "CLEANUP: Deleting MA stack ${maStackName}"
                                sh "aws cloudformation delete-stack --stack-name ${maStackName} --region ${region} || true"
                                sh "aws cloudformation wait stack-delete-complete --stack-name ${maStackName} --region ${region} || true"
                        }
                    }
                }
            }
        }
    }
}

/**
 * Deploy only the target cluster (no source cluster for BYOS scenario)
 */
def deployTargetClusterOnly(Map config) {
    def clusterContextValues = [
        stage: "${config.stage}",
        vpcId: "${config.vpcId}",
        clusters: [[
            clusterId: "target",
            clusterVersion: "${config.targetVer}",
            clusterType: "OPENSEARCH_MANAGED_SERVICE",
            openAccessPolicyEnabled: true,
            domainRemovalPolicy: "DESTROY",
            dataNodeType: config.sizeConfig.dataNodeType,
            dedicatedManagerNodeType: config.sizeConfig.dedicatedManagerNodeType,
            dataNodeCount: config.sizeConfig.dataNodeCount,
            dedicatedManagerNodeCount: config.sizeConfig.dedicatedManagerNodeCount,
            ebsEnabled: true,
            ebsVolumeSize: config.sizeConfig.ebsVolumeSize,
            ebsThroughput: config.sizeConfig.ebsThroughput,
            ebsIops: config.sizeConfig.ebsIops,
            nodeToNodeEncryption: true,
            allowAllVpcTraffic: true
        ]]
    ]

    def contextJsonStr = JsonOutput.prettyPrint(JsonOutput.toJson(clusterContextValues))
    writeFile(file: config.clusterContextFilePath, text: contextJsonStr)
    sh "echo 'Using cluster context file options:' && cat ${config.clusterContextFilePath}"
    withMigrationsTestAccount(region: params.REGION) { accountId ->
            sh "./awsDeployCluster.sh --stage ${config.stage} --context-file ${config.clusterContextFilePath} --vpc-id ${config.vpcId}"
    }

    def rawJsonFile = readFile "tmp/cluster-details-${config.stage}.json"
    echo "Cluster details JSON:\n${rawJsonFile}"
    env.clusterDetailsJson = rawJsonFile
}
