def call(Map config = [:]) {
    k8sLocalDeployment(
            gitUrl: config.gitUrl,
            gitBranch: config.gitBranch,
            jobName: 'elasticsearch-8x-k8s-local-test',
            sourceVersion: 'ES_8.11',
            targetVersion: 'OS_2.19'
    )
}