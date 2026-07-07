def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: config.jobName ?: 'external-kafka-k8s-local-test',
            sourceVersion: 'ES_8.19',
            targetVersion: 'OS_3.1',
            testIds: '0036'
    )
}
