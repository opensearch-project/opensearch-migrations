/**
 * Air-gapped integration test pipeline.
 *
 * Validates that Migration Assistant deploys and runs a full migration
 * (backfill + CDC) in an isolated network with all images from private ECR.
 *
 * Phases:
 *   1. Build stack — build MA images, mirror third-party images to ECR
 *   2. Isolated VPC — create VPC with no-internet subnets, deploy EKS + MA
 *   3. AOS clusters — deploy source (ES) and target (OS) in private subnets
 *   4. Test — run Test0040 (backfill + CDC) from migration console
 *   5. Cleanup — tear down everything in reverse order
 */
def call(Map config = [:]) {
    def jobName = config.jobName ?: "eks-isolated-integ-test"
    def gitBranchDefault = config.gitBranchDefault ?: 'main'
    def defaultStageId = config.defaultStageId ?: "isoint"
    def lockLabel = config.lockLabel ?: (jobName.startsWith("pr-") ? "aws-pr-slot" : "aws-main-slot")
    def clusterContextFilePath = "tmp/cluster-context-isolated-${currentBuild.number}.json"

    // Track resources for cleanup
    def isolatedVpcId = ''
    def buildStackName = ''
    def isolatedStackName = ''

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: gitBranchDefault, description: 'Git branch to use')
            string(name: 'GIT_COMMIT', defaultValue: '', description: '(Optional) Specific commit to checkout')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            string(name: 'REGION', defaultValue: 'us-east-1', description: 'AWS region')
            choice(name: 'SOURCE_VERSION', choices: ['ES_7.10'], description: 'Source cluster version')
            choice(name: 'TARGET_VERSION', choices: ['OS_2.19', 'OS_1.3', 'OS_3.1'], description: 'Target cluster version')
            string(name: 'TEST_IDS', defaultValue: '0040', description: 'Test IDs to run (comma-separated)')
            booleanParam(name: 'BUILD', defaultValue: true, description: 'Build all artifacts from source (images, CFN, chart). When false, downloads published release artifacts.')
            booleanParam(name: 'USE_RELEASE_BOOTSTRAP', defaultValue: false, description: 'Download aws-bootstrap.sh from the latest GitHub release instead of using the source checkout version')
            string(name: 'VERSION', defaultValue: 'latest', description: 'Release version to deploy (e.g. "2.8.2" or "latest"). Determines which release artifacts to download for images, chart, and CFN templates.')
        }

        options {
            lock(label: lockLabel, quantity: 1)
            timeout(time: 4, unit: 'HOURS')
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
                causeString: 'Triggered by PR on opensearch-migrations repository',
                regexpFilterExpression: "^$jobName\$",
                regexpFilterText: "\$job_name",
            )
            cron(periodicCron(jobName))
        }

        stages {
            stage('Checkout') {
                steps {
                    script {
                        def pool = jobName.startsWith("main-") ? "m" : jobName.startsWith("release-") ? "r" : "p"
                        env.maStageName = "${params.STAGE}-${pool}${currentBuild.number}"
                        env.buildStageName = "isob-${pool}${currentBuild.number}"
                        buildStackName = "MA-ISO-BUILD-${env.maStageName}"
                        isolatedStackName = "MA-ISO-${env.maStageName}"
                    }
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL, commit: params.GIT_COMMIT)
                    echo """
    ================================================================
    EKS Isolated Integration Test
    ================================================================
    Git:        ${params.GIT_REPO_URL} @ ${params.GIT_BRANCH}
    Stage:      ${env.maStageName}
    Region:     ${params.REGION}
    Source:     ${params.SOURCE_VERSION}
    Target:     ${params.TARGET_VERSION}
    Test IDs:   ${params.TEST_IDS}
    ================================================================
"""
                }
            }

            stage('Test Caller Identity') {
                steps {
                    sh 'aws sts get-caller-identity'
                }
            }

            stage('Build') {
                when { expression { !params.USE_RELEASE_BOOTSTRAP && params.BUILD } }
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        sh './gradlew clean build -x test --no-daemon --stacktrace'
                    }
                }
            }

            stage('Phase 1: Build Stack') {
                steps {
                    timeout(time: 90, unit: 'MINUTES') {
                        withMigrationsTestAccount(region: params.REGION, duration: 5400) { accountId ->
                            script {
                                def bootstrap = resolveBootstrap(
                                    useReleaseBootstrap: params.USE_RELEASE_BOOTSTRAP,
                                    build: params.BUILD,
                                    skipTestImages: true,
                                    version: params.VERSION
                                )

                                bootstrapMA(
                                    stackName: buildStackName,
                                    stage: env.buildStageName,
                                    region: params.REGION,
                                    bootstrap: bootstrap,
                                    eksAccessPrincipalArn: "arn:aws:iam::${accountId}:role/JenkinsDeploymentRole",
                                    kubectlContext: "migration-eks-build-${env.buildStageName}"
                                )

                                env.BUILD_ECR = env.registryEndpoint
                                echo "Build ECR registry: ${env.BUILD_ECR}"
                            }
                        }
                    }
                }
            }

            stage('Phase 2: Isolated Deploy') {
                steps {
                    timeout(time: 90, unit: 'MINUTES') {
                        withMigrationsTestAccount(region: params.REGION, duration: 5400) { accountId ->
                            script {
                                // Create isolated VPC
                                def vpc = createIsolatedVpc(region: params.REGION, stage: env.maStageName)
                                isolatedVpcId = vpc.vpcId
                                env.isolatedVpcId = vpc.vpcId

                                // Deploy EKS + MA into isolated subnets
                                def bootstrap = resolveBootstrap(
                                    useReleaseBootstrap: params.USE_RELEASE_BOOTSTRAP,
                                    build: params.BUILD,
                                    skipTestImages: true,
                                    version: params.VERSION
                                )
                                bootstrapMA(
                                    stackName: isolatedStackName,
                                    stage: env.maStageName,
                                    region: params.REGION,
                                    bootstrap: bootstrap,
                                    eksAccessPrincipalArn: "arn:aws:iam::${accountId}:role/JenkinsDeploymentRole",
                                    kubectlContext: "migration-eks-${env.maStageName}",
                                    vpcId: vpc.vpcId,
                                    subnetIds: vpc.subnetIds,
                                    createVpcEndpoints: true,
                                    maImagesSource: env.BUILD_ECR
                                )

                                env.eksClusterName = env.eksClusterName  // already set by bootstrapMA
                                env.eksKubeContext = "migration-eks-${env.maStageName}"

                                // Configure kubectl context
                                sh """
                                    aws eks update-kubeconfig \
                                      --name "${env.eksClusterName}" \
                                      --region "${params.REGION}" \
                                      --alias "${env.eksKubeContext}"
                                """

                                // Verify isolated network has no public internet access
                                echo "Validating network isolation..."
                                sh """
                                    kubectl --context=${env.eksKubeContext} exec migration-console-0 -n ma -- \
                                      timeout 10 curl -sfI https://aws.amazon.com > /dev/null 2>&1 \
                                      && echo 'FAIL: internet reachable' && exit 1 \
                                      || echo 'Isolation validated: pods cannot reach public internet'
                                """

                                // Build stack is no longer needed — isolated ECR has its own image copies
                                echo "CLEANUP: Starting early deletion of build stack ${buildStackName}"
                                sh "aws cloudformation delete-stack --stack-name ${buildStackName} --region ${params.REGION} || true"
                            }
                        }
                    }
                }
            }

            stage('Phase 3: Deploy AOS Clusters') {
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        script {
                            deployIsolatedAosClusters(
                                region: params.REGION,
                                vpcId: env.isolatedVpcId,
                                stage: env.maStageName,
                                eksClusterName: env.eksClusterName,
                                clusterContextFilePath: clusterContextFilePath,
                                sourceVer: params.SOURCE_VERSION,
                                targetVer: params.TARGET_VERSION
                            )
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
                                def sourceCluster = clusterDetails.source
                                def targetCluster = clusterDetails.target
                                def sourceVersionExpanded = expandVersionString("${params.SOURCE_VERSION}")
                                def targetVersionExpanded = expandVersionString("${params.TARGET_VERSION}")

                                writeJSON file: '/tmp/source-cluster-config.json', json: [
                                    endpoint: sourceCluster.endpoint,
                                    allow_insecure: true,
                                    sigv4: [region: params.REGION, service: "es"],
                                    version: params.SOURCE_VERSION
                                ]
                                sh """
                                    kubectl --context=${env.eksKubeContext} create configmap source-${sourceVersionExpanded}-migration-config \
                                      --from-file=cluster-config=/tmp/source-cluster-config.json \
                                      --namespace ma --dry-run=client -o yaml | kubectl --context=${env.eksKubeContext} apply -f -
                                """

                                writeJSON file: '/tmp/target-cluster-config.json', json: [
                                    endpoint: targetCluster.endpoint,
                                    allow_insecure: true,
                                    sigv4: [region: params.REGION, service: "es"]
                                ]
                                sh """
                                    kubectl --context=${env.eksKubeContext} create configmap target-${targetVersionExpanded}-migration-config \
                                      --from-file=cluster-config=/tmp/target-cluster-config.json \
                                      --namespace ma --dry-run=client -o yaml | kubectl --context=${env.eksKubeContext} apply -f -
                                """
                            }
                        }
                    }
                }
            }

            stage('Phase 4: Run Tests') {
                steps {
                    timeout(time: 2, unit: 'HOURS') {
                        dir('libraries/testAutomation') {
                            script {
                                sh "pipenv install --deploy"
                                withMigrationsTestAccount(region: params.REGION) { accountId ->
                                    sh """
                                        pipenv run app \
                                          --source-version=${params.SOURCE_VERSION} \
                                          --target-version=${params.TARGET_VERSION} \
                                          --test-ids='${params.TEST_IDS}' \
                                          --reuse-clusters \
                                          --skip-delete \
                                          --skip-install \
                                          --kube-context=${env.eksKubeContext}
                                    """
                                }
                            }
                        }
                    }
                }
            }

            stage('Validate CloudWatch Connectivity') {
                steps {
                    timeout(time: 5, unit: 'MINUTES') {
                        withMigrationsTestAccount(region: params.REGION) { accountId ->
                            script {
                                // Verify logs reached CloudWatch through VPC endpoint
                                def logCount = sh(
                                    script: """
                                        aws logs filter-log-events \
                                          --log-group-name '/migration-assistant-${env.maStageName}-${params.REGION}/logs' \
                                          --limit 1 \
                                          --region ${params.REGION} \
                                          --query 'events | length(@)' \
                                          --output text 2>/dev/null || echo "0"
                                    """,
                                    returnStdout: true
                                ).trim()
                                if (logCount == "0") {
                                    echo "WARNING: No CloudWatch logs found — fluent-bit may not be shipping logs through VPC endpoint"
                                } else {
                                    echo "CloudWatch logs validated: logs are reaching CloudWatch via VPC endpoint"
                                }
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                eksPostCleanup(
                    maStackName: isolatedStackName,
                    clusterStackName: "OpenSearch-${env.maStageName}-${params.REGION}",
                    kubeContext: env.eksKubeContext,
                    eksClusterName: env.eksClusterName,
                    cdkContextFile: clusterContextFilePath,
                    cdkStage: env.maStageName,
                    stepsAfterMaDelete: [
                        [type: 'isolated-vpc', vpcId: isolatedVpcId, stage: env.maStageName,
                         reason: 'isolated VPC teardown'],
                        [type: 'cfn-destroy', stackName: buildStackName,
                         reason: 'build stack confirmation (deletion initiated in Phase 2)'],
                    ],
                    extraVerifyStacks: [buildStackName],
                )
            }
        }
    }
}
