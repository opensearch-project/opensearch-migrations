def call(Map config = [:]) {
    def jobName = config.jobName ?: "eks-cdc-esos-matrix-test"
    def defaultChildJobName = "pr-checks/pr-eks-cdc-esos-integ-test"
    if (jobName.startsWith("main-")) {
        defaultChildJobName = "main/main-eks-cdc-esos-integ-test"
    } else if (jobName.startsWith("pr-")) {
        defaultChildJobName = "pr-checks/pr-eks-cdc-esos-integ-test"
    }
    def childJobName = config.childJobName ?: defaultChildJobName
    def enablePeriodicSchedule = config.containsKey('enablePeriodicSchedule') ?
            config.enablePeriodicSchedule :
            !jobName.startsWith("pr-")

    def allTargetVersions = ['OS_2.19', 'OS_3.1']

    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

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
            cron(enablePeriodicSchedule ? 'H 22 * * *' : '')
        }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/opensearch-project/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to use for repository')
            string(name: 'GIT_COMMIT', defaultValue: '', description: '(Optional) Specific commit to checkout after cloning branch')
            choice(
                    name: 'TARGET_VERSION',
                    choices: ['all'] + allTargetVersions,
                    description: 'Pick a specific target version, or "all"'
            )
        }

        options {
            timeout(time: 5, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '30'))
            skipDefaultCheckout(true)
        }

        stages {
            stage('Checkout') {
                steps {
                    checkoutStep(branch: params.GIT_BRANCH, repo: params.GIT_REPO_URL, commit: params.GIT_COMMIT)
                }
            }
            stage('Fan Out by Target Version') {
                steps {
                    script {
                        def targetVersions = params.TARGET_VERSION == 'all' ? allTargetVersions : [params.TARGET_VERSION]

                        echo "Target versions: ${targetVersions}"
                        echo "Total pipelines: ${targetVersions.size()}"

                        def jobs = [:]
                        def results = [:]

                        targetVersions.each { target ->
                            def displayName = "ES_7.10 → ${target}"

                            jobs[displayName] = {
                                try {
                                    def result = build(
                                            job: childJobName,
                                            parameters: [
                                                    string(name: 'TARGET_VERSION', value: target),
                                                    string(name: 'GIT_REPO_URL', value: params.GIT_REPO_URL),
                                                    string(name: 'GIT_BRANCH', value: params.GIT_BRANCH),
                                                    string(name: 'GIT_COMMIT', value: params.GIT_COMMIT)
                                            ],
                                            wait: true,
                                            propagate: false
                                    )

                                    results[displayName] = [
                                            result  : result.result,
                                            url     : result.absoluteUrl,
                                            number  : result.number,
                                            duration: result.duration
                                    ]
                                } catch (Exception e) {
                                    results[displayName] = [
                                            result  : 'EXCEPTION',
                                            error   : e.message
                                    ]
                                }
                            }
                        }

                        parallel jobs

                        // Print results
                        def successCount = results.count { it.value.result == 'SUCCESS' }
                        def totalCount = results.size()

                        echo "=" * 60
                        echo "CDC ES→OS MATRIX RESULTS"
                        echo "=" * 60
                        results.each { combo, data ->
                            def status = data.result == 'SUCCESS' ? '✅' : '❌'
                            echo "${status} ${combo} | ${data.result}"
                            if (data.url) echo "   🔗 ${data.url}"
                        }
                        echo "=" * 60
                        echo "SUMMARY: ${successCount}/${totalCount} passed"

                        currentBuild.description = "CDC ES→OS: ${successCount}/${totalCount} passed"
                        if (successCount < totalCount) {
                            currentBuild.result = successCount == 0 ? 'FAILURE' : 'UNSTABLE'
                        }
                    }
                }
            }
        }
    }
}
