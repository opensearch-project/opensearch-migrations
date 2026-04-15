/**
 * Unified CDC integration test pipeline supporting ES→OS, ES→AOSS, or both.
 *
 * Merges eksCdcIntegPipeline (ES→OS) and eksCdcAossIntegPipeline (ES→AOSS)
 * into a single pipeline that deploys infrastructure once and runs test phases
 * sequentially.
 *
 * Config params:
 *   deployOsTarget      - Deploy OpenSearch target (default: true)
 *   deployAossTarget    - Deploy AOSS target (default: false)
 *   aossCollectionType  - AOSS collection type (default: 'SEARCH')
 *   tlsMode             - TLS mode for MA bootstrap (default: 'none')
 *   esOsTestIds         - Test IDs for ES→OS phase (default: '0030,0031,0032,0033,0040')
 *   aossTestIds         - Test IDs for ES→AOSS phase (default: '0034,0041')
 */
def call(Map config = [:]) {
    def defaultStageId = config.defaultStageId ?: "cdc-unified"
    def jobName = config.jobName ?: "eks-cdc-unified-integ-test"
    def deployOsTarget = config.containsKey('deployOsTarget') ? config.deployOsTarget : true
    def deployAossTarget = config.containsKey('deployAossTarget') ? config.deployAossTarget : false
    def aossCollectionType = config.aossCollectionType ?: 'SEARCH'
    def tlsMode = config.tlsMode ?: 'none'
    def esOsTestIds = config.esOsTestIds ?: '0030,0031,0032,0033,0040'
    def aossTestIds = config.aossTestIds ?: '0034,0041'
    def lockLabel = config.lockLabel ?: (jobName.startsWith("pr-") ? "aws-pr-slot" : "aws-main-slot")
    def sourceVersion = config.sourceVersion ?: ""
    def targetVersion = config.targetVersion ?: ""
    def sourceClusterType = config.sourceClusterType ?: ""
    def targetClusterType = config.targetClusterType ?: ""
    def clusterContextFilePath = "tmp/cluster-context-cdc-unified-${currentBuild.number}.json"

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
            string(name: 'GIT_COMMIT', defaultValue: '', description: '(Optional) Specific commit to checkout after cloning branch')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            choice(name: 'SOURCE_VERSION', choices: ['ES_7.10'], description: 'Source cluster version')
            choice(name: 'TARGET_VERSION', choices: ['OS_1.3', 'OS_2.19', 'OS_3.1'], description: 'Target cluster version (for OS target)')
            string(name: 'SPEEDUP_FACTOR', defaultValue: '20', description: 'Speedup factor for traffic replayer')
            string(name: 'REGION', defaultValue: 'us-east-1', description: 'AWS region for deployment')
            booleanParam(name: 'BUILD_IMAGES', defaultValue: true, description: 'Build container images from source instead of using public images')
            booleanParam(name: 'BUILD_CHART_AND_DASHBOARDS', defaultValue: true, description: 'Build Helm chart and dashboards from source instead of using release artifacts')
            booleanParam(name: 'USE_RELEASE_BOOTSTRAP', defaultValue: false, description: 'Download aws-bootstrap.sh from the latest GitHub release instead of using the source checkout version')
            string(name: 'VERSION', defaultValue: 'latest', description: 'Release version to deploy (e.g. "2.8.2" or "latest"). Determines which release artifacts to download.')
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
        }

        stages {
            stage('Checkout & Print Params') {
                steps {
                    script {
                        def pool = jobName.startsWith("main-") ? "m" : jobName.startsWith("release-") ? "r" : "p"
                        env.maStageName = "${params.STAGE ?: defaultStageId}-${pool}${currentBuild.number}"
                        env.aossDeployed = "false"
                    }
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL, commit: params.GIT_COMMIT)
                    echo """
    ================================================================
    EKS CDC Unified Integration Test
    ================================================================
    Git:                    ${params.GIT_REPO_URL} @ ${params.GIT_BRANCH}
    Commit:                 ${params.GIT_COMMIT ?: '(latest)'}
    Stage:                  ${env.maStageName}
    Region:                 ${params.REGION}
    Deploy OS Target:       ${deployOsTarget}
    Deploy AOSS Target:     ${deployAossTarget}
    AOSS Collection Type:   ${aossCollectionType}
    TLS Mode:               ${tlsMode}
    Source:                 ${params.SOURCE_VERSION}
    Target (OS):            ${params.TARGET_VERSION}
    ES→OS Test IDs:         ${esOsTestIds}
    ES→AOSS Test IDs:       ${aossTestIds}
    Build Images:           ${params.BUILD_IMAGES}
    Build Chart:            ${params.BUILD_CHART_AND_DASHBOARDS}
    Use Release Bootstrap:  ${params.USE_RELEASE_BOOTSTRAP}
    Version:                ${params.VERSION}
    ================================================================
"""
                }
            }

            stage('Test Caller Identity') {
                steps { sh 'aws sts get-caller-identity' }
            }

            stage('Build') {
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
                            env.sourceClusterType = sourceClusterType ?: 'OPENSEARCH_MANAGED_SERVICE'
                            env.targetClusterType = targetClusterType ?: 'OPENSEARCH_MANAGED_SERVICE'
                            env.MA_STACK_NAME = "Migration-Assistant-Infra-Create-VPC-eks-${maStageName}-${params.REGION}"

                            def bootstrap = resolveBootstrap(
                                useReleaseBootstrap: params.USE_RELEASE_BOOTSTRAP,
                                buildImages: params.BUILD_IMAGES,
                                buildChartAndDashboards: params.BUILD_CHART_AND_DASHBOARDS,
                                skipTestImages: true,
                                version: params.VERSION
                            )

                            parallel(
                                'Deploy Clusters': {
                                    dir('test') {
                                        if (deployOsTarget) {
                                            // Deploy source + OS target
                                            deployClustersStep(
                                                stage: "${maStageName}",
                                                clusterContextFilePath: "${clusterContextFilePath}",
                                                sourceVer: env.sourceVer,
                                                sourceClusterType: env.sourceClusterType,
                                                targetVer: env.targetVer,
                                                targetClusterType: env.targetClusterType,
                                                publicAccess: true
                                            )
                                        } else {
                                            // AOSS-only: deploy source cluster only
                                            deployClustersStep(
                                                stage: "${maStageName}",
                                                clusterContextFilePath: "${clusterContextFilePath}",
                                                sourceVer: env.sourceVer,
                                                sourceClusterType: env.sourceClusterType,
                                                publicAccess: true
                                            )
                                        }
                                    }
                                },
                                'Bootstrap MA': {
                                    withMigrationsTestAccount(region: params.REGION, duration: 7200) { accountId ->
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

            stage('Deploy AOSS Target') {
                when { expression { deployAossTarget } }
                steps {
                    timeout(time: 60, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                withMigrationsTestAccount(region: params.REGION) { accountId ->
                                    def jenkinsRoleArn = "arn:aws:iam::${accountId}:role/JenkinsDeploymentRole"
                                    def podRoleArn = "arn:aws:iam::${accountId}:role/${env.eksClusterName}-migrations-role"

                                    def aossContextFile = "tmp/cluster-context-aoss-target-${currentBuild.number}.json"
                                    def context = [
                                        stage: "${maStageName}",
                                        vpcId: "${env.maVpcId}",
                                        clusters: [[
                                            clusterId: "target",
                                            clusterType: "OPENSEARCH_SERVERLESS",
                                            collectionType: aossCollectionType,
                                            standbyReplicas: "DISABLED",
                                            domainRemovalPolicy: "DESTROY",
                                            dataAccessPrincipals: [jenkinsRoleArn, podRoleArn]
                                        ]]
                                    ]
                                    writeJSON file: aossContextFile, json: context
                                    sh "cat ${aossContextFile}"

                                    env.AOSS_CONTEXT_FILE = aossContextFile
                                    sh "./awsDeployCluster.sh --stage ${maStageName} --context-file ${aossContextFile}"

                                    def clusterDetails = readJSON text: readFile("tmp/cluster-details-${maStageName}.json")
                                    env.AOSS_COLLECTION_ENDPOINT = clusterDetails['target'].endpoint
                                    env.aossDeployed = "true"
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

                                // Source configmap (always)
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

                                  kubectl --context=${env.eksKubeContext} -n ma get configmap source-${sourceVersionExpanded}-migration-config -o yaml
                                """

                                // Target configmap (if OS target deployed)
                                if (deployOsTarget) {
                                    def targetCluster = clusterDetails.target
                                    def targetVersionExpanded = expandVersionString("${env.targetVer}")

                                    writeJSON file: '/tmp/target-cluster-config.json', json: [
                                            endpoint: targetCluster.endpoint,
                                            allow_insecure: true,
                                            sigv4: [region: params.REGION, service: "es"]
                                    ]
                                    sh """
                                      kubectl --context=${env.eksKubeContext} create configmap target-${targetVersionExpanded}-migration-config \
                                        --from-file=cluster-config=/tmp/target-cluster-config.json \
                                        --namespace ma --dry-run=client -o yaml | kubectl --context=${env.eksKubeContext} apply -f -

                                      kubectl --context=${env.eksKubeContext} -n ma get configmap target-${targetVersionExpanded}-migration-config -o yaml
                                    """
                                }

                                // AOSS endpoint injection (if AOSS target deployed)
                                if (deployAossTarget) {
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
            }

            stage('Phase 1: ES→OS CDC Tests') {
                when { expression { deployOsTarget } }
                steps {
                    timeout(time: 2, unit: 'HOURS') {
                        dir('libraries/testAutomation') {
                            script {
                                sh "pipenv install --deploy"
                                withMigrationsTestAccount(region: params.REGION) { accountId ->
                                    sh "pipenv run app --source-version=${env.sourceVer} --target-version=${env.targetVer} --test-ids='${esOsTestIds}' --speedup-factor=${params.SPEEDUP_FACTOR} --reuse-clusters --skip-delete --skip-install --kube-context=${env.eksKubeContext}"
                                }
                            }
                        }
                    }
                }
            }

            stage('Phase 2: ES→AOSS CDC Tests') {
                when { expression { deployAossTarget } }
                steps {
                    timeout(time: 2, unit: 'HOURS') {
                        dir('libraries/testAutomation') {
                            script {
                                sh "pipenv install --deploy"
                                withMigrationsTestAccount(region: params.REGION) { accountId ->
                                    sh "pipenv run app --source-version=${env.sourceVer} --target-type=AOSS --test-ids='${aossTestIds}' --reuse-clusters --skip-delete --skip-install --kube-context=${env.eksKubeContext}"
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
                            if (env.eksClusterName) {
                                cdcCleanupStep(kubeContext: env.eksKubeContext)
                                eksCleanupStep(
                                    stackName: maStackName,
                                    eksClusterName: env.eksClusterName,
                                    kubeContext: env.eksKubeContext
                                )
                            }

                            def cleanupSteps = [:]

                            // AOSS stack cleanup (if deployed)
                            if (env.aossDeployed == "true") {
                                def aossStackName = "OpenSearch-${maStageName}-${region}"
                                cleanupSteps['Delete AOSS Stack'] = {
                                    echo "CLEANUP: Deleting AOSS stack ${aossStackName}"
                                    sh "aws cloudformation delete-stack --stack-name ${aossStackName} --region ${region} || true"
                                    sh "aws cloudformation wait stack-delete-complete --stack-name ${aossStackName} --region ${region} || true"
                                }
                            }

                            // OS domain stacks cleanup (if deployed)
                            if (deployOsTarget) {
                                cleanupSteps['Destroy OS Domain Stacks'] = {
                                    dir('test') {
                                        echo "CLEANUP: Destroying domain stacks via CDK"
                                        sh "./awsDeployCluster.sh --stage ${maStageName} --context-file ${clusterContextFilePath} --destroy || true"
                                    }
                                }
                            }

                            // MA stack cleanup (always)
                            cleanupSteps['Delete MA Stack'] = {
                                echo "CLEANUP: Deleting MA stack ${maStackName}"
                                sh "aws cloudformation delete-stack --stack-name ${maStackName} --region ${region} || true"
                                sh "aws cloudformation wait stack-delete-complete --stack-name ${maStackName} --region ${region} || true"
                            }

                            parallel(cleanupSteps)
                        }

                        sh """
                            if command -v kubectl >/dev/null 2>&1; then
                                kubectl config delete-context ${env.eksKubeContext} 2>/dev/null || true
                            fi
                        """
                    }
                }
                archiveArtifacts artifacts: 'libraries/testAutomation/logs/**', allowEmptyArchive: true
            }
        }
    }
}
