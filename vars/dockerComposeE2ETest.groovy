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

            stage('Install Docker Compose v2') {
                steps {
                    // Jenkins AL2023 agents ship Docker 20.x without the Compose v2 plugin,
                    // so `docker compose` fails with "unknown shorthand flag: 'f' in -f".
                    // Install the official standalone binary into the user-level
                    // cli-plugins dir; Docker auto-discovers it there. Idempotent.
                    sh '''
                        set -euo pipefail
                        PLUGIN_DIR="$HOME/.docker/cli-plugins"
                        mkdir -p "$PLUGIN_DIR"
                        if ! docker compose version >/dev/null 2>&1; then
                            ARCH="$(uname -m)"
                            curl -fsSL -o "$PLUGIN_DIR/docker-compose" "https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-${ARCH}"
                            chmod +x "$PLUGIN_DIR/docker-compose"
                        fi
                        docker compose version
                    '''
                }
            }

            stage('Build Docker Images') {
                steps {
                    timeout(time: 15, unit: 'MINUTES') {
                        script {
                            // ECR_PULL_THROUGH_ENDPOINT is exported by /etc/profile.d/ecr-pull-through.sh
                            // on Jenkins hosts, which only login shells source. Read it via `bash -l`
                            // so the non-login Jenkins sh step can forward it to buildDockerImages.sh.
                            def ptcEndpoint = sh(script: 'bash -l -c \'echo -n $ECR_PULL_THROUGH_ENDPOINT\'', returnStdout: true).trim()
                            withEnv(ptcEndpoint ? ["ECR_PULL_THROUGH_ENDPOINT=${ptcEndpoint}"] : []) {
                                sh './deployment/cdk/opensearch-service-migration/buildDockerImages.sh'
                            }
                        }
                    }
                }
            }

            stage('Docker Compose Up') {
                steps {
                    timeout(time: 5, unit: 'MINUTES') {
                        sh './gradlew -p TrafficCapture dockerSolution:composeUp -x test -x spotlessCheck --info --stacktrace'
                        sh 'docker ps'
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
                    sh './gradlew -p TrafficCapture dockerSolution:composeDown -x test -x spotlessCheck || true'
                }
            }
        }
    }
}
