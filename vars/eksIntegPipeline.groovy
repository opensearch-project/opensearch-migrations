import groovy.json.JsonOutput

def call(Map config = [:]) {
    def defaultStageId = config.defaultStageId ?: "eks-integ"
    def jobName = config.jobName ?: "eks-integ-test"
    def sourceVersion = config.sourceVersion ?: ""
    def targetVersion = config.targetVersion ?: ""
    def sourceClusterType = config.sourceClusterType ?: ""
    def targetClusterType = config.targetClusterType ?: ""
    def clusterContextFilePath = "tmp/cluster-context-integ-${currentBuild.number}.json"
    def time = new Date().getTime()
    def testUniqueId = config.testUniqueId ?: "integ_full_${time}_${currentBuild.number}"
    def testDir = "/root/lib/integ_test/integ_test"
    def integTestCommand = config.integTestCommand ?: "${testDir}/ma_workflow_test.py"
    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/lewijacn/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'eks-pipeline', description: 'Git branch to use for repository')
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
                    choices: ['OS_1.3', 'OS_2.19'],
                    description: 'Pick a specific target version'
            )
            choice(
                    name: 'TARGET_CLUSTER_TYPE',
                    choices: ['OPENSEARCH_MANAGED_SERVICE'],
                    description: 'Pick a target cluster type'
            )
            string(name: 'TEST_IDS', defaultValue: 'all', description: 'Test IDs to execute. Use comma separated list e.g. "0001,0004" or "all" for all tests')
        }

        options {
            // Acquire lock on a given deployment stage
            lock(label: params.STAGE, quantity: 1, variable: 'maStageName')
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
                    causeString: 'Triggered by PR on opensearch-migrations repository',
                    regexpFilterExpression: "^$jobName\$",
                    regexpFilterText: "\$job_name",
            )
        }

        stages {
            stage('Checkout') {
                steps {
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL)
                }
            }

            stage('Test Caller Identity') {
                steps {
                    script {
                        sh 'aws sts get-caller-identity'
                    }
                }
            }

