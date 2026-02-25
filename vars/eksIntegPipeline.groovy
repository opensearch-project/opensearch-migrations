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
    def testIds = config.testIds ?: "0001,0002"
    def clusterContextFilePath = "tmp/cluster-context-integ-${currentBuild.number}.json"
    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
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

            stage('Deploy & Bootstrap MA') {
                steps {
                    timeout(time: 150, unit: 'MINUTES') {
                        script {
                            env.STACK_NAME_SUFFIX = "${maStageName}-us-east-1"
                            def clusterDetails = readJSON text: env.clusterDetailsJson
                            def targetCluster = clusterDetails.target
                            def vpcId = targetCluster.vpcId
                            def subnetIds = "${targetCluster.subnetIds}"

                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                    sh """
                                        ./deployment/k8s/aws/aws-bootstrap.sh \
                                          --deploy-import-vpc-cfn \
                                          --build-cfn \
                                          --stack-name "Migration-Assistant-Infra-Import-VPC-eks-${env.STACK_NAME_SUFFIX}" \
                                          --vpc-id "${vpcId}" \
                                          --subnet-ids "${subnetIds}" \
                                          --stage "${maStageName}" \
                                          --eks-access-principal-arn "arn:aws:iam::\${MIGRATIONS_TEST_ACCOUNT_ID}:role/JenkinsDeploymentRole" \
                                          --build-images \
                                          --build-chart-and-dashboards \
                                          --base-dir "\$(pwd)" \
                                          --skip-console-exec \
                                          --skip-setting-k8s-context \
                                          --region us-east-1 \
                                          2>&1 | while IFS= read -r line; do printf '%s | %s\\n' "\$(date '+%H:%M:%S')" "\$line"; done; exit \${PIPESTATUS[0]}
                                    """

                                    // Capture env vars for later stages and cleanup
                                    def rawOutput = sh(
                                        script: """
                                          aws cloudformation describe-stacks \
                                          --stack-name Migration-Assistant-Infra-Import-VPC-eks-${env.STACK_NAME_SUFFIX} \
                                          --query "Stacks[0].Outputs[?OutputKey=='MigrationsExportString'].OutputValue" \
                                          --output text
                                        """,
                                        returnStdout: true
                                    ).trim()
                                    def exportsMap = rawOutput.split(';')
                                            .collect { it.trim().replaceFirst(/^export\s+/, '') }
                                            .findAll { it.contains('=') }
                                            .collectEntries {
                                                def (key, value) = it.split('=', 2)
                                                [(key): value]
                                            }
                                    env.registryEndpoint = exportsMap['MIGRATIONS_ECR_REGISTRY']
                                    env.eksClusterName = exportsMap['MIGRATIONS_EKS_CLUSTER_NAME']
                                    env.clusterSecurityGroup = exportsMap['EKS_CLUSTER_SECURITY_GROUP']
                                    env.eksKubeContext = "migration-eks-cluster-${maStageName}-us-east-1"
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
                            withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: "us-east-1", duration: 1200, roleSessionName: 'jenkins-session') {
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
                                                    region: "us-east-1",
                                                    service: "es"
                                            ],
                                            version: env.targetVer
                                    ]
                                    sh """
                                      kubectl --context=${env.eksKubeContext} create configmap target-${targetVersionExpanded}-migration-config \
                                        --from-file=cluster-config=/tmp/target-cluster-config.json \
                                        --namespace ma --dry-run=client -o yaml | kubectl --context=${env.eksKubeContext} apply -f -

                                      kubectl --context=${env.eksKubeContext} -n ma get configmap target-${targetVersionExpanded}-migration-config -o yaml
                                    """

                                    // Modify source/target security group to allow EKS cluster security group
                                    sh """
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
                                        sh "pipenv run app --source-version=$sourceVer --target-version=$targetVer $testIdsArg --reuse-clusters --skip-delete --skip-install --kube-context=${env.eksKubeContext}"
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
                    script {
                        def region = "us-east-1"
                        def maStackName = "Migration-Assistant-Infra-Import-VPC-eks-${maStageName}-${region}"
                        def sourceDomainStackName = "OpenSearchDomain-source-${maStageName}-${region}"
                        def targetDomainStackName = "OpenSearchDomain-target-${maStageName}-${region}"
                        def networkStackName = "NetworkInfra-${maStageName}-${region}"

                        withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                            withAWS(role: 'JenkinsDeploymentRole', roleAccount: MIGRATIONS_TEST_ACCOUNT_ID, region: region, duration: 4500, roleSessionName: 'jenkins-session') {
                                // EKS/k8s cleanup (only if EKS was deployed)
                                if (env.eksClusterName) {
                                    dir('libraries/testAutomation') {
                                        sh "pipenv install --deploy"
                                        sh "kubectl --context=${env.eksKubeContext} -n ma get pods || true"
                                        sh "pipenv run app --delete-only --kube-context=${env.eksKubeContext}"
                                        echo "List resources not removed by helm uninstall:"
                                        sh "kubectl --context=${env.eksKubeContext} get all,pvc,configmap,secret,workflow -n ma -o wide --ignore-not-found || true"
                                        sh "kubectl --context=${env.eksKubeContext} delete namespace ma --ignore-not-found --timeout=60s || true"
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
                                }

                                // Delete all deployed CloudFormation stacks
                                // Order: MA stack first, then domain stacks, then network stack (VPC dependencies)
                                echo "CLEANUP: Deleting MA stack ${maStackName}"
                                sh "aws cloudformation delete-stack --stack-name ${maStackName} --region ${region} || true"
                                sh "aws cloudformation wait stack-delete-complete --stack-name ${maStackName} --region ${region} || true"

                                echo "CLEANUP: Deleting domain stacks"
                                sh "aws cloudformation delete-stack --stack-name ${sourceDomainStackName} --region ${region} || true"
                                sh "aws cloudformation delete-stack --stack-name ${targetDomainStackName} --region ${region} || true"
                                sh "aws cloudformation wait stack-delete-complete --stack-name ${sourceDomainStackName} --region ${region} || true"
                                sh "aws cloudformation wait stack-delete-complete --stack-name ${targetDomainStackName} --region ${region} || true"

                                echo "CLEANUP: Deleting network stack ${networkStackName}"
                                sh "aws cloudformation delete-stack --stack-name ${networkStackName} --region ${region} || true"
                                sh "aws cloudformation wait stack-delete-complete --stack-name ${networkStackName} --region ${region} || true"
                            }
                        }
                    }
                }
            }
        }
    }
}
