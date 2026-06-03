def call(Map config = [:]) {
    def defaultStageId = config.defaultStageId ?: "eksint"
    def jobName = config.jobName ?: "eks-integ-test"
    def lockLabel = config.lockLabel ?: (jobName.startsWith("pr-") ? "aws-pr-slot" : "aws-main-slot")
    def sourceVersion = config.sourceVersion ?: ""
    def targetVersion = config.targetVersion ?: ""
    def sourceClusterType = config.sourceClusterType ?: ""
    def targetClusterType = config.targetClusterType ?: ""
    def testIds = config.testIds ?: "0001,0002"
    def gitBranchDefault = config.gitBranchDefault ?: 'main'
    def clusterContextFilePath = "tmp/cluster-context-integ-${currentBuild.number}.json"
    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: gitBranchDefault, description: 'Git branch to use for repository')
            string(name: 'GIT_COMMIT', defaultValue: '', description: '(Optional) Specific commit to checkout after cloning branch')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            choice(
                    name: 'SOURCE_VERSION',
                    choices: ['ES_7.10'],
                    description: 'Pick a specific source version'
            )
            choice(
                    name: 'SOURCE_CLUSTER_TYPE',
                    choices: ['OPENSEARCH_MANAGED_SERVICE'],
                    description: 'Pick a source cluster type'
            )
            choice(
                    name: 'TARGET_VERSION',
                    choices: ['OS_1.3', 'OS_2.19', 'OS_3.1'],
                    description: 'Pick a specific target version'
            )
            choice(
                    name: 'TARGET_CLUSTER_TYPE',
                    choices: ['OPENSEARCH_MANAGED_SERVICE'],
                    description: 'Pick a target cluster type'
            )
            string(name: 'TEST_IDS', defaultValue: 'all', description: 'Test IDs to execute. Use comma separated list e.g. "0001,0004" or "all" for all tests')
            string(name: 'REGION', defaultValue: 'us-east-1', description: 'AWS region for deployment')
            booleanParam(name: 'BUILD', defaultValue: true, description: 'Build all artifacts from source (images, CFN, chart). When false, downloads published release artifacts.')
            booleanParam(name: 'USE_RELEASE_BOOTSTRAP', defaultValue: false, description: 'Download aws-bootstrap.sh from the latest GitHub release instead of using the source checkout version')
            string(name: 'VERSION', defaultValue: 'latest', description: 'Release version to deploy (e.g. "2.8.2" or "latest"). Determines which release artifacts to download for images, chart, and CFN templates.')
        }

        options {
            lock(label: lockLabel, quantity: 1)
            timeout(time: 3, unit: 'HOURS')
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
                        env.maStageName = "${params.STAGE}-${pool}${currentBuild.number}"
                        echo """
    ================================================================
    EKS Integration Test
    ================================================================
    Git:                    ${params.GIT_REPO_URL} @ ${params.GIT_BRANCH}
    Stage:                  ${env.maStageName}
    Region:                 ${params.REGION}
    Source:                 ${params.SOURCE_VERSION}
    Target:                 ${params.TARGET_VERSION}
    Build:                  ${params.BUILD}
    Use Release Bootstrap:  ${params.USE_RELEASE_BOOTSTRAP}
    Version:                ${params.VERSION}
    ================================================================
"""
                    }
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL, commit: params.GIT_COMMIT)
                }
            }

            stage('Test Caller Identity') {
                steps {
                    sh 'aws sts get-caller-identity'
                }
            }

            // Skip source build when using release bootstrap or when not building
            // any artifacts from source (images/chart). The Gradle build is only needed
            // to produce JARs for Docker image builds and CDK synth for CFN templates.
            stage('Build') {
                when { expression { !params.USE_RELEASE_BOOTSTRAP && params.BUILD } }
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        sh './gradlew clean build -x test --no-daemon --stacktrace'
                    }
                }
            }

            stage('Deploy Clusters & Bootstrap MA') {
                steps {
                    timeout(time: 150, unit: 'MINUTES') {
                        script {
                            env.sourceVer = sourceVersion ?: params.SOURCE_VERSION
                            env.targetVer = targetVersion ?: params.TARGET_VERSION
                            env.sourceClusterType = sourceClusterType ?: params.SOURCE_CLUSTER_TYPE
                            env.targetClusterType = targetClusterType ?: params.TARGET_CLUSTER_TYPE
                            env.MA_STACK_NAME = "Migration-Assistant-Infra-Create-VPC-eks-${maStageName}-${params.REGION}"

                            def bootstrap = resolveBootstrap(
                                useReleaseBootstrap: params.USE_RELEASE_BOOTSTRAP,
                                build: params.BUILD,
                                skipTestImages: true,
                                version: params.VERSION,
                                useGeneralNodePool: true
                            )

                            parallel(
                                'Deploy Clusters': {
                                    dir('test') {
                                        deployClustersStep(
                                            stage: "${maStageName}",
                                            clusterContextFilePath: "${clusterContextFilePath}",
                                            sourceVer: env.sourceVer,
                                            sourceClusterType: env.sourceClusterType,
                                            targetVer: env.targetVer,
                                            targetClusterType: env.targetClusterType,
                                            publicAccess: true
                                        )
                                    }
                                },
                                'Bootstrap MA': {
                                    withMigrationsTestAccount(region: params.REGION) { accountId ->
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
                                    def sourceVersionExpanded = expandVersionString("${env.sourceVer}")
                                    def targetVersionExpanded = expandVersionString("${env.targetVer}")

                                    // Source configmap
                                    writeJSON file: '/tmp/source-cluster-config.json', json: [
                                            endpoint: sourceCluster.endpoint,
                                            allow_insecure: true,
                                            sigv4: [
                                                    region: params.REGION,
                                                    service: "es"
                                            ],
                                            version: env.sourceVer
                                    ]
                                    sh """
                                      kubectl --context=${env.eksKubeContext} create configmap source-${sourceVersionExpanded}-migration-config \
                                        --from-file=cluster-config=/tmp/source-cluster-config.json \
                                        --namespace ma --dry-run=client -o yaml | kubectl --context=${env.eksKubeContext} apply -f -

                                      kubectl --context=${env.eksKubeContext} -n ma get configmap source-${sourceVersionExpanded}-migration-config -o yaml
                                    """

                                    // Target configmap
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

                                      kubectl --context=${env.eksKubeContext} -n ma get configmap target-${targetVersionExpanded}-migration-config -o yaml
                                    """
                                }
                        }
                    }
                }
            }

            stage('Perform Python E2E Tests') {
                steps {
                    timeout(time: 2, unit: 'HOURS') {
                        dir('libraries/testAutomation') {
                            script {
                                def testIdsArg = ""
                                def testIdsResolved = testIds ?: params.TEST_IDS
                                if (testIdsResolved != "" && testIdsResolved != "all") {
                                    testIdsArg = "--test-ids='$testIdsResolved'"
                                }
                                sh "pipenv install --deploy"
                                withMigrationsTestAccount(region: params.REGION) { accountId ->
                                    sh "pipenv run app --source-version=$sourceVer --target-version=$targetVer $testIdsArg --reuse-clusters --skip-delete --skip-install --kube-context=${env.eksKubeContext}"
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
                    maStackName: env.MA_STACK_NAME ?: "Migration-Assistant-Infra-Create-VPC-eks-${maStageName}-${params.REGION}",
                    clusterStackName: "OpenSearch-${maStageName}-${params.REGION}",
                    kubeContext: env.eksKubeContext,
                    eksClusterName: env.eksClusterName,
                    cdkContextFile: clusterContextFilePath,
                    cdkStage: maStageName,
                )
            }
        }
    }
}
