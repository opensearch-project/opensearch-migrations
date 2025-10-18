static def expandVersionString(String input) {
    def trimmed = input.trim()
    def pattern = ~/^(ES|OS)_(\d+)\.(\d+)$/
    def matcher = trimmed =~ pattern
    if (!matcher.matches()) {
        error("Invalid version string format: '${input}'. Expected something like ES_7.10 or OS_1.3")
    }
    def prefix = matcher[0][1]
    def major  = matcher[0][2]
    def minor  = matcher[0][3]
    def name   = (prefix == 'ES') ? 'elasticsearch' : 'opensearch'
    return "${name}-${major}-${minor}"
}

def call(Map config = [:]) {
    def defaultStageId = config.defaultStageId ?: "eks-integ"
    def jobName = config.jobName ?: "eks-integ-test"
    def sourceVersion = config.sourceVersion ?: ""
    def targetVersion = config.targetVersion ?: ""
    def sourceClusterType = config.sourceClusterType ?: ""
    def targetClusterType = config.targetClusterType ?: ""
    def testIds = config.testIds ?: "0001"
    def clusterContextFilePath = "tmp/cluster-context-integ-${currentBuild.number}.json"
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
                    choices: ['OS_1.3', 'OS_2.19', 'OS_3.1'],
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

            stage('Build') {
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        script {
                            sh './gradlew clean build --no-daemon --stacktrace'
                        }
                    }
                }
            }

            stage('Deploy Clusters') {
                steps {
                    timeout(time: 60, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                env.sourceVer = sourceVersion ?: params.SOURCE_VERSION
                                env.targetVer = targetVersion ?: params.TARGET_VERSION
                                env.sourceClusterType = sourceClusterType ?: params.SOURCE_CLUSTER_TYPE
                                env.targetClusterType = targetClusterType ?: params.TARGET_CLUSTER_TYPE
                                deployClustersStep(
                                    stage: "${maStageName}",
                                    clusterContextFilePath: "${clusterContextFilePath}",
                                    sourceVer: env.sourceVer,
                                    sourceClusterType: env.sourceClusterType,
                                    targetVer: env.targetVer,
                                    targetClusterType: env.targetClusterType
                                )
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
                                    def exportsMap = rawOutput.split(';')
                                            .collect { it.trim().replaceFirst(/^export\s+/, '') }
                                            .findAll { it.contains('=') }
                                            .collectEntries {
                                                def (key, value) = it.split('=', 2)
                                                [(key): value]
                                            }
                                    if (!exportsMap) {
                                        error("No key=value pairs found in MigrationsExportString output")
                                    }
                                    echo "Found ${exportsMap.size()} key=value pairs in MigrationsExportString output"
                                    env.registryEndpoint = exportsMap['MIGRATIONS_ECR_REGISTRY']
                                    env.eksClusterName = exportsMap['MIGRATIONS_EKS_CLUSTER_NAME']
                                    env.clusterSecurityGroup = exportsMap['EKS_CLUSTER_SECURITY_GROUP']

                                    // Add access policy for Jenkins deployment role to perform kubectl commands
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

                                        # Wait until kubectl is fully ready
                                        for i in {1..10}; do
                                          if kubectl get namespace default >/dev/null 2>&1; then
                                            echo "kubectl is ready for use"
                                            break
                                          fi
                                          echo "Waiting for kubectl to be ready... (\$i/10)"
                                          sleep 5
                                        done
                                    """

                                    // TODO: Remove this source and target cluster configmaps when integ test utilizes workflow CLI to generate
                                    sh 'kubectl create namespace ma --dry-run=client -o yaml | kubectl apply -f -'
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
                                                    region: "us-east-1",
                                                    service: "es"
                                            ],
                                            version: env.sourceVer
                                    ]
                                    sh """
                                      kubectl create configmap source-${sourceVersionExpanded}-migration-config \
                                        --from-file=cluster-config=/tmp/source-cluster-config.json \
                                        --namespace ma --dry-run=client -o yaml | kubectl apply -f -
                                    
                                      kubectl -n ma get configmap source-${sourceVersionExpanded}-migration-config -o yaml
                                    """
                                    // Target configmap
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
                                      kubectl create configmap target-${targetVersionExpanded}-migration-config \
                                        --from-file=cluster-config=/tmp/target-cluster-config.json \
                                        --namespace ma --dry-run=client -o yaml | kubectl apply -f -
                                    
                                      kubectl -n ma get configmap target-${targetVersionExpanded}-migration-config -o yaml
                                    """

                                    // Modify source/target security group to allow EKS cluster security group
                                    sh """
                                      # Check if the ingress rule from clusterSecurityGroup already exists
                                      exists=\$(aws ec2 describe-security-groups \
                                        --group-ids $targetCluster.securityGroupId \
                                        --query "SecurityGroups[0].IpPermissions[?UserIdGroupPairs[?GroupId=='$env.clusterSecurityGroup']]" \
                                        --output text)
                                    
                                      if [ -z "\$exists" ]; then
                                        echo "Ingress rule not found. Adding..."
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
                                    sh "./gradlew buildImagesToRegistry -PregistryEndpoint=${env.registryEndpoint} -PimageArch=amd64 -Pbuilder=ecr-builder"
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
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "./aws-bootstrap.sh --skip-git-pull --base-dir /home/ec2-user/workspace/eks-integ-test --use-public-images false --skip-console-exec"
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
                                def testIdsArg = ""
                                def testIdsResolved = testIds ?: params.TEST_IDS
                                if (testIdsResolved != "" && testIdsResolved != "all") {
                                    testIdsArg = "--test-ids='$testIdsResolved'"
                                }
                                sh "pipenv install --deploy"
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "pipenv run app --source-version=$sourceVer --target-version=$targetVer $testIdsArg --reuse-clusters --skip-delete "
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
                timeout(time: 75, unit: 'MINUTES') {
                    dir('libraries/testAutomation') {
                        script {
                            sh "pipenv install --deploy"
                            if (env.eksClusterName) {
                                def clusterDetails = readJSON text: env.clusterDetailsJson
                                def targetCluster = clusterDetails.target
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 4500, roleSessionName: 'jenkins-session') {
                                        sh "kubectl -n ma get pods"
                                        sh "pipenv run app --delete-only"
                                        echo "List resources not removed by helm uninstall:"
                                        sh "kubectl get all,pvc,configmap,secret,servicemonitor,workflow -n ma -o wide"
                                        sh "kubectl -n ma delete namespace ma"
                                        // Remove added security group rule to allow proper cleanup of stacks
                                        sh """
                                          echo "Checking if source/target security group $targetCluster.securityGroupId exists..."
                                        
                                          if ! aws ec2 describe-security-groups --group-ids $targetCluster.securityGroupId >/dev/null 2>&1; then
                                            echo "Security group $targetCluster.securityGroupId does not exist. Skipping cleanup."
                                            exit 0
                                          fi
                                        
                                          echo "Checking for existing ingress rule to remove..."
                                        
                                          exists=\$(aws ec2 describe-security-groups \
                                            --group-ids $targetCluster.securityGroupId \
                                            --query "SecurityGroups[0].IpPermissions[?UserIdGroupPairs[?GroupId=='$env.clusterSecurityGroup']]" \
                                            --output json)
                                        
                                          if [ "\$exists" != "[]" ]; then
                                            echo "Ingress rule found. Revoking..."
                                            aws ec2 revoke-security-group-ingress \
                                              --group-id $targetCluster.securityGroupId \
                                              --protocol -1 \
                                              --port -1 \
                                              --source-group $env.clusterSecurityGroup
                                          else
                                            echo "No ingress rule to revoke."
                                          fi
                                        """
                                        sh "cd $WORKSPACE/deployment/migration-assistant-solution && cdk destroy Migration-Assistant-Infra-Import-VPC-v3-${env.STACK_NAME_SUFFIX} --force --concurrency 3"
                                        sh "cd $WORKSPACE/test/amazon-opensearch-service-sample-cdk && cdk destroy '*' --force --concurrency 3 && rm -f cdk.context.json"
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
