def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: 'elasticsearch-5x-k8s-local-test'
    )
}