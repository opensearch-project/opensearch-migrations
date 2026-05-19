def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: config.jobName ?: 'opensearch-3-1-h2-k8s-local-test',
            sourceVersion: 'OS_3.1',
            targetVersion: 'OS_3.1',
            testIds: '0050'
    )
}
