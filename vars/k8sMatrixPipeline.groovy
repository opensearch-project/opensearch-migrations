def call(Map config = [:]) {
    def childJobName = "elasticsearch-5x-k8s-local-test"

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }
        
        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/lewijacn/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'k8s-matrix-test', description: 'Git branch to use for repository')
            choice(
                name: 'SOURCE_VERSION',
                choices: ['(all)', 'ES_5.6', 'ES_7.10'],
                description: 'Pick a specific source version, or "(all)"'
            )
            choice(
                name: 'TARGET_VERSION',
                choices: ['(all)', 'OS_2.19', 'OS_3.1'],
                description: 'Pick a specific target version, or "(all)"'
            )
        }

        options {
            timeout(time: 3, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '30'))
            skipDefaultCheckout(true)
        }

        stages {
            stage('Generate and Run Migration Tests') {
                steps {
                    script {
                        echo "🚀 Starting dynamic migration test matrix"
                        
                        // Determine which combinations to run
                        def sourceVersions = params.SOURCE_VERSION == '(all)' ? 
                            ['ES_5.6', 'ES_7.10'] : [params.SOURCE_VERSION]
                        def targetVersions = params.TARGET_VERSION == '(all)' ? 
                            ['OS_2.19', 'OS_3.1'] : [params.TARGET_VERSION]
                        
                        echo "📋 Source versions: ${sourceVersions}"
                        echo "📋 Target versions: ${targetVersions}"
                        echo "📋 Total combinations: ${sourceVersions.size() * targetVersions.size()}"
                        
                        // Create parallel jobs map
                        def jobs = [:]
                        def results = [:]
                        
                        sourceVersions.each { source ->
                            targetVersions.each { target ->
                                def combination = "${source}_to_${target}"
                                def displayName = "${source} → ${target}"
                                
                                jobs[displayName] = {
                                    echo "🔄 Starting: ${displayName}"
                                    
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
                                        
                                        results[displayName] = [
                                            result: result.result,
                                            url: result.absoluteUrl,
                                            number: result.number,
                                            duration: result.duration
                                        ]
                                        
                                        if (result.result == 'SUCCESS') {
                                            echo "✅ ${displayName}: SUCCESS (Build #${result.number})"
                                        } else {
                                            echo "❌ ${displayName}: ${result.result} (Build #${result.number})"
                                        }
                                        
                                    } catch (Exception e) {
                                        echo "💥 ${displayName}: EXCEPTION - ${e.message}"
                                        results[displayName] = [
                                            result: 'EXCEPTION',
                                            url: '',
                                            number: 0,
                                            duration: 0,
                                            error: e.message
                                        ]
                                    }
                                }
                            }
                        }
                        
                        echo "🏃 Running ${jobs.size()} migration tests in parallel..."
                        
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
