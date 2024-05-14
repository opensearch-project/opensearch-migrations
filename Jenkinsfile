pipeline {
    environment {
        // GIT_URL = 'https://github.com/mikaylathompson/opensearch-migrations.git'
        GIT_URL = 'https://github.com/opensearch-project/opensearch-migrations.git'
        GIT_BRANCH = 'main'
        STAGE = 'aws-integ'
    }

    agent any

    parameters {
        booleanParam(name: 'USE_LOCAL_WORKSPACE', defaultValue: false, description: 'Use local workspace for the build')
        string(name: 'BRANCH_NAME', defaultValue: 'main', description: 'Branch to build from')
    }

    stages {
        stage('Checkout') {
            agent any
            steps {
                script {
                    if (params.USE_LOCAL_WORKSPACE) {
                        sh "/copyGitTrackedFiles.sh /opensearch-migrations-src ."
                    } else {
                        git branch: "${params.BRANCH_NAME}", url: "${env.GIT_URL}"
                    }
                }
            }
        }

        stage('Test Caller Identity') {
            agent any
            steps {
                sh 'aws sts get-caller-identity'
            }
        }

        stage('Build') {
            agent any
            steps {
                timeout(time: 1, unit: 'HOURS') {
                    dir('TrafficCapture') {
                        sh './gradlew build -x test'
                    }
                }
            }
        }

        stage('Deploy') {
            steps {
                dir('test') {
                    sh 'sudo usermod -aG docker $USER'
                    sh 'sudo newgrp docker'
                    sh "sudo ./awsE2ESolutionSetup.sh --stage ${env.STAGE} --migrations-git-url ${env.GIT_URL} --migrations-git-branch ${env.GIT_BRANCH}"
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
//     post {
//         always {
//             dir('test') {
//                 sh "sudo ./awsE2ESolutionSetup.sh --stage ${env.STAGE} --run-post-actions"
//             }
//         }
//     }
}