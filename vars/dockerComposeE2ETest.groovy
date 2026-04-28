def call(Map config = [:]) {
    def jobName = config.jobName ?: "docker-compose-e2e-test"

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
            string(name: 'GIT_COMMIT', defaultValue: '', description: '(Optional) Specific commit to checkout after cloning branch')
        }

        options {
            timeout(time: 30, unit: 'MINUTES')
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
                    regexpFilterExpression: "^${jobName}\$",
                    regexpFilterText: '$job_name'
            )
        }

        stages {
            stage('Checkout') {
                steps {
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL, commit: params.GIT_COMMIT)
                }
            }

            stage('Build Docker Images') {
                steps {
                    timeout(time: 15, unit: 'MINUTES') {
                        sh './deployment/cdk/opensearch-service-migration/buildDockerImages.sh'
                    }
                }
            }

            stage('Docker Compose Up') {
                steps {
                    timeout(time: 3, unit: 'MINUTES') {
                        dir('TrafficCapture/dockerSolution') {
                            sh 'docker compose -f src/main/docker/docker-compose.yml up -d'
                            sh 'sleep 30'
                            sh 'docker ps'
                        }
                    }
                }
            }

            stage('Run E2E Tests') {
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        sh 'docker exec $(docker ps --filter "name=migration-console" -q) pipenv run pytest /root/lib/integ_test/integ_test/replayer_tests.py --unique_id="testindex" -s'
                    }
                }
            }
        }

        post {
            always {
                script {
                    sh 'mkdir -p logs/docker'
                    sh '''
                        for container in $(docker ps -aq); do
                            container_name=$(docker inspect --format '{{.Name}}' $container | sed 's/\\///')
                            docker logs $container > logs/docker/${container_name}_logs.txt 2>&1 || true
                        done
                    '''
                    archiveArtifacts artifacts: 'logs/docker/**', allowEmptyArchive: true
                    dir('TrafficCapture/dockerSolution') {
                        sh 'docker compose -f src/main/docker/docker-compose.yml down --volumes || true'
                    }
                }
            }
        }
    }
}
