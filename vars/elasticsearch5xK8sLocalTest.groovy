def call(Map config = [:]) {
    k8sLocalDeployment(
            gitUrl: config.gitUrl,
            gitBranch: config.gitBranch,
            jobName: 'elasticsearch-5x-k8s-local-test',
            sourceVersion: 'ES_5.6',
            targetVersion: 'OS_2.x'
    )
}