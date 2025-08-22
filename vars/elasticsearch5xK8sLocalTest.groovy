def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: 'elasticsearch-5x-k8s-local-test',
            sourceVersion: 'ES_5.6',
            targetVersion: 'OS_2.19',
            testIdsArg: '--test-ids=0005'
    )
}