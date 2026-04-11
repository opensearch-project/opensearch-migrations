def call(Map config = [:]) {
    def collectionType = config.collectionType ?: 'SEARCH'
    def testIdMap = ['SEARCH': '0021', 'TIMESERIES': '0022', 'VECTORSEARCH': '0023']
    def envVarMap = ['SEARCH': 'AOSS_SEARCH_ENDPOINT', 'TIMESERIES': 'AOSS_TIMESERIES_ENDPOINT', 'VECTORSEARCH': 'AOSS_VECTOR_ENDPOINT']
    def testId = testIdMap[collectionType]
    def endpointEnvVar = envVarMap[collectionType]
    def defaultStageId = config.defaultStageId ?: "aosss"
    def jobName = config.jobName ?: "eks-aoss-${collectionType.toLowerCase()}-integ-test"
    def lockLabel = config.lockLabel ?: (jobName.startsWith("pr-") ? "aws-pr-slot" : "aws-main-slot")
    def clusterContextFilePath = "tmp/cluster-context-aoss-${currentBuild.number}.json"

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            string(name: 'REGION', defaultValue: 'us-east-1', description: 'AWS region for deployment')
            choice(name: 'SOURCE_VERSION', choices: ['OS_1.3'], description: 'Version of the cluster that created the snapshot')
            string(name: 'RFS_WORKERS', defaultValue: '1', description: 'Number of RFS worker pods for document backfill')
            // Snapshot configuration
            string(name: 'S3_REPO_URI', defaultValue: 's3://migrations-snapshots-library-us-east-1/aoss-osb-data/os1x-aoss-osb-data/', description: 'Full S3 URI to snapshot repository')
            string(name: 'SNAPSHOT_NAME', defaultValue: 'os1x-aoss-osb-data', description: 'Name of the snapshot')
            string(name: 'MONITOR_RETRY_LIMIT', defaultValue: '33', description: 'Max retries for workflow monitoring (~1/min). 33=~30min')
            booleanParam(name: 'BUILD_IMAGES', defaultValue: true, description: 'Build container images from source instead of using public images')
            booleanParam(name: 'BUILD_CHART_AND_DASHBOARDS', defaultValue: true, description: 'Build Helm chart and dashboards from source instead of using release artifacts')
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
                    [key: 'job_name', value: '$.job_name']
                ],
                tokenCredentialId: 'jenkins-migrations-generic-webhook-token',
                causeString: 'Triggered by webhook',
                regexpFilterExpression: "^$jobName\$",
                regexpFilterText: "\$job_name",
            )
        }

        stages {
            stage('Checkout & Print Params') {
                steps {
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL)
                    script {
                        def pool = jobName.startsWith("main-") ? "m" : jobName.startsWith("release-") ? "r" : "p"
                        env.maStageName = "${params.STAGE ?: defaultStageId}-${pool}${currentBuild.number}"
                        env.STACK_NAME = "MA-Serverless-${maStageName}-${params.REGION}"
                        env.eksClusterName = "migration-eks-cluster-${maStageName}-${params.REGION}"
                        env.eksKubeContext = env.eksClusterName
                        env.CLUSTER_STACK = "OpenSearch-${maStageName}-${params.REGION}"

                        echo """
                            ================================================================
                            AOSS ${collectionType} Collection Integration Test
                            ================================================================
                            Git:              ${params.GIT_REPO_URL} @ ${params.GIT_BRANCH}
                            Stage:            ${maStageName}
                            Region:           ${params.REGION}
                            Collection Type:  ${collectionType}
                            Test ID:          ${testId}
                            Source:           ${params.SOURCE_VERSION}
                            Workers:          ${params.RFS_WORKERS}
                            Build Images:           ${params.BUILD_IMAGES}
                            Build Chart:            ${params.BUILD_CHART_AND_DASHBOARDS}
                            Use Release Bootstrap:  ${params.USE_RELEASE_BOOTSTRAP}
                            Version:                ${params.VERSION}
                            ================================================================
                        """
                    }
                }
            }

            stage('Test Caller Identity') {
                steps {
                    script {
                        sh 'aws sts get-caller-identity'
                    }
                }
            }

            // Skip source build when using release bootstrap or when not building
            // any artifacts from source (images/chart).
            stage('Build') {
                when { expression { !params.USE_RELEASE_BOOTSTRAP && (params.BUILD_IMAGES || params.BUILD_CHART_AND_DASHBOARDS) } }
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        script {
                            sh './gradlew clean build -x test --no-daemon --stacktrace'
                        }
                    }
                }
            }

            stage('Deploy & Bootstrap MA') {
                steps {
                    timeout(time: 90, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 7200, roleSessionName: 'jenkins-session') {
                                    def bootstrap = resolveBootstrap(
                                        useReleaseBootstrap: params.USE_RELEASE_BOOTSTRAP,
                                        buildImages: params.BUILD_IMAGES,
                                        buildChartAndDashboards: params.BUILD_CHART_AND_DASHBOARDS,
                                        version: params.VERSION
                                    )
                                    sh """
                                        ${bootstrap.script} \
                                          --deploy-create-vpc-cfn \
                                          --stack-name "${env.STACK_NAME}" \
                                          --stage "${maStageName}" \
                                          --region "${params.REGION}" \
                                          --eks-access-principal-arn "arn:aws:iam::\${MIGRATIONS_TEST_ACCOUNT_ID}:role/JenkinsDeploymentRole" \
                                          ${bootstrap.flags} \
                                          --skip-console-exec \
                                          --skip-setting-k8s-context \
                                          2>&1 | { set +x; while IFS= read -r line; do printf '%s | %s\\n' "\$(date '+%H:%M:%S')" "\$line"; done; }; exit \${PIPESTATUS[0]}
                                    """

                                    // Extract VPC ID from MigrationsExportString
                                    def rawOutput = sh(
                                        script: """aws cloudformation describe-stacks --stack-name "${env.STACK_NAME}" \
                                          --query "Stacks[0].Outputs[?OutputKey=='MigrationsExportString'].OutputValue" \
                                          --output text""",
                                        returnStdout: true
                                    ).trim()
                                    if (!rawOutput) {
                                        error("Could not retrieve MigrationsExportString from ${env.STACK_NAME}")
                                    }
                                    def exportsMap = rawOutput.split(';')
                                            .collect { it.trim().replaceFirst(/^export\s+/, '') }
                                            .findAll { it.contains('=') }
                                            .collectEntries {
                                                def (key, value) = it.split('=', 2)
                                                [(key): value]
                                            }
                                    env.MA_VPC_ID = exportsMap['VPC_ID']

                                    echo "MA VPC: ${env.MA_VPC_ID}"

                                    // Set up kubectl context for later stages
                                    sh "aws eks update-kubeconfig --region ${params.REGION} --name ${env.eksClusterName} --alias ${env.eksClusterName}"
                                }
                            }
                        }
                    }
                }
            }

            stage('Deploy AOSS Target') {
                steps {
                    timeout(time: 60, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    def jenkinsRoleArn = "arn:aws:iam::${MIGRATIONS_TEST_ACCOUNT_ID}:role/JenkinsDeploymentRole"
                                    def podRoleArn = "arn:aws:iam::${MIGRATIONS_TEST_ACCOUNT_ID}:role/${env.eksClusterName}-migrations-role"

                                    def context = [
                                        stage: "${maStageName}",
                                        vpcId: "${env.MA_VPC_ID}",
                                        clusters: [
                                            [
                                                clusterId: "target",
                                                clusterType: "OPENSEARCH_SERVERLESS",
                                                collectionType: collectionType,
                                                standbyReplicas: "DISABLED",
                                                domainRemovalPolicy: "DESTROY",
                                                dataAccessPrincipals: [jenkinsRoleArn, podRoleArn]
                                            ]
                                        ]
                                    ]
                                    def contextJson = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(context))
                                    writeFile(file: clusterContextFilePath, text: contextJson)
                                    sh "echo 'Cluster context:' && cat ${clusterContextFilePath}"

                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "./awsDeployCluster.sh --stage ${maStageName} --context-file ${clusterContextFilePath}"
                                    }

                                    def clusterDetails = readJSON text: readFile("tmp/cluster-details-${maStageName}.json")
                                    env.AOSS_COLLECTION_ENDPOINT = clusterDetails['target'].endpoint
                                    echo "AOSS: ${env.AOSS_COLLECTION_ENDPOINT}"
                                }
                            }
                        }
                    }
                }
            }

            stage('Run Migration & Tests') {
                steps {
                    timeout(time: 2, unit: 'HOURS') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                    // Normalize S3 URI - ensure it ends with /
                                    def s3RepoUri = params.S3_REPO_URI
                                    if (!s3RepoUri.endsWith('/')) {
                                        s3RepoUri = s3RepoUri + '/'
                                    }

                                    // Inject AOSS endpoint and snapshot config as container env vars
                                    sh """
                                        kubectl --context=${env.eksKubeContext} set env statefulset/migration-console \
                                          -n ma \
                                          ${endpointEnvVar}=${env.AOSS_COLLECTION_ENDPOINT} \
                                          AOSS_SNAPSHOT_NAME=${params.SNAPSHOT_NAME} \
                                          AOSS_S3_REPO_URI=${s3RepoUri} \
                                          AOSS_S3_REGION=${params.REGION} \
                                          AOSS_MONITOR_RETRY_LIMIT=${params.MONITOR_RETRY_LIMIT}
                                        kubectl --context=${env.eksKubeContext} rollout status statefulset/migration-console -n ma --timeout=120s
                                    """
                                }
                            }

                            dir('libraries/testAutomation') {
                                sh "pipenv install --deploy"
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "pipenv run app --source-version=${params.SOURCE_VERSION} --target-type=AOSS --test-ids='${testId}' --reuse-clusters --skip-delete --skip-install --kube-context=${env.eksKubeContext}"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                timeout(time: 60, unit: 'MINUTES') {
                    script {
                        def eksClusterName = env.eksClusterName

                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: params.REGION, duration: 3600, roleSessionName: 'jenkins-session') {
                                // 1. Delete cluster stack (AOSS collection — depends on MA VPC)
                                echo "CLEANUP: Deleting cluster stack ${env.CLUSTER_STACK}"
                                sh """
                                    aws cloudformation delete-stack --stack-name "${env.CLUSTER_STACK}" --region "${params.REGION}" || true
                                    aws cloudformation wait stack-delete-complete --stack-name "${env.CLUSTER_STACK}" --region "${params.REGION}" || true
                                    echo "Cluster stack deleted."
                                """

                                // 2. Clean up EKS resources (namespace, instance profiles, security groups)
                                eksCleanupStep(
                                    stackName: env.STACK_NAME,
                                    eksClusterName: eksClusterName
                                )

                                // 3. Delete MA stack last (owns the VPC)
                                echo "CLEANUP: Deleting MA stack ${env.STACK_NAME}"
                                sh """
                                    aws cloudformation delete-stack --stack-name "${env.STACK_NAME}" --region "${params.REGION}" || true
                                    aws cloudformation wait stack-delete-complete --stack-name "${env.STACK_NAME}" --region "${params.REGION}" || true
                                    echo "MA stack deleted."
                                """
                            }
                        }

                        sh """
                            if command -v kubectl >/dev/null 2>&1; then
                                kubectl config delete-context ${eksClusterName} 2>/dev/null || true
                            fi
                        """
                        echo "Cleanup completed"
                    }
                }
                archiveArtifacts artifacts: 'libraries/testAutomation/logs/**', allowEmptyArchive: true
            }
        }
    }
}
