def call(Map config = [:]) {
    def childJobName = "k8s-local-integ-test"

    def allSourceVersions = ['ES_1.5', 'ES_2.4', 'ES_5.6', 'ES_6.8', 'ES_7.10']
    def allTargetVersions = ['OS_1.3', 'OS_2.19', 'OS_3.1']

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }
        
        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
            choice(
                    name: 'SOURCE_VERSION',
                    choices: ['all'] + allSourceVersions,
                    description: 'Pick a specific source version, or "all"'
            )
            choice(
                    name: 'TARGET_VERSION',
                    choices: ['all'] + allTargetVersions,
                    description: 'Pick a specific target version, or "all"'
            )
        }

        options {
            timeout(time: 3, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '30'))
            skipDefaultCheckout(true)
        }

        triggers {
            // Trigger once per day at a hashed minute around 10 PM
            cron('H 22 * * *')
        }

        stages {
            stage('Checkout') {
                steps {
                    script {
                        sh 'sudo chown -R $(whoami) .'
                        sh 'sudo chmod -R u+w .'
                        // If in an existing git repository, remove any additional files in git tree that are not listed in .gitignore
                        if (sh(script: 'git rev-parse --git-dir > /dev/null 2>&1', returnStatus: true) == 0) {
                            echo 'Cleaning any existing git files in workspace'
                            sh 'git reset --hard'
                            sh 'git clean -fd'
                        } else {
                            echo 'No git project detected, this is likely an initial run of this pipeline on the worker'
                        }
                        git branch: "${params.GIT_BRANCH}", url: "${params.GIT_REPO_URL}"
                    }
                }
            }
            stage('Create and Monitor Integ Tests') {
                steps {
                    script {
                        // Determine which combinations to run. We currently will launch a separate k8sLocalDeployment job for
                        // each target version, and will let it run against the single source version specified or all source versions
                        def sourceVersions = params.SOURCE_VERSION
                        def targetVersions = params.TARGET_VERSION == 'all' ? allTargetVersions : [params.TARGET_VERSION]

                        echo "Source versions: ${sourceVersions}"
                        echo "Target versions: ${targetVersions}"
                        echo "Total created pipelines: ${targetVersions.size()}"

                        // Create parallel jobs map
                        def jobs = [:]
                        def results = [:]

                        sh "mkdir -p libraries/testAutomation/reports"

                        targetVersions.each { target ->
                            def source = sourceVersions
                            def combination = "${source}_to_${target}"
                            def displayName = "${source} → ${target}"

                            jobs[displayName] = {
                                try {
                                    def result = build(
                                            job: childJobName,
                                            parameters: [
                                                    string(name: 'SOURCE_VERSION', value: source),
                                                    string(name: 'TARGET_VERSION', value: target),
                                                    string(name: 'GIT_REPO_URL', value: params.GIT_REPO_URL),
                                                    string(name: 'GIT_BRANCH', value: params.GIT_BRANCH)
                                            ],
                                            wait: true,
                                            propagate: false // Don't fail parent if child fails
                                    )

                                    copyArtifacts(
                                            projectName: childJobName,
                                            selector: specific("${result.number}"),
                                            filter: 'reports/**',
                                            target: "libraries/testAutomation/reports",
                                            flatten: true,
                                            optional: true
                                    )

                                    results[displayName] = [
                                            result  : result.result,
                                            url     : result.absoluteUrl,
                                            number  : result.number,
                                            duration: result.duration
                                    ]

                                } catch (Exception e) {
                                    echo "💥 ${displayName}: EXCEPTION - ${e.message}"
                                    results[displayName] = [
                                            result  : 'EXCEPTION',
                                            url     : '',
                                            number  : 0,
                                            duration: 0,
                                            error   : e.message
                                    ]
                                }
                            }
                        }

                        // Execute all jobs in parallel
                        parallel jobs

                        echo "📊 All migration tests completed. Processing results..."

                        // Process and display results
                        def successCount = 0
                        def failureCount = 0
                        def totalCount = results.size()

                        echo ""
                        echo "=" * 60
                        echo "📊 MIGRATION TEST RESULTS SUMMARY"
                        echo "=" * 60

                        results.each { combination, data ->
                            def status = ""
                            def details = ""

                            switch(data.result) {
                                case 'SUCCESS':
                                    status = "✅"
                                    successCount++
                                    details = "Build #${data.number} (${data.duration}ms)"
                                    break
                                case 'FAILURE':
                                    status = "❌"
                                    failureCount++
                                    details = "Build #${data.number} - FAILED"
                                    currentBuild.result = 'UNSTABLE'
                                    break
                                case 'ABORTED':
                                    status = "⏹️"
                                    failureCount++
                                    details = "Build #${data.number} - ABORTED"
                                    currentBuild.result = 'UNSTABLE'
                                    break
                                case 'EXCEPTION':
                                    status = "💥"
                                    failureCount++
                                    details = "Exception: ${data.error}"
                                    currentBuild.result = 'UNSTABLE'
                                    break
                                default:
                                    status = "❓"
                                    failureCount++
                                    details = "Unknown result: ${data.result}"
                                    currentBuild.result = 'UNSTABLE'
                            }

                            echo "${status} ${combination.padRight(20)} | ${details}"
                            if (data.url) {
                                echo "   🔗 Details: ${data.url}"
                            }
                        }

                        echo "=" * 60
                        echo "📈 SUMMARY: ${successCount}/${totalCount} tests passed, ${failureCount} failed"
                        echo "=" * 60

                        // Set build description
                        currentBuild.description = "Migration Tests: ${successCount}/${totalCount} passed"

                        // Set overall build result
                        if (failureCount > 0) {
                            if (successCount == 0) {
                                currentBuild.result = 'FAILURE'
                                echo "🚨 All tests failed!"
                            } else {
                                currentBuild.result = 'UNSTABLE'
                                echo "⚠️  Some tests failed, but build marked as unstable"
                            }
                        } else {
                            echo "🎉 All tests passed successfully!"
                        }

                        // Store results as environment variables for potential use in post actions
                        env.TOTAL_TESTS = totalCount.toString()
                        env.PASSED_TESTS = successCount.toString()
                        env.FAILED_TESTS = failureCount.toString()
                    }
                }
            }
            stage('Print Complete Results') {
                steps {
                    timeout(time: 15, unit: 'MINUTES') {
                        dir('libraries/testAutomation') {
                            script {
                                sh "pipenv install --deploy"
                                sh "pipenv run app --test-reports-dir='./reports' --output-reports-summary-only"
                            }
                        }
                    }
                }
            }
        }
        
        post {
            always {
                script {
                    echo "🏁 Migration test matrix completed"
                    echo "📊 Final Results: ${env.PASSED_TESTS}/${env.TOTAL_TESTS} tests passed"
                }
            }
            success {
                script {
                    echo "🎉 All migration tests completed successfully!"
                }
            }
            unstable {
                script {
                    echo "⚠️  Migration tests completed with some failures"
                }
            }
            failure {
                script {
                    echo "🚨 Migration test matrix failed"
                }
            }
        }
    }
}
