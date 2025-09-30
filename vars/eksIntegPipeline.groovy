import groovy.json.JsonOutput
import groovy.json.JsonSlurper

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
                                    stage     : "<STAGE>",
                                    vpcAZCount: 2,
                                    clusters  : [
                                        [
                                                clusterId     : "source",
                                                clusterVersion: "${env.sourceVer}",
                                                clusterType   : "${env.sourceClusterType}"
                                        ],
                                        [
                                                clusterId     : "target",
                                                clusterVersion: "${env.targetVer}",
                                                clusterType   : "${env.targetClusterType}"
                                        ]
                                    ]
                                ]
                                def contextJsonStr = JsonOutput.prettyPrint(JsonOutput.toJson(clusterContextValues))
                                writeFile (file: "${clusterContextFilePath}", text: contextJsonStr)
                                sh "echo 'Using cluster context file options: ' && cat ${clusterContextFilePath}"
                                sh "./awsDeployCluster.sh --stage ${maStageName} --context-file ${clusterContextFilePath}"

                                def rawJsonFile = readFile "tmp/cluster-details-${maStageName}"
                                def parsedClusterDetails = new JsonSlurper().parseText(rawJsonFile)

                                // Store as global variable so other stages can use it
                                clusterDetails = parsedClusterDetails
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
                                def sourceCluster = clusterDetails.clusters.find { it.clusterId == 'source' }
                                def targetCluster = clusterDetails.clusters.find { it.clusterId == 'target' }
                                def vpcId = targetCluster.vpcId
                                def securityGroupIds = "${sourceCluster.securityGroupId},${targetCluster.securityGroupId}"
                                sh "npm install"
                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
                                        sh "cdk deploy Migration-Assistant-Infra-Import-VPC-v3-${env.STACK_NAME_SUFFIX} --parameters Stage=${maStageName} --parameters VPCId=${vpcId} --parameters VPCSecurityGroupIds=${securityGroupIds} --require-approval never --concurrency 3"
                                    }
                                }
                            }
                        }
                    }
                }
            }

//            stage('Build Docker Images') {
//                steps {
//                    timeout(time: 1, unit: 'HOURS') {
//                        script {
//                            sh './gradlew buildImagesToRegistry -PregistryEndpoint=123456789012.dkr.ecr.us-west-2.amazonaws.com/my-ecr-repo -PimageArch=amd64'
//                        }
//                    }
//                }
//            }
//
//            stage('Install Helm Chart') {
//                steps {
//                    timeout(time: 15, unit: 'MINUTES') {
//                        dir('deployment/k8s') {
//                            script {
//                                env.STACK_NAME_SUFFIX = "${maStageName}-us-east-1"
//                                sh "npm install"
//                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
//                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", region: "us-east-1", duration: 3600, roleSessionName: 'jenkins-session') {
//                                        sh "./aws-bootstrap.sh"
//                                    }
//                                }
//                            }
//                        }
//                    }
//                }
//            }
//
//            stage('Integ Tests') {
//                steps {
//                    timeout(time: 1, unit: 'HOURS') {
//                        dir('test') {
//                            script {
//                                def test_result_file = "${testDir}/reports/${testUniqueId}/report.xml"
//                                def populatedIntegTestCommand = integTestCommand.replaceAll("<STAGE>", maStageName)
//                                def command = "pipenv run pytest --log-file=${testDir}/reports/${testUniqueId}/pytest.log " +
//                                        "--junitxml=${test_result_file} ${populatedIntegTestCommand} " +
//                                        "--unique_id ${testUniqueId} " +
//                                        "--stage ${maStageName} " +
//                                        "-s"
//                                withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
//                                    withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-session') {
//                                        sh "./awsRunIntegTests.sh --command '${command}' " +
//                                                "--test-result-file ${test_result_file} " +
//                                                "--stage ${maStageName}"
//                                    }
//                                }
//                            }
//                        }
//                    }
//                }
//            }
        }
        post {
            always {
                timeout(time: 10, unit: 'MINUTES') {
                    dir('test') {
                        script {
                            sh "echo 'Default post step performs no actions'"
                        }
                    }
                }
            }
        }
    }
}
