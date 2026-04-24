def call(Map config = [:]) {
    def defaultStageId = config.defaultStageId ?: "cdc-only"
    def gitBranchDefault = config.gitBranchDefault ?: 'main'
    def jobName = config.jobName ?: "eks-cdc-integ-test"
    def defaultTestIds = config.defaultTestIds ?: "0030"
    def tlsMode = config.tlsMode ?: "none"
    def lockLabel = config.lockLabel ?: (jobName.startsWith("pr-") ? "aws-pr-slot" : "aws-main-slot")
    def sourceVersion = config.sourceVersion ?: ""
    def targetVersion = config.targetVersion ?: ""
    def sourceClusterType = config.sourceClusterType ?: ""
    def targetClusterType = config.targetClusterType ?: ""
    def clusterContextFilePath = "tmp/cluster-context-cdc-integ-${currentBuild.number}.json"
    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: gitBranchDefault, description: 'Git branch to use for repository')
            string(name: 'GIT_COMMIT', defaultValue: '', description: '(Optional) Specific commit to checkout after cloning branch')
            string(name: 'TEST_IDS', defaultValue: "${defaultTestIds}", description: 'Comma-separated test IDs to run (e.g. 0030)')
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
            string(name: 'SPEEDUP_FACTOR', defaultValue: '20', description: 'Speedup factor for traffic replayer')
            string(name: 'REGION', defaultValue: 'us-east-1', description: 'AWS region for deployment')
            booleanParam(name: 'BUILD', defaultValue: true, description: 'Build all artifacts from source (images, CFN, chart). When false, downloads published release artifacts.')
            booleanParam(name: 'USE_RELEASE_BOOTSTRAP', defaultValue: false, description: 'Download aws-bootstrap.sh from the latest GitHub release instead of using the source checkout version')
            string(name: 'VERSION', defaultValue: 'latest', description: 'Release version to deploy (e.g. "2.8.2" or "latest"). Determines which release artifacts to download.')
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
                        env.maStageName = "${params.STAGE ?: defaultStageId}-${pool}${currentBuild.number}"
                    }
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL, commit: params.GIT_COMMIT)
                    echo """
    ================================================================
    EKS CDC Integration Test
    ================================================================
    Git:                    ${params.GIT_REPO_URL} @ ${params.GIT_BRANCH}
    Commit:                 ${params.GIT_COMMIT ?: '(latest)'}
    Stage:                  ${env.maStageName}
    Region:                 ${params.REGION}
    Test IDs:               ${params.TEST_IDS}
    Source:                 ${params.SOURCE_VERSION}
    Target:                 ${params.TARGET_VERSION}
    Build:                  ${params.BUILD}
    Use Release Bootstrap:  ${params.USE_RELEASE_BOOTSTRAP}
    Version:                ${params.VERSION}
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
                                            kubectlContext: "migration-eks-${maStageName}",
                                            tlsMode: tlsMode != 'none' ? tlsMode : null
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

            stage('Perform CDC E2E Tests') {
                steps {
                    timeout(time: 2, unit: 'HOURS') {
                        dir('libraries/testAutomation') {
                            script {
                                sh "pipenv install --deploy"
                                withMigrationsTestAccount(region: params.REGION) { accountId ->
                                    sh "pipenv run app --source-version=$sourceVer --target-version=$targetVer --test-ids='${params.TEST_IDS}' --speedup-factor=${params.SPEEDUP_FACTOR} --reuse-clusters --skip-delete --skip-install --kube-context=${env.eksKubeContext}"
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

                            if (env.eksClusterName) {
                                cdcCleanupStep(kubeContext: env.eksKubeContext)
                                eksCleanupStep(
                                    stackName: maStackName,
                                    eksClusterName: env.eksClusterName,
                                    kubeContext: env.eksKubeContext
                                )
                            }

                            // Destroy AOS domains and MA stack in parallel (independent — no shared VPC)
                            parallel(
                                'Destroy AOS Domains': {
                                    dir('test') {
                                        echo "CLEANUP: Destroying domain stacks via CDK"
                                        sh "./awsDeployCluster.sh --stage ${maStageName} --context-file ${clusterContextFilePath} --destroy || true"
                                    }
                                },
                                'Delete MA Stack': {
                                    echo "CLEANUP: Deleting MA stack ${maStackName}"
                                    sh "aws cloudformation delete-stack --stack-name ${maStackName} --region ${region} || true"
                                    sh "aws cloudformation wait stack-delete-complete --stack-name ${maStackName} --region ${region} || true"
                                }
                            )
                        }

                        sh """
                            if command -v kubectl >/dev/null 2>&1; then
                                kubectl config delete-context ${env.eksKubeContext} 2>/dev/null || true
                            fi
                        """
                    }
                }
            }
        }
    }
}
