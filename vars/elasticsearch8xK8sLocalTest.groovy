def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: config.jobName ?: 'elasticsearch-8x-k8s-local-test',
            sourceVersion: 'ES_8.19',
            targetVersion: 'OS_2.19'
    )
}