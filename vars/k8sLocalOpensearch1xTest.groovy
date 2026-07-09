def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: config.jobName ?: 'k8s-local-opensearch1x-test',
            sourceVersion: 'OS_1.3',
            targetVersion: 'OS_3.1',
            testIds: '0001,0002,0003,0010'
    )
}
