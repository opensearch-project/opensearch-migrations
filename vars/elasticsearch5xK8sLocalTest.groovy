def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: 'elasticsearch-5x-k8s-local-test',
            sourceVersion: 'ES_5.6',
            targetVersion: 'OS_3.1',
            testIds: '0001,0004,0005'
    )
}
