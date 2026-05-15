/**
 * CDC integration test pipeline with AOSS target.
 *
 * Combines CDC pipeline stages (ES source + capture proxy + replayer) with
 * AOSS deployment from eksAOSSIntegPipeline. Deploys:
 *   - EKS cluster + Migration Assistant (via aws-bootstrap.sh, create-vpc-cfn)
 *   - ES 7.10 source domain (via CDK, public access + SigV4)
 *   - AOSS search collection (via CDK, as replayer target in MA VPC)
 */

def call(Map config = [:]) {
    def defaultStageId = config.defaultStageId ?: "aosscdc"
    def gitBranchDefault = config.gitBranchDefault ?: 'main'
    def jobName = config.jobName ?: "eks-cdc-aoss-integ-test"
    def defaultTestIds = config.defaultTestIds ?: "0034,0041"
    def lockLabel = config.lockLabel ?: (jobName.startsWith("pr-") ? "aws-pr-slot" : "aws-main-slot")
    def clusterContextFilePath = "tmp/cluster-context-cdc-aoss-${currentBuild.number}.json"

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: gitBranchDefault, description: 'Git branch to use for repository')
            string(name: 'GIT_COMMIT', defaultValue: '', description: '(Optional) Specific commit to checkout after cloning branch')
            string(name: 'TEST_IDS', defaultValue: "${defaultTestIds}", description: 'Comma-separated test IDs to run (e.g. 0034,0042)')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            choice(name: 'SOURCE_VERSION', choices: ['ES_7.10'], description: 'Source cluster version')
            choice(name: 'SOURCE_CLUSTER_TYPE', choices: ['OPENSEARCH_MANAGED_SERVICE'], description: 'Source cluster type')
            string(name: 'REGION', defaultValue: 'us-east-1', description: 'AWS region for deployment')
            booleanParam(name: 'BUILD', defaultValue: true, description: 'Build all artifacts from source (images, CFN, chart). When false, downloads published release artifacts.')
            booleanParam(name: 'USE_RELEASE_BOOTSTRAP', defaultValue: false, description: 'Use release bootstrap script')
            string(name: 'VERSION', defaultValue: 'latest', description: 'Release version to deploy')
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
            stage('Checkout & Print Params') {
                steps {
                    script {
                        def pool = jobName.startsWith("main-") ? "m" : jobName.startsWith("release-") ? "r" : "p"
                        env.maStageName = "${params.STAGE ?: defaultStageId}-${pool}${currentBuild.number}"
                        env.STACK_NAME = "MA-CDC-AOSS-${maStageName}-${params.REGION}"
                        env.CLUSTER_STACK = "OpenSearch-${maStageName}-${params.REGION}"
                    }
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL, commit: params.GIT_COMMIT)
                    echo """
    ================================================================
    EKS CDC + AOSS Integration Test
    ================================================================
    Git:            ${params.GIT_REPO_URL} @ ${params.GIT_BRANCH}
    Stage:          ${env.maStageName}
    Region:         ${params.REGION}
    Test IDs:       ${params.TEST_IDS}
    Source:         ${params.SOURCE_VERSION}
    Target:         AOSS (search collection)
    ================================================================
"""
                }
            }

            stage('Test Caller Identity') {
                steps { sh 'aws sts get-caller-identity' }
            }

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
                            env.sourceVer = params.SOURCE_VERSION

                            def bootstrap = resolveBootstrap(
                                useReleaseBootstrap: params.USE_RELEASE_BOOTSTRAP,
                                build: params.BUILD,
                                skipTestImages: true,
                                version: params.VERSION,
                                useGeneralNodePool: true
                            )

                            withMigrationsTestAccount(region: params.REGION, duration: 7200) { accountId ->
                                bootstrapMA(
                                    stackName: env.STACK_NAME,
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

            stage('Deploy Source & AOSS Target') {
                steps {
                    timeout(time: 60, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                withMigrationsTestAccount(region: params.REGION) { accountId ->
                                    def jenkinsRoleArn = "arn:aws:iam::${accountId}:role/JenkinsDeploymentRole"
                                    def podRoleArn = "arn:aws:iam::${accountId}:role/${env.eksClusterName}-migrations-role"

                                    def context = [
                                        stage: "${maStageName}",
                                        vpcId: "${env.maVpcId}",
                                        clusters: [
                                            [
                                                clusterId: "source",
                                                clusterName: "${maStageName}-source",
                                                clusterVersion: env.sourceVer,
                                                clusterType: params.SOURCE_CLUSTER_TYPE,
                                                domainRemovalPolicy: "DESTROY",
                                                publicAccess: true,
                                                accessPolicies: [
                                                    Version: "2012-10-17",
                                                    Statement: [[
                                                        Effect: "Allow",
                                                        Principal: [AWS: "arn:aws:iam::${accountId}:root"],
                                                        Action: "es:*",
                                                        Resource: "*"
                                                    ]]
                                                ]
                                            ],
                                            [
                                                clusterId: "target",
                                                clusterName: "${maStageName}-target",
                                                clusterType: "OPENSEARCH_SERVERLESS",
                                                collectionType: "SEARCH",
                                                standbyReplicas: "DISABLED",
                                                domainRemovalPolicy: "DESTROY",
                                                dataAccessPrincipals: [jenkinsRoleArn, podRoleArn]
                                            ]
                                        ]
                                    ]
                                    writeJSON file: clusterContextFilePath, json: context
                                    sh "cat ${clusterContextFilePath}"
                                    sh "./awsDeployCluster.sh --stage ${maStageName} --context-file ${clusterContextFilePath}"

                                    def clusterDetails = readJSON text: readFile("tmp/cluster-details-${maStageName}.json")
                                    env.AOSS_COLLECTION_ENDPOINT = clusterDetails['target'].endpoint
                                    env.clusterDetailsJson = readFile("tmp/cluster-details-${maStageName}.json")
                                    echo "AOSS endpoint: ${env.AOSS_COLLECTION_ENDPOINT}"
                                }
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
                                def sourceCluster = clusterDetails.source
                                def sourceVersionExpanded = expandVersionString("${env.sourceVer}")

                                // Source configmap (ES 7.10 with sigv4/es)
                                writeJSON file: '/tmp/source-cluster-config.json', json: [
                                        endpoint: sourceCluster.endpoint,
                                        allow_insecure: true,
                                        sigv4: [region: params.REGION, service: "es"],
                                        version: env.sourceVer
                                ]
                                sh """
                                  kubectl --context=${env.eksKubeContext} create configmap source-${sourceVersionExpanded}-migration-config \
                                    --from-file=cluster-config=/tmp/source-cluster-config.json \
                                    --namespace ma --dry-run=client -o yaml | kubectl --context=${env.eksKubeContext} apply -f -
                                """

                                // Inject AOSS endpoint env var into migration-console
                                sh """
                                  kubectl --context=${env.eksKubeContext} set env statefulset/migration-console \
                                    -n ma AOSS_CDC_ENDPOINT=${env.AOSS_COLLECTION_ENDPOINT}
                                  kubectl --context=${env.eksKubeContext} rollout status statefulset/migration-console -n ma --timeout=120s
                                """
                            }
                        }
                    }
                }
            }

            stage('Perform CDC AOSS Tests') {
                steps {
                    timeout(time: 2, unit: 'HOURS') {
                        dir('libraries/testAutomation') {
                            script {
                                sh "pipenv install --deploy"
                                withMigrationsTestAccount(region: params.REGION, duration: 14400) { accountId ->
                                    sh "pipenv run app --source-version=${env.sourceVer} --target-type=AOSS --test-ids='${params.TEST_IDS}' --reuse-clusters --skip-delete --skip-install --kube-context=${env.eksKubeContext}"
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
                    maStackName: env.STACK_NAME,
                    clusterStackName: env.CLUSTER_STACK,
                    clusterInsideMaVpc: true,
                    kubeContext: env.eksKubeContext,
                    eksClusterName: env.eksClusterName,
                )
            }
        }
    }
}
