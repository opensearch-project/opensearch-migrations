def call(Map config = [:]) {
    def gitBranchDefault = config.gitBranchDefault ?: 'main'
    def testId = '0021'
    def collections = [
        [id: 'search', type: 'SEARCH'],
        [id: 'timeseries', type: 'TIMESERIES'],
        [id: 'vector', type: 'VECTORSEARCH'],
    ]
    def defaultStageId = config.defaultStageId ?: "aosss"
    def jobName = config.jobName ?: "pr-eks-aoss-search-integ-test"
    def cronSchedule = config.disablePeriodic ? '' : periodicCron(jobName)
    def lockLabel = config.lockLabel ?: (jobName.startsWith("pr-") ? "aws-pr-slot" : "aws-main-slot")
    def clusterContextFilePath = "tmp/cluster-context-aoss-${currentBuild.number}.json"

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: gitBranchDefault, description: 'Git branch to use for repository')
            string(name: 'GIT_COMMIT', defaultValue: '', description: '(Optional) Specific commit to checkout after cloning branch')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
            string(name: 'REGION', defaultValue: 'us-east-1', description: 'AWS region for deployment')
            choice(name: 'SOURCE_VERSION', choices: ['OS_1.3'], description: 'Version of the cluster that created the snapshot')
            string(name: 'RFS_WORKERS', defaultValue: '1', description: 'Number of RFS worker pods for document backfill')
            // Snapshot configuration
            string(name: 'S3_REPO_URI', defaultValue: 's3://migrations-snapshots-library-us-east-1/aoss-osb-data/os1x-aoss-osb-data/', description: 'Full S3 URI to snapshot repository')
            string(name: 'SNAPSHOT_NAME', defaultValue: 'os1x-aoss-osb-data', description: 'Name of the snapshot')
            string(name: 'MONITOR_RETRY_LIMIT', defaultValue: '33', description: 'Max retries for workflow monitoring (~1/min). 33=~30min')
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
                causeString: 'Triggered by webhook',
                regexpFilterExpression: "^$jobName\$",
                regexpFilterText: "\$job_name",
            )
            cron(cronSchedule)
        }

        stages {
            stage('Checkout & Print Params') {
                steps {
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL, commit: params.GIT_COMMIT)
                    script {
                        def pool = jobName.startsWith("main-") ? "m" : jobName.startsWith("release-") ? "r" : "p"
                        env.maStageName = "${params.STAGE ?: defaultStageId}-${pool}${currentBuild.number}"
                        env.STACK_NAME = "MA-Serverless-${maStageName}-${params.REGION}"
                        env.CLUSTER_STACK = "OpenSearch-${maStageName}-${params.REGION}"

                        echo """
                            ================================================================
                            AOSS Collection Integration Test
                            ================================================================
                            Git:              ${params.GIT_REPO_URL} @ ${params.GIT_BRANCH}
                            Stage:            ${maStageName}
                            Region:           ${params.REGION}
                            Collection Types: SEARCH, TIMESERIES, VECTORSEARCH
                            Test ID:          ${testId}
                            Source:           ${params.SOURCE_VERSION}
                            Workers:          ${params.RFS_WORKERS}
                            Build:                  ${params.BUILD}
                            Use Release Bootstrap:  ${params.USE_RELEASE_BOOTSTRAP}
                            Version:                ${params.VERSION}
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
                    timeout(time: 90, unit: 'MINUTES') {
                        script {
                            withMigrationsTestAccount(region: params.REGION, duration: 7200) { accountId ->
                                def bootstrap = resolveBootstrap(
                                    useReleaseBootstrap: params.USE_RELEASE_BOOTSTRAP,
                                    build: params.BUILD,
                                    skipTestImages: true,
                                    version: params.VERSION,
                                    useGeneralNodePool: true
                                )
                                bootstrapMA(
                                    stackName: env.STACK_NAME,
                                    stage: maStageName,
                                    region: params.REGION,
                                    bootstrap: bootstrap,
                                    eksAccessPrincipalArn: "arn:aws:iam::${accountId}:role/JenkinsDeploymentRole",
                                    kubectlContext: "migration-eks-${maStageName}"
                                )

                                echo "MA VPC: ${env.maVpcId}"
                            }
                        }
                    }
                }
            }

            stage('Deploy AOSS Targets') {
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
                                        clusters: collections.collect { collection ->
                                            [
                                                clusterId: collection.id,
                                                clusterName: "${maStageName}-${collection.id}",
                                                clusterType: "OPENSEARCH_SERVERLESS",
                                                collectionType: collection.type,
                                                standbyReplicas: "DISABLED",
                                                domainRemovalPolicy: "DESTROY",
                                                dataAccessPrincipals: [jenkinsRoleArn, podRoleArn]
                                            ]
                                        }
                                    ]
                                    def contextJson = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(context))
                                    writeFile(file: clusterContextFilePath, text: contextJson)
                                    sh "echo 'Cluster context:' && cat ${clusterContextFilePath}"

                                    sh "./awsDeployCluster.sh --stage ${maStageName} --context-file ${clusterContextFilePath}"

                                    def clusterDetails = readJSON text: readFile("tmp/cluster-details-${maStageName}.json")
                                    env.AOSS_SEARCH_ENDPOINT = clusterDetails['search'].endpoint
                                    env.AOSS_TIMESERIES_ENDPOINT = clusterDetails['timeseries'].endpoint
                                    env.AOSS_VECTOR_ENDPOINT = clusterDetails['vector'].endpoint
                                    echo "AOSS search: ${env.AOSS_SEARCH_ENDPOINT}"
                                    echo "AOSS time-series: ${env.AOSS_TIMESERIES_ENDPOINT}"
                                    echo "AOSS vector: ${env.AOSS_VECTOR_ENDPOINT}"
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
                            withMigrationsTestAccount(region: params.REGION) { accountId ->
                                // Normalize S3 URI - ensure it ends with /
                                def s3RepoUri = params.S3_REPO_URI
                                if (!s3RepoUri.endsWith('/')) {
                                    s3RepoUri = s3RepoUri + '/'
                                }

                                // Inject AOSS endpoint and snapshot config as container env vars
                                sh """
                                    kubectl --context=${env.eksKubeContext} set env statefulset/migration-console \
                                      -n ma \
                                      AOSS_SEARCH_ENDPOINT=${env.AOSS_SEARCH_ENDPOINT} \
                                      AOSS_TIMESERIES_ENDPOINT=${env.AOSS_TIMESERIES_ENDPOINT} \
                                      AOSS_VECTOR_ENDPOINT=${env.AOSS_VECTOR_ENDPOINT} \
                                      AOSS_SNAPSHOT_NAME=${params.SNAPSHOT_NAME} \
                                      AOSS_S3_REPO_URI=${s3RepoUri} \
                                      AOSS_S3_REGION=${params.REGION} \
                                      AOSS_MONITOR_RETRY_LIMIT=${params.MONITOR_RETRY_LIMIT}
                                    kubectl --context=${env.eksKubeContext} rollout status statefulset/migration-console -n ma --timeout=120s
                                """
                            }

                            dir('libraries/testAutomation') {
                                sh "pipenv install --deploy"
                                withMigrationsTestAccount(region: params.REGION) { accountId ->
                                    sh "pipenv run app --source-version=${params.SOURCE_VERSION} --target-type=AOSS --test-ids='${testId}' --reuse-clusters --skip-delete --skip-install --kube-context=${env.eksKubeContext}"
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
                    timeoutMinutes: 60,
                )
            }
        }
    }
}
