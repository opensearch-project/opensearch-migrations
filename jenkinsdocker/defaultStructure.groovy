def source_cdk_context = """
{
  "source-single-node-ec2": {
    "suffix": "ec2-source-<STAGE>",
    "networkStackSuffix": "ec2-source-<STAGE>",
    "vpcId": "vpc-07d3e089b8e9139e4",
    "distVersion": "7.10.2",
    "cidr": "12.0.0.0/16",
    "distributionUrl": "https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-oss-7.10.2-linux-x86_64.tar.gz",
    "captureProxyEnabled": true,
    "securityDisabled": true,
    "minDistribution": false,
    "cpuArch": "x64",
    "isInternal": true,
    "singleNodeCluster": true,
    "networkAvailabilityZones": 2,
    "dataNodeCount": 1,
    "managerNodeCount": 0,
    "serverAccessType": "ipv4",
    "restrictServerAccessTo": "0.0.0.0/0"
  }
}
"""
def migration_cdk_context = """
{
  "migration-default": {
    "stage": "<STAGE>",
    "vpcId": "vpc-07d3e089b8e9139e4",
    "engineVersion": "OS_2.11",
    "domainName": "os-cluster-<STAGE>",
    "dataNodeCount": 2,
    "openAccessPolicyEnabled": true,
    "domainRemovalPolicy": "DESTROY",
    "artifactBucketRemovalPolicy": "DESTROY",
    "trafficReplayerExtraArgs": "--speedup-factor 10.0",
    "fetchMigrationEnabled": true,
    "reindexFromSnapshotServiceEnabled": true,
    "sourceClusterEndpoint": "<SOURCE_CLUSTER_ENDPOINT>",
    "dpPipelineTemplatePath": "../../../test/dp_pipeline_aws_integ.yaml",
    "migrationConsoleEnableOSI": true,
    "migrationAPIEnabled": true
  }
}
"""
def source_context_file_name = 'sourceJenkinsContext.json'
def migration_context_file_name = 'migrationJenkinsContext.json'
void call(Map arguments = [:], Closure body) {
    pipeline {
        environment {
            // GIT_URL = 'https://github.com/mikaylathompson/opensearch-migrations.git'
            // GIT_BRANCH = 'add-metric-verification-to-tests'
            GIT_URL = 'https://github.com/opensearch-project/opensearch-migrations.git'
            GIT_BRANCH = 'main'
            STAGE = 'aws-integ'
        }

        parameters {
            string(name: 'BRANCH_NAME', defaultValue: 'main', description: 'The branch to build')
            string(name: 'DEPLOY_STAGE', description: 'Deployment environment')
        }

        agent { label 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        stages {
            stage('Checkout') {
                steps {
                    git branch: "${env.GIT_BRANCH}", url: "${env.GIT_URL}"
                }
            }

            stage('Test Caller Identity') {
                steps {
                    sh 'aws sts get-caller-identity'
                }
            }

            stage('Setup E2E CDK Context') {
                steps {
                    writeFile (file: "test/$source_context_file_name", text: source_cdk_context)
                    sh "echo 'Using source context file options: ' && cat test/$source_context_file_name"
                    writeFile (file: "test/$migration_context_file_name", text: migration_cdk_context)
                    sh "echo 'Using migration context file options: ' && cat test/$migration_context_file_name"
                }
            }

            stage('Build') {
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        sh 'sudo ./gradlew clean build'
                    }
                }
            }

            stage('Deploy') {
                steps {
                    dir('test') {
                        sh 'sudo usermod -aG docker $USER'
                        sh 'sudo newgrp docker'
                        sh "sudo ./awsE2ESolutionSetup.sh --source-context-file './$source_context_file_name' --migration-context-file './$migration_context_file_name' --source-context-id source-single-node-ec2 --migration-context-id migration-default --stage ${env.STAGE} --migrations-git-url ${env.GIT_URL} --migrations-git-branch ${env.GIT_BRANCH}"
                    }
                }
            }

            stage('Integ Tests') {
                steps {
                    dir('test') {
                        script {
                            def time = new Date().getTime()
                            def uniqueId = "integ_min_${time}_${currentBuild.number}"
                            sh "sudo ./awsRunIntegTests.sh --stage ${env.STAGE} --migrations-git-url ${env.GIT_URL} --migrations-git-branch ${env.GIT_BRANCH} --unique-id ${uniqueId}"
                        }
                    }

                }
            }
        }
        post {
            always {
                dir('test') {
                    sh "sudo ./awsE2ESolutionSetup.sh --stage ${env.STAGE} --run-post-actions"
                }
            }
        }
    }
}