//            stage('Build') {
//                steps {
//                    timeout(time: 1, unit: 'HOURS') {
//                        script {
//                            sh './gradlew clean build --no-daemon --stacktrace'
//                        }
//                    }
//                }
//            }

            stage('Deploy Clusters') {
                steps {
                    timeout(time: 60, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                env.sourceVer = sourceVersion ?: params.SOURCE_VERSION
                                env.targetVer = targetVersion ?: params.TARGET_VERSION
                                env.sourceClusterType = sourceClusterType ?: params.SOURCE_CLUSTER_TYPE
                                env.targetClusterType = targetClusterType ?: params.TARGET_CLUSTER_TYPE
                                def clusterContextValues = [
                                        stage      : "<STAGE>",
                                        vpcAZCount : 2,
                                        clusters   : [
                                                [
                                                        clusterId                 : "source",
                                                        clusterVersion            : "${env.sourceVer}",
                                                        clusterType               : "${env.sourceClusterType}",
                                                        openAccessPolicyEnabled   : true
                                                ],
                                                [
                                                        clusterId                 : "target",
                                                        clusterVersion            : "${env.targetVer}",
                                                        clusterType               : "${env.targetClusterType}",
                                                        openAccessPolicyEnabled   : true
                                                ]
                                        ]
                                ]
                                def contextJsonStr = JsonOutput.prettyPrint(JsonOutput.toJson(clusterContextValues))
                                writeFile (file: "${clusterContextFilePath}", text: contextJsonStr)
                                sh "echo 'Using cluster context file options: ' && cat ${clusterContextFilePath}"
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "./awsDeployCluster.sh --stage ${maStageName} --context-file ${clusterContextFilePath}"
                                    }
                                }

                                def rawJsonFile = readFile "tmp/cluster-details-${maStageName}.json"
                                echo "Cluster details JSON:\n${rawJsonFile}"
                                env.clusterDetailsJson = rawJsonFile
                            }
                        }
                    }
                }
            }

            stage('Deploy MA Stack') {
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        dir('deployment/migration-assistant-solution') {
                            script {
                                env.STACK_NAME_SUFFIX = "${maStageName}-us-east-1"
                                def clusterDetails = readJSON text: env.clusterDetailsJson
                                def targetCluster = clusterDetails.target
                                def vpcId = targetCluster.vpcId
                                def subnetIds = "${targetCluster.subnetIds}"

                                sh "npm install"
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh """
                                            cdk deploy Migration-Assistant-Infra-Import-VPC-v3-${env.STACK_NAME_SUFFIX} \
                                              --parameters Stage=${maStageName} \
                                              --parameters VPCId=${vpcId} \
                                              --parameters VPCSubnetIds=${subnetIds} \
                                              --require-approval never \
                                              --concurrency 3
                                        """
                                    }
                                }
                            }
                        }
                    }
                }
            }

            stage('Configure EKS') {
                steps {
                    timeout(time: 30, unit: 'MINUTES') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 1200, roleSessionName: 'jenkins-session') {
                                    def rawOutput = sh(
                                            script: """
                                              aws cloudformation describe-stacks \
                                              --stack-name Migration-Assistant-Infra-Import-VPC-v3-${env.STACK_NAME_SUFFIX} \
                                              --query "Stacks[0].Outputs[?OutputKey=='MigrationsExportString'].OutputValue" \
                                              --output text
                                            """,
                                            returnStdout: true
                                    ).trim()
                                    if (!rawOutput) {
                                        error("Could not retrieve CloudFormation Output 'MigrationsExportString' from stack Migration-Assistant-Infra-Import-VPC-v3-${env.STACK_NAME_SUFFIX}")
                                    }

                                    def pairs = rawOutput.split(';').collect { it.trim().replaceFirst(/^export\s+/, '') }
                                    if (!pairs || pairs.size() == 0) {
                                        error("No key=value pairs found in MigrationsExportString output")
                                    }
                                    echo "Found ${pairs.size()} key=value pairs in MigrationsExportString output"

                                    def registryPair = pairs.find { it.startsWith("MIGRATIONS_ECR_REGISTRY=") }
                                    if (!registryPair) {
                                        error("MIGRATIONS_ECR_REGISTRY key not found in MigrationsExportString output")
                                    }
                                    env.registryEndpoint = registryPair.split('=')[1]

                                    def eksClusterPair = pairs.find { it.startsWith("MIGRATIONS_EKS_CLUSTER_NAME=") }
                                    if (!eksClusterPair) {
                                        error("MIGRATIONS_EKS_CLUSTER_NAME key not found in MigrationsExportString output")
                                    }
                                    env.eksClusterName = eksClusterPair.split('=')[1]

                                    def principalArn = 'arn:aws:iam::$MIGRATIONS_TEST_ACCOUNT_ID:role/JenkinsDeploymentRole'

                                    sh """
                                        if aws eks describe-access-entry --cluster-name $env.eksClusterName --principal-arn $principalArn >/dev/null 2>&1; then
                                          echo "Access entry already exists, skipping create."
                                        else
                                          aws eks create-access-entry --cluster-name $env.eksClusterName --principal-arn $principalArn --type STANDARD
                                        fi
                                        
                                        aws eks associate-access-policy \
                                          --cluster-name $env.eksClusterName \
                                          --principal-arn $principalArn \
                                          --policy-arn arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy \
                                          --access-scope type=cluster
                                        
                                        # Update kubeconfig to use this role
                                        aws eks update-kubeconfig --region us-east-1 --name $env.eksClusterName
                                    """

                                    sh 'kubectl create namespace ma --dry-run=client -o yaml | kubectl apply -f -'
                                    def clusterDetails = readJSON text: env.clusterDetailsJson
                                    def sourceCluster = clusterDetails.source
                                    writeJSON file: '/tmp/source-cluster-config.json', json: [
                                            endpoint: sourceCluster.endpoint,
                                            allow_insecure: true,
                                            sigv4: [
                                                    region: "us-east-1",
                                                    service: "es"
                                            ],
                                            version: env.sourceVer
                                    ]
                                    sh """
                                      kubectl create configmap source-elasticsearch-7-10-migration-config \
                                        --from-file=cluster-config=/tmp/source-cluster-config.json \
                                        --namespace ma --dry-run=client -o yaml | kubectl apply -f -
                                    
                                      kubectl -n ma get configmap source-elasticsearch-7-10-migration-config -o yaml
                                    """

                                    def targetCluster = clusterDetails.target
                                    writeJSON file: '/tmp/target-cluster-config.json', json: [
                                            endpoint: targetCluster.endpoint,
                                            allow_insecure: true,
                                            sigv4: [
                                                    region: "us-east-1",
                                                    service: "es"
                                            ],
                                            version: env.targetVer
                                    ]
                                    sh """
                                      kubectl create configmap target-opensearch-1-3-migration-config \
                                        --from-file=cluster-config=/tmp/target-cluster-config.json \
                                        --namespace ma --dry-run=client -o yaml | kubectl apply -f -
                                    
                                      kubectl -n ma get configmap target-opensearch-1-3-migration-config -o yaml
                                    """

                                }
                            }
                        }
                    }
                }
            }

            stage('Build Docker Images') {
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        script {
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                    def builderExists = sh(
                                            script: "docker buildx ls | grep -q '^ecr-builder'",
                                            returnStatus: true
                                    ) == 0

                                    if (builderExists) {
                                        echo "The buildx builder 'ecr-builder' already exists"
                                    } else {
                                        sh "docker buildx create --name ecr-builder --driver docker-container"
                                    }
                                    //sh "./gradlew buildImagesToRegistry -PregistryEndpoint=${env.registryEndpoint} -PimageArch=amd64 -Pbuilder=ecr-builder"
                                }
                            }
                        }
                    }
                }
            }

            stage('Install Helm Chart') {
                steps {
                    timeout(time: 15, unit: 'MINUTES') {
                        dir('deployment/k8s/aws') {
                            script {
                                def clusterDetails = readJSON text: env.clusterDetailsJson
                                def targetCluster = clusterDetails.target
                                def securityGroupIds = "${targetCluster.securityGroupId}"
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "./aws-bootstrap.sh --security-group-ids \"${securityGroupIds}\" --skip-git-pull --base-dir /home/ec2-user/workspace/eks-integ-test --use-public-images false --skip-console-exec"
                                    }
                                }
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
//                                def testIdsArg = ""
//                                def testIdsResolved = testIds ?: params.TEST_IDS
//                                if (testIdsResolved != "" && testIdsResolved != "all") {
//                                    testIdsArg = "--test-ids='$testIdsResolved'"
//                                }
                                sh "pipenv install --deploy"
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "pipenv run app --source-version=$sourceVer --target-version=$targetVer --test-ids=0001 --reuse-clusters --skip-delete "
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
                timeout(time: 15, unit: 'MINUTES') {
                    dir('libraries/testAutomation') {
                        script {
                            sh "pipenv install --deploy"
                            if (env.eksClusterName) {
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "kubectl -n ma config current-context"
                                        sh "kubectl -n ma get pods"
                                        //sh "pipenv run app --delete-only"
                                        //sh "kubectl -n ma delete namespace ma"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
